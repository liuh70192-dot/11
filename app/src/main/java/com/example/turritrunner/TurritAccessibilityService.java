package com.example.turritrunner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private int screenW = 1080, screenH = 1920;

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
            if (busy) { cb.done(Collections.emptyList(), "正在执行任务，无法同时识别账号"); return; }
            busy = true; cancelled = false; inDiscovery = true;
            discoverCallback = cb; discovered.clear();
            if (!launchTurrit()) { finishDiscover("无法启动 Turrit：请确认已安装官方包 org.telegram.group"); return; }
            after(1100, () -> {
                if (!goMe()) { finishDiscover("未能进入 Turrit 的「我的」页面"); return; }
                after(650, () -> {
                    expandAccounts();
                    after(450, () -> sweepAccounts(0, 0));
                });
            });
        });
    }
    private void finishDiscover(String detail) {
        if (!busy || !inDiscovery) return;
        busy = false; inDiscovery = false;
        List<String> result = new ArrayList<>(discovered);
        DiscoverResult cb = discoverCallback; discoverCallback = null;
        Store.get(this).log("识别账号：" + result.size() + " 个；" + detail);
        if (cb != null) cb.done(result, detail);
    }
    private void sweepAccounts(int step, int phase) {
        if (cancelled) { finishDiscover("已取消"); return; }
        if (!isTurritForeground()) { finishDiscover("当前前台不是 Turrit"); return; }
        collectAccountLabels(discovered);
        if (step >= 8) {
            if (phase == 0) {
                // 某些版本会从中间位置展开；往回滑，再扫描一遍。
                swipe(screenW * .48f, screenH * .37f, screenW * .48f, screenH * .58f);
                after(450, () -> sweepAccounts(0, 1));
                return;
            }
            finishDiscover(discovered.isEmpty() ?
                "未读取到账号文本。可尝试手动添加账号；Turrit 版本可能不暴露无障碍文本。" :
                "扫描结束；如果部分账号遗漏，可再次识别或手动添加。 ");
            return;
        }
        swipe(screenW * .48f, phase == 0 ? screenH * .58f : screenH * .37f,
              screenW * .48f, phase == 0 ? screenH * .34f : screenH * .58f);
        after(480, () -> sweepAccounts(step + 1, phase));
    }

    /** 实际发送仅在页面识别成功后继续；单账号「立即测试」为不发送的安全检查。 */
    public void run(String label, String bot, String title, List<String> texts,
                    boolean dryRun, Result cb) {
        h.post(() -> {
            if (busy) { cb.done(false, "当前有其他任务在执行"); return; }
            if (TextUtils.isEmpty(label) || TextUtils.isEmpty(bot) || TextUtils.isEmpty(title)) {
                cb.done(false, "缺少账号、机器人用户名或机器人顶部名称关键词"); return;
            }
            if (!dryRun && (texts == null || texts.isEmpty())) {
                cb.done(false, "消息库为空"); return;
            }
            this.busy = true; this.cancelled = false; this.inDiscovery = false;
            this.callback = cb; this.targetLabel = label.trim();
            this.botUser = bot.replace("@", "").trim(); this.expectedTitle = title.trim();
            this.payload = texts == null ? Collections.emptyList() : texts;
            this.completedMessages = 0;
            if (!botUser.matches("[A-Za-z0-9_]{5,32}")) {
                finish(false, "机器人用户名格式不正确"); return;
            }
            if (!launchTurrit()) { finish(false, "Turrit 未安装或无法启动"); return; }
            after(1100, () -> {
                if (!goMe()) { finish(false, "无法定位底部「我的」页面"); return; }
                after(550, () -> {
                    expandAccounts();
                    after(450, () -> chooseAccount(0, 0, dryRun));
                });
            });
        });
    }
    private void finish(boolean ok, String reason) {
        if (!busy || inDiscovery) return;
        busy = false;
        Result cb = callback; callback = null;
        Store.get(this).log((ok ? "成功" : "失败") + "：" + targetLabel + " / " + reason);
        if (cb != null) cb.done(ok, reason);
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
    private boolean goMe() {
        if (!isTurritForeground()) return false;
        AccessibilityNodeInfo me = findLabel("我的", .79f, 1f);
        if (me == null) me = findLabel("Me", .79f, 1f);
        if (me != null && click(me)) return true;
        return tap(screenW * .91f, screenH * .945f);
    }
    private void expandAccounts() {
        // 「添加账号」出现在列表底部时说明列表已展开，避免重复折叠。
        if (findLabel("添加账号", .25f, .78f) != null) return;
        AccessibilityNodeInfo arrow = findLabel("展开", .10f, .36f);
        if (arrow != null && click(arrow)) return;
        tap(screenW * .915f, screenH * .21f);
    }
    private boolean headerIsAccount(String label) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return findTextRec(root, label, .14f, .295f, true) != null;
    }
    private void chooseAccount(int step, int phase, boolean dryRun) {
        if (!isTurritForeground()) { finish(false, "当前不是 Turrit 界面"); return; }
        if (headerIsAccount(targetLabel)) { openBot(dryRun); return; }
        AccessibilityNodeInfo node = findTextRec(getRootInActiveWindow(), targetLabel, .29f, .70f, true);
        if (node != null) {
            if (!click(node)) { finish(false, "账号行不可点击：" + targetLabel); return; }
            after(900, () -> {
                // 切换后通常回到聊天页面；重复进入「我的」验证当前账号。
                if (!goMe()) { finish(false, "切换后无法验证账号"); return; }
                after(550, () -> {
                    if (headerIsAccount(targetLabel)) openBot(dryRun);
                    else finish(false, "切换账号验证失败，已阻止发送：" + targetLabel);
                });
            });
            return;
        }
        if (step >= 9) {
            if (phase == 0) {
                swipe(screenW * .48f, screenH * .36f, screenW * .48f, screenH * .58f);
                after(450, () -> chooseAccount(0, 1, dryRun));
            } else finish(false, "账号列表已上下搜索，未找到：" + targetLabel);
            return;
        }
        swipe(screenW * .48f, phase == 0 ? screenH * .59f : screenH * .35f,
              screenW * .48f, phase == 0 ? screenH * .35f : screenH * .59f);
        after(430, () -> chooseAccount(step + 1, phase, dryRun));
    }

    private void openBot(boolean dryRun) {
        if (!isTurritForeground()) { finish(false, "Turrit 不在前台"); return; }
        // 显式把 Telegram deep link 交给 Turrit，避免浏览器或其它 Telegram 客户端劫持。
        Uri address = Uri.parse("tg://resolve?domain=" + Uri.encode(botUser));
        Intent intent = new Intent(Intent.ACTION_VIEW, address);
        intent.setPackage(Store.TURRIT_PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(intent); }
        catch (Exception e) { finish(false, "Turrit 不支持该机器人直达链接：" + e.getClass().getSimpleName()); return; }
        after(1550, () -> verifyChat(0, dryRun));
    }
    private void verifyChat(int attempt, boolean dryRun) {
        if (!isTurritForeground()) { finish(false, "打开机器人后，前台不是 Turrit"); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        // 不以聊天历史中的关键词作为验证。只检查顶部标题区域，防止发错人。
        AccessibilityNodeInfo title = findTextRec(root, expectedTitle, .035f, .22f, false);
        AccessibilityNodeInfo edit = editableAtBottom(root);
        if (title != null && edit != null) {
            if (dryRun) { finish(true, "账号与机器人顶部标题已验证（未发送）"); return; }
            clearDraftAndSend(0);
            return;
        }
        if (attempt < 5) { after(550, () -> verifyChat(attempt + 1, dryRun)); return; }
        finish(false, "无法验证机器人顶部名称或输入框；请检查「顶部名称关键词」，未发送");
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
    private void collectAccountLabels(Set<String> out) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Set<String> excludes = new HashSet<>();
        Collections.addAll(excludes, "添加账号", "我的", "收藏夹", "下载", "实用工具",
            "聊天", "联系人", "发现", "设置", "扫一扫", "编辑资料", "My", "Add Account");
        collectLabelNodes(root, out, excludes);
    }
    private void collectLabelNodes(AccessibilityNodeInfo node, Set<String> out, Set<String> excludes) {
        if (node == null) return;
        Rect r = new Rect(); node.getBoundsInScreen(r);
        String content = readText(node).trim();
        if (content.isEmpty() && node.getChildCount() == 0)
            content = safe(node.getContentDescription()).trim();
        // 取正文账号区域中的文字，排除右侧蓝色未读数字徽章。
        if (node.isVisibleToUser() && !content.isEmpty()
            && r.centerY() >= screenH * .29f && r.centerY() <= screenH * .69f
            && r.centerX() >= screenW * .105f && r.centerX() <= screenW * .82f
            && content.length() <= 50 && !content.contains("\n") && !excludes.contains(content)) {
            out.add(content);
        }
        for (int i = 0; i < node.getChildCount(); i++) collectLabelNodes(node.getChild(i), out, excludes);
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
