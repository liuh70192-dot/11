package com.example.turritrunner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.function.Consumer;

/**
 * MuMu/Turrit 前台无障碍自动化。
 * 坚持「验证后操作」：任何页面、目标名称或编辑框无法确认时，不发送、不猜测。
 */
public class TurritAccessibilityService extends AccessibilityService {
    public interface Result { void done(boolean ok, String detail); }
    public interface DiscoverResult { void done(List<String> names, String detail); }
    private static TurritAccessibilityService active;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean busy = false;
    private boolean cancelled = false;
    private Result callback;
    private String targetLabel, botUser, expectedTitle;
    private List<String> payload = Collections.emptyList();
    private int completedMessages;
    private final Set<String> discovered = new LinkedHashSet<>();
    private DiscoverResult discoverCallback;
    private boolean inDiscovery;
    private boolean returnAfterManualTest;
    private boolean botStartTapped;
    private int screenW = 1080, screenH = 1920;
    // 账号扫描任务的状态（v0.4 曾漏掉这三个字段，造成 Java 编译失败）。
    private int expectedAccountCount = -1;
    private int noProgressSwipes = 0;
    private String previousScanSignature = "";
    // 完整扫描：先把可滚动账号列表拉到顶部，再逐屏向下找账号。
    // 以前依赖个人资料头像判断到顶，但头像固定可见，导致遗漏列表尾部的账号。
    private static final int INITIAL_TOP_SWIPES = 3;
    private static final int MAX_ACCOUNT_SWIPES = 7;
    private static final int MAX_REVERSE_SWIPES = 3;
    // 单账号选择：先有限次数回到列表顶部，再向下寻找。两次画面不变立即停止。
    private static final int MAX_SELECT_TOP_SWIPES = 5;
    private static final int MAX_SELECT_DOWN_SWIPES = 7;

    private static final Pattern ACCOUNT_COUNTER = Pattern.compile("^(\\d{1,2})\\s*/\\s*10$");


    public static TurritAccessibilityService current() { return active; }
    public boolean isBusy() { return busy; }
    @Override public void onCreate() {
        super.onCreate();
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager != null) {
            Display display = manager.getDefaultDisplay();
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            screenW = metrics.widthPixels;
            screenH = metrics.heightPixels;
        }
    }
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); active = this;
        Store.get(this).log("无障碍服务已连接");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* 定时流程按需读取节点树 */ }
    @Override public void onInterrupt() { cancel("无障碍服务被中断"); }
    @Override public void onDestroy() { cancel("无障碍服务已关闭"); if (active == this) active = null; super.onDestroy(); }

    public void cancel(String reason) {
        cancelled = true;
        if (!busy) return;
        if (inDiscovery) finishDiscover(reason);
        else finish(false, reason);
    }

    public void discover(DiscoverResult cb) {
        h.post(() -> {
            if (busy) { cb.done(Collections.emptyList(), "正在执行任务"); return; }
            busy = true; cancelled = false; inDiscovery = true;
            discoverCallback = cb; discovered.clear(); expectedAccountCount = -1;
            noProgressSwipes = 0; previousScanSignature = "";
            if (!launchTurrit()) { finishDiscover("无法启动 Turrit"); return; }
            after(1100, () -> navigateToMe(0,
                    () -> ensureAccountPanelOpen(0, () -> moveAccountListToTop(0), this::finishDiscover),
                    this::finishDiscover));
        });
    }
    private void finishDiscover(String detail) {
        if (!busy || !inDiscovery) return;
        busy = false; inDiscovery = false;
        List<String> result = new ArrayList<>(discovered);
        // 自动持久化在无障碍服务内完成；即使 Activity 在后台甚至被回收，也不会丢失。
        // 只有明确识别齐「6/10」全部账号，才用新名单替换旧名单。
        // 数量不足时只增量合并，避免漏扫把已有账号删掉。
        Store store = Store.get(this);
        if (!result.isEmpty()) {
            if (expectedAccountCount > 0 && result.size() == expectedAccountCount) {
                store.replaceFoundAccounts(result);
            } else {
                store.mergeFoundAccounts(result);
            }
        }
        String count = expectedAccountCount > 0 ? result.size() + "/" + expectedAccountCount
                                                    : result.size() + " 个";
        String summary = (result.isEmpty() ? "未识别到账号" : "已自动保存 " + count + " 账号")
                + "；" + detail;
        store.log("识别账号：" + summary);
        DiscoverResult cb = discoverCallback; discoverCallback = null;
        if (cb != null) cb.done(result, summary);
        // 识别在 Turrit 里完成后，直接回到 APK 的「账号」页。
        // 之前只回调后台 Activity，Turrit 始终位于前台，用户看不到保存结果。
        if (!cancelled) returnToAssistant(1);
    }

    private void moveAccountListToTop(int attempt) {
        if (cancelled) { finishDiscover("已取消"); return; }
        if (!isTurritForeground()) { finishDiscover("Turrit 不在前台"); return; }
        AccountSnapshot shot = accountSnapshot();
        if (!shot.valid) {
            finishDiscover("账号列表已离开屏幕，已停止操作"); return;
        }
        if (shot.total > 0) expectedAccountCount = shot.total;
        // 有时一打开 Turrit，列表已经停在中间。回拉期间也收集账号，避免遗漏。
        discovered.addAll(shot.names);
        if (expectedAccountCount > 0 && discovered.size() >= expectedAccountCount) {
            finishDiscover("账号数已与 Turrit 显示数量一致"); return;
        }
        if (attempt >= INITIAL_TOP_SWIPES) {
            noProgressSwipes = 0;
            previousScanSignature = "";
            sweepAccounts(0); return;
        }
        if (!swipeAccountListTowardTop()) {
            sweepAccounts(0); return;
        }
        after(590, () -> moveAccountListToTop(attempt + 1));
    }

    private void sweepAccounts(int swipes) {
        if (cancelled) { finishDiscover("已取消"); return; }
        if (!isTurritForeground()) { finishDiscover("Turrit 不在前台"); return; }
        AccountSnapshot snapshot = accountSnapshot();
        if (!snapshot.valid) {
            finishDiscover("账号区域不可读取，已停止扫描"); return;
        }
        if (snapshot.total > 0) expectedAccountCount = snapshot.total;
        int before = discovered.size();
        discovered.addAll(snapshot.names);
        String signature = snapshot.signature();
        if (before == discovered.size() && signature.equals(previousScanSignature)) noProgressSwipes++;
        else noProgressSwipes = 0;
        previousScanSignature = signature;

        if (expectedAccountCount > 0 && discovered.size() >= expectedAccountCount) {
            finishDiscover("账号数量已核对完成"); return;
        }
        // 最多前进 7 次；若仍未齐，回退有限次数补扫，而不是无限向下滑。
        if (swipes >= MAX_ACCOUNT_SWIPES || (swipes >= 4 && noProgressSwipes >= 3)) {
            sweepAccountsReverse(0); return;
        }
        float bottom = Math.min(screenH * .67f, snapshot.addTop - screenH * .045f);
        float top = Math.max(screenH * .30f, bottom - screenH * .23f);
        if (bottom - top < screenH * .09f) {
            sweepAccountsReverse(0); return;
        }
        if (!swipe(screenW * .44f, bottom, screenW * .44f, top)) {
            sweepAccountsReverse(0); return;
        }
        after(600, () -> sweepAccounts(swipes + 1));
    }

    private void sweepAccountsReverse(int reverseSwipes) {
        if (cancelled) { finishDiscover("已取消"); return; }
        if (!isTurritForeground()) { finishDiscover("Turrit 不在前台"); return; }
        AccountSnapshot snapshot = accountSnapshot();
        if (!snapshot.valid) { finishDiscover("扫描结束，列表不可读取"); return; }
        if (snapshot.total > 0) expectedAccountCount = snapshot.total;
        discovered.addAll(snapshot.names);
        if (expectedAccountCount > 0 && discovered.size() >= expectedAccountCount) {
            finishDiscover("账号数量已核对完成"); return;
        }
        if (reverseSwipes >= MAX_REVERSE_SWIPES) {
            finishDiscover(expectedAccountCount > discovered.size()
                    ? "Turrit 显示 " + expectedAccountCount + " 个，当前只读取到 " + discovered.size()
                            + " 个；已保存已识别账号，未清除旧账号"
                    : "有限次数双向扫描已结束");
            return;
        }
        if (!swipeAccountListTowardTop()) {
            finishDiscover("列表已停止滚动，已保留本次识别结果"); return;
        }
        after(600, () -> sweepAccountsReverse(reverseSwipes + 1));
    }

    /** 每次操作先回到 Turrit 主导航；单账号「测试」也实际发送消息池的 1 条文字。 */
    public void run(String label, String bot, String title, List<String> texts,
                    boolean manualTest, Result cb) {
        h.post(() -> {
            if (busy) { cb.done(false, "当前有其他任务在执行"); return; }
            if (TextUtils.isEmpty(label) || TextUtils.isEmpty(bot) || TextUtils.isEmpty(title)) {
                cb.done(false, "缺少账号、机器人用户名或机器人顶部名称关键词"); return;
            }
            if (texts == null || texts.isEmpty()) {
                cb.done(false, "消息库为空"); return;
            }
            this.busy = true; this.cancelled = false; this.inDiscovery = false;
            this.returnAfterManualTest = manualTest;
            this.callback = cb; this.targetLabel = label.trim();
            this.botUser = bot.replace("@", "").trim(); this.expectedTitle = title.trim();
            this.payload = texts == null ? Collections.emptyList() : texts;
            this.completedMessages = 0;
            this.botStartTapped = false;
            if (!botUser.matches("[A-Za-z0-9_]{5,32}")) {
                finish(false, "机器人用户名格式不正确"); return;
            }
            if (!launchTurrit()) { finish(false, "Turrit 未安装或无法启动"); return; }
            after(1100, () -> navigateToMe(0,
                    () -> ensureAccountPanelOpen(0,
                            () -> prepareAccountSelection(0, manualTest),
                            message -> finish(false, message)),
                    message -> finish(false, message)));
        });
    }
    private void finish(boolean ok, String reason) {
        if (!busy || inDiscovery) return;
        busy = false;
        Result cb = callback; callback = null;
        boolean goBack = returnAfterManualTest;
        returnAfterManualTest = false;
        Store.get(this).log((ok ? "成功" : "失败") + "：" + targetLabel + " / " + reason);
        if (cb != null) cb.done(ok, reason);
        // 手动测试发送 1 条，结束后返回 APK；定时任务不强制切回界面。
        if (goBack && !cancelled) returnToAssistant(1);
    }
    private void returnToAssistant(int tab) {
        h.postDelayed(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(MainActivity.EXTRA_RETURN_TAB, tab);
            try { startActivity(intent); }
            catch (Exception e) {
                Store.get(this).log("完成后返回 APK 失败：" + e.getClass().getSimpleName());
            }
        }, 180);
    }
    private void after(long ms, Runnable r) {
        h.postDelayed(() -> { if (busy && !cancelled) r.run(); }, ms);
    }

    private boolean launchTurrit() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(Store.TURRIT_PKG);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(intent); return true; }
        catch (Exception e) { return false; }
    }
    private boolean isTurritForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null &&
            Store.TURRIT_PKG.contentEquals(root.getPackageName());
    }
    /** 只认同时存在的底部导航项，不能在机器人聊天页盲点右下角。 */
    private boolean hasMainTabBar() {
        AccessibilityNodeInfo me = findTextRec(getRootInActiveWindow(), "我的", .85f, 1f, true);
        if (me == null) me = findTextRec(getRootInActiveWindow(), "Me", .85f, 1f, true);
        AccessibilityNodeInfo chats = findTextRec(getRootInActiveWindow(), "聊天", .85f, 1f, true);
        if (chats == null) chats = findTextRec(getRootInActiveWindow(), "Chats", .85f, 1f, true);
        if (me == null || chats == null) return false;
        Rect m = new Rect(), c = new Rect();
        me.getBoundsInScreen(m); chats.getBoundsInScreen(c);
        return m.centerX() > screenW * .72f && c.centerX() < screenW * .28f;
    }
    private boolean goMe() {
        if (!isTurritForeground() || !hasMainTabBar()) return false;
        AccessibilityNodeInfo me = findTextRec(getRootInActiveWindow(), "我的", .85f, 1f, true);
        if (me == null) me = findTextRec(getRootInActiveWindow(), "Me", .85f, 1f, true);
        return me != null && click(me);
    }
    /**
     * 不论目前在聊天、机器人初始页面、个人资料或用户名弹窗，都先有限次数返回主导航。
     * 绝不能在不认识的页面按固定坐标点击「我的」，可能点到机器人按钮或资料选项。
     */
    private void navigateToMe(int backs, Runnable ready, Consumer<String> fail) {
        if (cancelled) { fail.accept("操作已取消"); return; }
        if (!isTurritForeground()) {
            if (backs < 2 && launchTurrit()) {
                after(650, () -> navigateToMe(backs + 1, ready, fail));
            } else fail.accept("无法回到 Turrit 主界面");
            return;
        }
        if (hasMainTabBar()) {
            if (!goMe()) { fail.accept("无法点击 Turrit 底部「我的」选项"); return; }
            after(650, () -> {
                if (!isTurritForeground() || !hasMainTabBar()) {
                    fail.accept("进入「我的」时页面发生变化"); return;
                }
                ready.run();
            });
            return;
        }
        if (backs >= 7) {
            fail.accept("连续返回后仍没有找到 Turrit 底部导航，停止操作"); return;
        }
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            fail.accept("无法关闭当前页面或弹窗"); return;
        }
        after(550, () -> navigateToMe(backs + 1, ready, fail));
    }
    /**
     * Turrit 个人页可整体滚动：先回到顶部，再根据「添加账号」/「6/10」检测
     * 展开状态。不能在尚未确认顶部位置时盲点箭头，否则可能把已展开列表收起。
     */
    private void ensureAccountPanelOpen(int returnToTopTries, Runnable ready, Consumer<String> fail) {
        if (!isTurritForeground()) { fail.accept("Turrit 不在前台"); return; }
        if (accountSnapshot().valid) { ready.run(); return; }
        if (returnToTopTries < 2 && !isProfileTopVisible()) {
            // 从页面中部向下拉，让个人资料头、展开箭头回到屏幕上方。
            swipe(screenW * .46f, screenH * .36f, screenW * .46f, screenH * .78f);
            after(620, () -> ensureAccountPanelOpen(returnToTopTries + 1, ready, fail));
            return;
        }
        // 仅点一次箭头，之后必须验证，绝不反复盲点/盲滑。
        AccessibilityNodeInfo arrow = findLabel("展开", .10f, .38f);
        boolean triggered = arrow != null && click(arrow);
        if (!triggered) triggered = tap(screenW * .922f, screenH * .212f);
        if (!triggered) { fail.accept("无法点击 Turrit 的账号展开按钮"); return; }
        after(720, () -> {
            if (!isTurritForeground()) { fail.accept("展开账号后 Turrit 不在前台"); return; }
            if (accountSnapshot().valid) {
                ready.run();
            } else {
                fail.accept("已尝试自动展开账号，但 Turrit 无障碍节点未返回「添加账号」或账号数量。请确认该页面显示账号列表；如已展开仍报错，说明需要进一步适配此版本的 Turrit 控件。");
            }
        });
    }
    private boolean isProfileTopVisible() {
        if (profileHeaderLabel() != null) return true;
        // 有些版本的用户名位于非叶子节点或 contentDescription 中。
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        collectTextLeaves(root, labels);
        for (AccessibilityNodeInfo node : labels) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            String value = nodeText(node).trim();
            if (value.matches("@[A-Za-z0-9_]{3,32}") && r.centerY() > screenH * .10f
                    && r.centerY() < screenH * .35f) return true;
        }
        return false;
    }
    private void prepareAccountSelection(int attempt, boolean manualTest) {
        if (!isTurritForeground()) { finish(false, "Turrit 已离开前台"); return; }
        if (!accountSnapshot().valid) {
            finish(false, "无法读取展开后的账号列表，已停止切换"); return;
        }
        // 与识别账号不同，切换不能只从当前位置向下滚动。
        // 用户可能上次停在底部（只看见「于你执念」和「添加账号」），目标在上面。
        chooseAccount(0, 0, manualTest, "", 0);
    }

    /**
     * 从已展开的账号列表向顶部滚动：触摸起点必须落在【可见账号行】里。
     * 不能依赖「添加账号」的位置决定是否允许回滑：列表滑到底后，添加账号在屏幕上方，
     * 旧代码因为计算出负距离而放弃回滑，所以找不到顶部的账号。
     */
    private boolean swipeAccountListTowardTop() {
        AccountSnapshot snapshot = accountSnapshot();
        if (!snapshot.valid) return false;
        float start = snapshot.lastRowCenterY > 0
                ? Math.max(screenH * .27f, Math.min(screenH * .52f, snapshot.lastRowCenterY))
                : screenH * .34f;
        float end = Math.min(screenH * .79f, start + screenH * .34f);
        if (end - start < screenH * .16f) return false;
        return swipe(screenW * .45f, start, screenW * .45f, end);
    }

    /** 只允许从可见账号行向上滑，避免在底部「缓存/设置」菜单区域空滑。 */
    private boolean swipeAccountListTowardBottom(AccountSnapshot snapshot) {
        if (!snapshot.valid || snapshot.lastRowCenterY <= 0) return false;
        float start = Math.max(screenH * .27f,
                Math.min(screenH * .66f, snapshot.lastRowCenterY));
        float end = Math.max(screenH * .12f, start - screenH * .30f);
        if (start - end < screenH * .16f) return false;
        return swipe(screenW * .45f, start, screenW * .45f, end);
    }

    private boolean headerIsAccount(String label) {
        // 必须是个人资料的【大号标题】，不能把页面滑动后的普通账号行当作顶部标题。
        String header = profileHeaderLabel();
        return header != null && header.equals(label);
    }

    /**
     * phase=0 回到顶部，phase=1 从顶部向下逐屏寻找。
     * 同一画面连续出现两次即说明已经到边界，立即改方向或结束；不无限滑动。
     * 每一次滑动前都先查找目标，能看到目标时立即停止滚动。
     */
    private void chooseAccount(int step, int phase, boolean manualTest,
                               String priorSignature, int stagnant) {
        if (!isTurritForeground()) { finish(false, "当前不是 Turrit 界面"); return; }
        if (headerIsAccount(targetLabel)) { openBot(manualTest); return; }
        AccountSnapshot snapshot = accountSnapshot();
        if (!snapshot.valid) { finish(false, "账号列表已关闭或不可读，已停止切换"); return; }
        AccessibilityNodeInfo node = findAccountRow(targetLabel, snapshot.addTop);
        if (node != null) {
            if (!click(node)) { finish(false, "账号无法点击：" + targetLabel); return; }
            after(850, () -> navigateToMe(0,
                    () -> verifySwitchedAccount(0, manualTest),
                    message -> finish(false, "切换账号后返回「我的」失败：" + message)));
            return;
        }
        String signature = snapshot.signature();
        int unchanged = (step > 0 && signature.equals(priorSignature)) ? stagnant + 1 : 0;
        if (phase == 0) {
            // 已到顶、画面不变或回滑达到上限，开始向下查找。
            if (step >= MAX_SELECT_TOP_SWIPES || unchanged >= 1) {
                Store.get(this).log("账号查找：已回到顶部，向下查找「" + targetLabel + "」");
                chooseAccount(0, 1, manualTest, "", 0);
                return;
            }
            if (!swipeAccountListTowardTop()) {
                chooseAccount(0, 1, manualTest, "", 0);
                return;
            }
            after(600, () -> chooseAccount(step + 1, 0, manualTest, signature, unchanged));
            return;
        }
        // 到底后不再继续下滑；不点击「设置/缓存/客服」等无关控件。
        if (step >= MAX_SELECT_DOWN_SWIPES || unchanged >= 1) {
            finish(false, "已双向查找账号，列表中未找到「" + targetLabel
                    + "」；请确认 Turrit 中该账号名称未改变");
            return;
        }
        if (!swipeAccountListTowardBottom(snapshot)) {
            finish(false, "账号列表已到边界，未找到「" + targetLabel + "」");
            return;
        }
        after(600, () -> chooseAccount(step + 1, 1, manualTest, signature, unchanged));
    }
    private void verifySwitchedAccount(int attempt, boolean manualTest) {
        if (!isTurritForeground()) { finish(false, "切换账号后离开 Turrit"); return; }
        if (hasMainTabBar() && headerIsAccount(targetLabel)) { openBot(manualTest); return; }
        if (!hasMainTabBar()) {
            navigateToMe(0, () -> verifySwitchedAccount(attempt + 1, manualTest),
                    message -> finish(false, "无法核对切换后的账号：" + message));
            return;
        }
        if (attempt >= 3) {
            finish(false, "切换后无法验证当前账号，已阻止发送：" + targetLabel);
            return;
        }
        // Turrit 往往保留上一个账号「我的」页面的滚动位置。
        // 即使列表在切换后自动折叠，也要能把【个人资料大标题】拉回屏幕，再验证。
        boolean moved = swipeAccountListTowardTop();
        if (!moved && !isProfileTopVisible()) {
            moved = swipe(screenW * .46f, screenH * .34f,
                    screenW * .46f, screenH * .76f);
        }
        if (!moved) {
            finish(false, "切换后无法确认顶部账号名称，未发送：" + targetLabel);
            return;
        }
        after(590, () -> verifySwitchedAccount(attempt + 1, manualTest));
    }

    private void openBot(boolean manualTest) {
        if (!isTurritForeground()) { finish(false, "Turrit 不在前台"); return; }
        // 以前只 setPackage，若同一个 Turrit 应用有两个 tg:// 处理 Activity，
        // Android 会弹出两个都叫“Turrit”的「打开方式」选择框，导致自动化中断。
        // 这里解析实际处理该链接的 Activity，并显式定位其中一个；不更改默认应用。
        Uri address = Uri.parse("tg://resolve?domain=" + Uri.encode(botUser));
        Intent intent = new Intent(Intent.ACTION_VIEW, address);
        intent.setPackage(Store.TURRIT_PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ComponentName handler = selectTurritDeepLinkHandler(intent);
        if (handler == null) {
            finish(false, "没有找到 Turrit 可用的 tg:// 处理页面，已停止操作");
            return;
        }
        intent.setComponent(handler);
        try { startActivity(intent); }
        catch (Exception e) { finish(false, "Turrit 无法打开机器人页面：" + e.getClass().getSimpleName()); return; }
        // 后续仍然校验机器人的顶部名称和聊天输入框，不会仅因打开了页面就发送。
        after(1550, () -> verifyChat(0, manualTest));
    }
    /** 仅在 org.telegram.group 内选取支持 tg:// 的 Activity，避免弹出系统「打开方式」。 */
    private ComponentName selectTurritDeepLinkHandler(Intent viewIntent) {
        List<ResolveInfo> candidates;
        try {
            candidates = getPackageManager().queryIntentActivities(
                    viewIntent, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (Exception error) {
            Store.get(this).log("查询 Turrit tg 链接处理页面失败："
                    + error.getClass().getSimpleName());
            return null;
        }
        if (candidates == null || candidates.isEmpty()) return null;
        Intent launcher = getPackageManager().getLaunchIntentForPackage(Store.TURRIT_PKG);
        String launcherClass = launcher != null && launcher.getComponent() != null
                ? launcher.getComponent().getClassName() : "";
        ResolveInfo selected = null;
        int bestScore = Integer.MIN_VALUE;
        for (ResolveInfo candidate : candidates) {
            if (candidate.activityInfo == null || !candidate.activityInfo.enabled
                    || !candidate.activityInfo.exported
                    || !Store.TURRIT_PKG.equals(candidate.activityInfo.packageName)) continue;
            String name = candidate.activityInfo.name;
            if (name == null || name.isEmpty()) continue;
            String lowerName = name.toLowerCase(Locale.ROOT);
            int score = 0;
            if (name.equals(launcherClass)) score += 100;
            if (lowerName.contains("launchactivity")) score += 60;
            if (lowerName.contains("resolver") || lowerName.contains("chooser")) score -= 80;
            // 同分时稳定选择字典序较小的实现，避免每次测试行为变化。
            if (selected == null || score > bestScore ||
                    (score == bestScore && name.compareTo(selected.activityInfo.name) < 0)) {
                selected = candidate;
                bestScore = score;
            }
        }
        if (selected == null) return null;
        Store.get(this).log("Turrit 直达机器人：固定使用 " + selected.activityInfo.name
                + "（候选 " + candidates.size() + "）");
        return new ComponentName(Store.TURRIT_PKG, selected.activityInfo.name);
    }

    private void verifyChat(int attempt, boolean manualTest) {
        if (!isTurritForeground()) { finish(false, "打开机器人后，前台不是 Turrit"); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        // 不以聊天历史中的关键词作为验证。只检查顶部标题区域，防止发错人。
        AccessibilityNodeInfo title = findTextRec(root, expectedTitle, .035f, .22f, false);
        AccessibilityNodeInfo edit = editableAtBottom(root);
        if (title != null && edit != null) {
            clearDraftAndSend(0);
            return;
        }
        // Turrit 首次进入机器人可能只有「启动机器人」按钮，没有文字输入框。
        // 只有顶部机器人标题明确匹配时才允许点击启动按钮，最多点击一次。
        if (title != null && edit == null && !botStartTapped) {
            AccessibilityNodeInfo start = findTextRec(root, "启动机器人", .79f, 1f, true);
            if (start == null) start = findTextRec(root, "Start Bot", .79f, 1f, false);
            if (start != null && click(start)) {
                botStartTapped = true;
                Store.get(this).log("已打开目标机器人的启动页，正在等待聊天输入框");
                after(1200, () -> verifyChat(0, manualTest));
                return;
            }
        }
        if (attempt < 6) { after(600, () -> verifyChat(attempt + 1, manualTest)); return; }
        finish(false, "无法验证机器人顶部标题和聊天输入框，或机器人启动后未出现输入框；未发送关键词");
    }
    private void clearDraftAndSend(int index) {
        AccessibilityNodeInfo edit = editableAtBottom(getRootInActiveWindow());
        if (edit == null) { finish(false, "未定位到聊天输入框，停止发送"); return; }
        if (!setText(edit, "")) { finish(false, "无法清空当前聊天草稿"); return; }
        after(330, () -> {
            AccessibilityNodeInfo fresh = editableAtBottom(getRootInActiveWindow());
            if (fresh == null || !isEmptyField(fresh)) {
                finish(false, "清空草稿后无法验证为空；已阻止发送"); return;
            }
            sendMessage(index);
        });
    }
    private void sendMessage(int index) {
        if (index >= payload.size()) { finish(true, "已发送 " + completedMessages + " 条"); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isTurritForeground() || findTextRec(root, expectedTitle, .035f, .22f, false) == null) {
            finish(false, "发送前机器人标题校验失败，已发送 " + completedMessages + " 条"); return;
        }
        AccessibilityNodeInfo edit = editableAtBottom(root);
        if (edit == null) { finish(false, "未找到输入框，已发送 " + completedMessages + " 条"); return; }
        String content = payload.get(index);
        if (!setText(edit, content)) { finish(false, "无法填写第 " + (index + 1) + " 条消息"); return; }
        after(450, () -> {
            AccessibilityNodeInfo latest = editableAtBottom(getRootInActiveWindow());
            if (latest == null || !content.equals(readText(latest))) {
                finish(false, "输入框内容不匹配，禁止点击发送"); return;
            }
            if (!pressSend(latest)) { finish(false, "未找到发送按钮"); return; }
            // 只检查一次发送结果，不自动重发，避免重复发送。
            after(900, () -> verifySent(index));
        });
    }
    private void verifySent(int index) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isTurritForeground() || findTextRec(root, expectedTitle, .035f, .22f, false) == null) {
            finish(false, "发送后页面已改变，无法确认是否发送，请人工检查"); return;
        }
        AccessibilityNodeInfo edit = editableAtBottom(root);
        if (edit == null) { finish(false, "发送后无法确认输入框状态，请人工检查"); return; }
        if (!isEmptyField(edit)) {
            finish(false, "点击发送后输入框仍有内容，未重试以防重复，请人工检查"); return;
        }
        // 输入框清空只表明客户端已接受发送动作，不等同于 Telegram 服务端送达。
        completedMessages++;
        if (index + 1 < payload.size()) after(400, () -> sendMessage(index + 1));
        else finish(true, "客户端发送动作已完成 " + completedMessages + " 条（不代表服务端确认）");
    }

    private boolean pressSend(AccessibilityNodeInfo input) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Rect inputBounds = new Rect(); input.getBoundsInScreen(inputBounds);
        AccessibilityNodeInfo send = findSendButton(root, inputBounds.centerY());
        if (send != null && click(send)) return true;
        Rect b = new Rect(); input.getBoundsInScreen(b);
        // Turrit 编辑框最右侧按钮，坐标随软键盘位置动态变化，非固定 Y。
        return tap(screenW * .92f, Math.max(0, Math.min(screenH, b.centerY())));
    }
    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, int inputY) {
        if (root == null) return null;
        Rect r = new Rect(); root.getBoundsInScreen(r);
        String s = (readText(root) + " " + safe(root.getContentDescription())).toLowerCase(Locale.ROOT);
        if ((s.equals("发送") || s.equals("send") || s.contains("send message") || s.contains("发送消息"))
                && r.centerX() > screenW * .65f && Math.abs(r.centerY() - inputY) < screenH * .12f
                && r.width() < screenW * .25f) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findSendButton(root.getChild(i), inputY);
            if (result != null) return result;
        }
        return null;
    }
    private AccessibilityNodeInfo editableAtBottom(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectEditable(root, nodes);
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo node : nodes) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            if (r.centerY() > screenH * .45f && r.centerY() > bestY) {
                best = node; bestY = r.centerY();
            }
        }
        return best;
    }
    private void collectEditable(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> out) {
        if (root == null) return;
        String cls = safe(root.getClassName());
        if ((root.isEditable() || cls.contains("EditText")) && root.isVisibleToUser()) out.add(root);
        for (int i = 0; i < root.getChildCount(); i++) collectEditable(root.getChild(i), out);
    }
    private boolean setText(AccessibilityNodeInfo edit, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
    private boolean isEmptyField(AccessibilityNodeInfo edit) {
        // getText() 对有 hint 的安卓 EditText 可能返回 placeholder；用 getHintText() 分辨。
        String content = readText(edit);
        String hint = safe(edit.getHintText());
        return content.isEmpty() || (edit.isShowingHintText() && (hint.isEmpty() || hint.equals(content)));
    }
    private AccessibilityNodeInfo findLabel(String text, float minY, float maxY) {
        return findTextRec(getRootInActiveWindow(), text, minY, maxY, false);
    }
    private AccessibilityNodeInfo findTextRec(AccessibilityNodeInfo root, String target,
                                              float minY, float maxY, boolean exact) {
        if (root == null || TextUtils.isEmpty(target)) return null;
        String content = readText(root);
        String desc = safe(root.getContentDescription());
        Rect r = new Rect(); root.getBoundsInScreen(r);
        boolean yOK = r.centerY() >= screenH * minY && r.centerY() <= screenH * maxY;
        boolean match = exact ? (target.equals(content) || target.equals(desc)) :
                (content.contains(target) || desc.contains(target));
        if (yOK && match && root.isVisibleToUser()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findTextRec(root.getChild(i), target, minY, maxY, exact);
            if (result != null) return result;
        }
        return null;
    }
    /** 账号边界：只读取「添加账号」上方的账号行；永远不读它下方的设置、缓存、客服等菜单。 */
    private static class AccountSnapshot {
        boolean valid;
        int total = -1;
        int addTop;
        int lastRowCenterY = -1;
        final Set<String> names = new LinkedHashSet<>();
        final List<String> visibleRows = new ArrayList<>();
        // 名称及屏幕位置决定当前是否真的滚动，忽略个人资料标题。
        String signature() { return visibleRows.toString() + "|" + addTop; }
    }
    private AccessibilityNodeInfo findAddAccountNode() {
        AccessibilityNodeInfo node = findTextRec(getRootInActiveWindow(), "添加账号", .06f, .82f, true);
        if (node == null) node = findTextRec(getRootInActiveWindow(), "Add Account", .06f, .82f, true);
        return node;
    }
    /**
     * 获取个人资料顶部的当前账号名称。
     * Turrit 的无 username 账号（例如「1」）只显示头像和名称，不显示 @xxx。
     * 旧版只支持有 @username 的个人资料头，因此在账号切换成功后会误报验证失败。
     */
    private String profileHeaderLabel() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectTextLeaves(root, leaves);
        AccessibilityNodeInfo username = null;
        for (AccessibilityNodeInfo node : leaves) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            String value = nodeText(node).trim();
            if (value.matches("@[A-Za-z0-9_]{3,32}") && r.centerY() > screenH * .10f
                    && r.centerY() < screenH * .34f && r.centerX() < screenW * .79f) {
                username = node; break;
            }
        }
        if (username != null) {
            Rect userR = new Rect(); username.getBoundsInScreen(userR);
            String selected = null;
            int bestDist = Integer.MAX_VALUE;
            for (AccessibilityNodeInfo node : leaves) {
                Rect r = new Rect(); node.getBoundsInScreen(r);
                String value = nodeText(node).trim();
                int dist = userR.centerY() - r.centerY();
                if (dist > 0 && dist < screenH * .10f && dist < bestDist
                        && r.centerX() > screenW * .18f && r.centerX() < screenW * .75f
                        && validAccountName(value)) { selected = value; bestDist = dist; }
            }
            if (selected != null) return selected;
        }
        // 没有 @username 的账户：仅从固定的「头像右边个人资料标题」区域读取。
        // 不能匹配下方账号列表，避免把列表中的其他账号误判为当前账号。
        String name = null;
        float best = -Float.MAX_VALUE;
        for (AccessibilityNodeInfo node : leaves) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            String value = nodeText(node).trim();
            if (!validAccountName(value)) continue;
            if (r.left < screenW * .265f || r.centerX() > screenW * .83f
                    || r.centerY() < screenH * .135f || r.centerY() > screenH * .295f
                    || r.height() < screenH * .031f)
                continue;
            String cls = safe(node.getClassName());
            if (!cls.contains("TextView") && node.getChildCount() != 0) continue;
            float score = r.height() * 4f - Math.abs(r.centerY() - screenH * .20f);
            if (score > best) { best = score; name = value; }
        }
        return name;
    }
    private AccountSnapshot accountSnapshot() {
        AccountSnapshot shot = new AccountSnapshot();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return shot;
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectTextLeaves(root, leaves);
        AccessibilityNodeInfo add = findAddAccountNode();
        AccessibilityNodeInfo counterNode = null;
        for (AccessibilityNodeInfo node : leaves) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            Matcher count = ACCOUNT_COUNTER.matcher(nodeText(node).trim());
            if (count.matches() && r.centerX() > screenW * .70f
                    && r.centerY() > screenH * .25f && r.centerY() < screenH * .83f) {
                shot.total = Math.min(10, Integer.parseInt(count.group(1)));
                counterNode = node;
                break;
            }
        }
        if (add == null && counterNode == null) return shot;
        Rect addR = new Rect();
        (add != null ? add : counterNode).getBoundsInScreen(addR);
        shot.addTop = addR.top;
        shot.valid = true;
        String header = profileHeaderLabel();
        if (header != null) shot.names.add(header);
        int minY = Math.round(screenH * .085f);
        for (AccessibilityNodeInfo node : leaves) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            String value = nodeText(node).trim();
            if (value.matches("@[A-Za-z0-9_]{3,32}") && r.centerY() < screenH * .34f) {
                minY = Math.max(minY, r.bottom + Math.round(screenH * .025f));
            }
        }
        for (AccessibilityNodeInfo node : leaves) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            String value = nodeText(node).trim();
            if (r.centerY() <= minY || r.centerY() >= shot.addTop - screenH * .010f) continue;
            if (r.centerX() < screenW * .13f || r.centerX() > screenW * .80f) continue;
            if (validAccountName(value)) {
                shot.names.add(value);
                shot.visibleRows.add(value + "@" + Math.round(r.centerY() / 18f));
                shot.lastRowCenterY = Math.max(shot.lastRowCenterY, r.centerY());
            }
        }
        return shot;
    }
    private AccessibilityNodeInfo findAccountRow(String wanted, int addTop) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectTextLeaves(root, leaves);
        int minY = Math.round(screenH * .085f);
        for (AccessibilityNodeInfo node : leaves) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            if (nodeText(node).trim().matches("@[A-Za-z0-9_]{3,32}") && r.centerY() < screenH * .34f)
                minY = Math.max(minY, r.bottom + Math.round(screenH * .025f));
        }
        for (AccessibilityNodeInfo node : leaves) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            if (nodeText(node).trim().equals(wanted) && r.centerY() > minY
                    && r.centerY() < addTop - screenH * .010f
                    && r.centerX() > screenW * .13f && r.centerX() < screenW * .80f) return node;
        }
        return null;
    }
    private void collectTextLeaves(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        String cls = safe(node.getClassName());
        // Turrit 有些文本节点自带子控件；不能仅检查 childCount == 0。
        if (node.isVisibleToUser() && !nodeText(node).trim().isEmpty()
                && (node.getChildCount() == 0 || cls.contains("TextView"))) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectTextLeaves(node.getChild(i), out);
    }
    private static String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        String t = readText(node);
        if (!t.trim().isEmpty()) return t;
        return safe(node.getContentDescription());
    }
    private boolean validAccountName(String value) {
        if (value.isEmpty() || value.length() > 40 || value.contains("\n") || value.startsWith("@")) return false;
        if (value.matches("\\d+\\s*/\\d+") || value.contains("添加账号")) return false;
        switch (value) {
            case "我的": case "Me": case "添加账号": case "Add Account":
            case "聊天": case "联系人": case "发现": case "下载": case "缓存":
            case "实用工具": case "收藏夹": case "设置": case "客服":
                return false;
            default: return true;
        }
    }
    private boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo current = node; current != null; current = current.getParent()) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        Rect r = new Rect(); node.getBoundsInScreen(r);
        return !r.isEmpty() && tap(r.centerX(), r.centerY());
    }
    private boolean tap(float x, float y) {
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 85);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }
    private boolean swipe(float x1, float y1, float x2, float y2) {
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 350);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }
    private static String safe(CharSequence x) { return x == null ? "" : x.toString(); }
    private static String readText(AccessibilityNodeInfo node) {
        return node == null ? "" : safe(node.getText());
    }
}
