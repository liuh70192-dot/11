package com.turrit.assistant.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.turrit.assistant.diagnostics.DiagnosticLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct Java port of the account-discovery path in 活跃jisou(5).zip.
 *
 * Important: this class owns BOTH the active-jisou accessibility-tree parser
 * (including recursive findAddAccountNode) and the bounded scan/gesture flow.
 * The host Kotlin parser is deliberately not used to decide whether scanning
 * may start, because some Turrit builds expose "添加账号" on a non-leaf node.
 */
public final class ActiveJisouAccountScanner {
    public interface SnapshotListener {
        void onSnapshot(Snapshot snapshot, AccessibilityNodeInfo root);
    }

    public interface Completion {
        void done(List<String> names, int expectedCount, String detail);
    }

    public static final class Snapshot {
        public final List<String> names;
        public final int total;
        public final int listTop;
        public final int addTop;
        public final int lastRowCenterY;
        public final int visibleRowCount;
        public final String signature;
        public final boolean valid;

        Snapshot(List<String> names, int total, int listTop, int addTop,
                 int lastRowCenterY, int visibleRowCount, String signature, boolean valid) {
            this.names = names == null ? new ArrayList<>() : new ArrayList<>(names);
            this.total = total;
            this.listTop = listTop;
            this.addTop = addTop;
            this.lastRowCenterY = lastRowCenterY;
            this.visibleRowCount = visibleRowCount;
            this.signature = signature == null ? "" : signature;
            this.valid = valid;
        }
    }

    private static final String TAG = "ActiveJisouScan";
    private static final Pattern ACCOUNT_COUNTER = Pattern.compile("^(\\d{1,2})\\s*/\\s*10$");
    private static final int INITIAL_TOP_SWIPES = 3;
    private static final int MAX_ACCOUNT_SWIPES = 7;
    private static final int MAX_REVERSE_SWIPES = 3;
    private static final int MAX_SCROLL_ACTION_FALLBACKS = 2;
    private static final long SWIPE_DURATION_MS = 350L;
    private static final long SETTLE_MS = 590L;

    private final AccessibilityService service;
    private final String targetPackage;
    private final int screenW;
    private final int screenH;
    private final SnapshotListener snapshotListener;
    private final Completion completion;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Set<String> discovered = new LinkedHashSet<>();

    private Runnable pending;
    private boolean running;
    private boolean finished;
    private int expectedAccountCount = -1;
    private int noProgressSwipes;
    private int scrollForwardFallbacks;
    private int scrollBackwardFallbacks;
    private String previousScanSignature = "";

    public ActiveJisouAccountScanner(
            AccessibilityService service,
            String targetPackage,
            int screenW,
            int screenH,
            SnapshotListener snapshotListener,
            Completion completion
    ) {
        this.service = service;
        this.targetPackage = targetPackage;
        this.screenW = screenW;
        this.screenH = screenH;
        this.snapshotListener = snapshotListener;
        this.completion = completion;
    }

    public void start() {
        if (running || finished) return;
        running = true;
        logInfo("启动 Java 识别器；package=" + targetPackage
                + "，屏幕=" + screenW + "x" + screenH);
        moveAccountListToTop(0);
    }

    public void cancel() {
        running = false;
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
        logInfo("扫描器已取消");
    }

    private void after(long delayMs, Runnable action) {
        if (!running || finished) return;
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> {
            pending = null;
            if (!running || finished) return;
            try {
                action.run();
            } catch (Throwable error) {
                Log.e(TAG, "Active-jisou scan step failed", error);
                DiagnosticLog.error(service, "账号识别/扫描步骤", "扫描步骤发生异常", error);
                finish("账号识别异常：" + error.getClass().getSimpleName());
            }
        };
        handler.postDelayed(pending, delayMs);
    }

    private Snapshot readSnapshot() {
        if (!running || finished) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        Snapshot snapshot = parseSnapshot(root, targetPackage, screenW, screenH);
        if (snapshot == null || !snapshot.valid) {
            logWarn("读取账号列表失败：" + describeParseFailure(
                    service.getRootInActiveWindow(), targetPackage, screenH));
            return null;
        }
        if (snapshot.total > 0) expectedAccountCount = snapshot.total;
        for (String name : snapshot.names) {
            if (name != null && !name.trim().isEmpty()) discovered.add(normalize(name));
        }
        if (snapshotListener != null) {
            try {
                snapshotListener.onSnapshot(snapshot, root);
            } catch (Throwable error) {
                DiagnosticLog.error(service, "账号识别/快照回调", "向任务助手同步当前屏幕账号失败", error);
            }
        }
        logInfo("快照读取成功：可见行=" + snapshot.visibleRowCount
                + "，本屏候选=" + snapshot.names.size()
                + "，累计去重=" + discovered.size()
                + "，Turrit显示=" + expectedAccountCount
                + "，列表顶部=" + snapshot.listTop
                + "，添加账号顶部=" + snapshot.addTop
                + "，末行中心Y=" + snapshot.lastRowCenterY
                + "，签名=" + snapshot.signature.hashCode());
        return snapshot;
    }

    private void moveAccountListToTop(int attempt) {
        if (!running || finished) return;
        Snapshot shot = readSnapshot();
        if (shot == null) {
            finish("账号列表不可读取，已停止扫描");
            return;
        }
        if (expectedAccountCount > 0 && discovered.size() >= expectedAccountCount) {
            finish("账号数已与 Turrit 显示数量一致");
            return;
        }
        if (attempt >= INITIAL_TOP_SWIPES) {
            noProgressSwipes = 0;
            previousScanSignature = "";
            sweepAccounts(0);
            return;
        }

        // Coordinates and direction are copied from active-jisou's
        // swipeAccountListTowardTop(): touch an account row and drag downward.
        float start = shot.lastRowCenterY > 0
                ? Math.max(screenH * .27f, Math.min(screenH * .52f, shot.lastRowCenterY))
                : screenH * .34f;
        float end = Math.min(screenH * .79f, start + screenH * .34f);
        boolean accepted = end - start >= screenH * .16f
                && swipe(screenW * .45f, start, screenW * .45f, end);
        logInfo("回到列表顶部手势 " + (attempt + 1) + "/" + INITIAL_TOP_SWIPES
                + "：(" + (int)(screenW * .45f) + "," + (int)start + ")->("
                + (int)(screenW * .45f) + "," + (int)end + ")，dispatchAccepted=" + accepted);
        if (!accepted) {
            if (scrollBackwardFallbacks < MAX_SCROLL_ACTION_FALLBACKS
                    && performScrollAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, shot)) {
                scrollBackwardFallbacks++;
                after(SETTLE_MS, () -> moveAccountListToTop(attempt + 1));
            } else {
                noProgressSwipes = 0;
                previousScanSignature = "";
                sweepAccounts(0);
            }
            return;
        }
        after(SETTLE_MS, () -> moveAccountListToTop(attempt + 1));
    }

    private void sweepAccounts(int swipes) {
        if (!running || finished) return;
        int beforeCount = discovered.size();
        Snapshot snapshot = readSnapshot();
        if (snapshot == null) {
            finish("账号区域不可读取，已停止扫描");
            return;
        }

        String signature = snapshot.signature;
        boolean moved = beforeCount != discovered.size()
                || (!previousScanSignature.isEmpty() && !signature.equals(previousScanSignature));
        if (!moved && signature.equals(previousScanSignature)) noProgressSwipes++;
        else noProgressSwipes = 0;
        previousScanSignature = signature;

        logInfo("向下扫描第 " + swipes + " 屏：发生位移=" + moved
                + "，连续无新增/无位移=" + noProgressSwipes
                + "，累计账号=" + discovered.size() + "/" + expectedAccountCount);

        if (expectedAccountCount > 0 && discovered.size() >= expectedAccountCount) {
            finish("账号数量已核对完成");
            return;
        }

        if (!moved && noProgressSwipes > 0
                && scrollForwardFallbacks < MAX_SCROLL_ACTION_FALLBACKS) {
            boolean scrolled = performScrollAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, snapshot);
            if (scrolled) {
                scrollForwardFallbacks++;
                logInfo("备用 ACTION_SCROLL_FORWARD 已接受，次数=" + scrollForwardFallbacks);
                after(SETTLE_MS, () -> sweepAccounts(swipes + 1));
                return;
            }
        }

        if (swipes >= MAX_ACCOUNT_SWIPES || (swipes >= 4 && noProgressSwipes >= 3)) {
            sweepAccountsReverse(0);
            return;
        }

        // active-jisou discovery sweep: swipe only inside the account list,
        // starting above Add Account and ending in the upper visible account rows.
        float bottom = Math.min(screenH * .67f, snapshot.addTop - screenH * .045f);
        float top = Math.max(screenH * .30f, bottom - screenH * .23f);
        if (bottom - top < screenH * .09f) {
            logWarn("账号列表可用滑动距离不足，转入反向核对；bottom=" + (int)bottom
                    + "，top=" + (int)top);
            sweepAccountsReverse(0);
            return;
        }
        boolean accepted = swipe(screenW * .44f, bottom, screenW * .44f, top);
        logInfo("账号列表向下翻屏：(" + (int)(screenW * .44f) + "," + (int)bottom
                + ")->(" + (int)(screenW * .44f) + "," + (int)top
                + ")，dispatchAccepted=" + accepted);
        if (!accepted) {
            boolean scrolled = performScrollAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, snapshot);
            if (!scrolled) {
                logWarn("手势与 ACTION_SCROLL_FORWARD 均未接受，停止向下扫描");
                sweepAccountsReverse(0);
                return;
            }
        }
        after(SETTLE_MS, () -> sweepAccounts(swipes + 1));
    }

    private void sweepAccountsReverse(int reverseSwipes) {
        if (!running || finished) return;
        Snapshot snapshot = readSnapshot();
        if (snapshot == null) {
            finish("扫描结束，列表不可读取");
            return;
        }
        if (expectedAccountCount > 0 && discovered.size() >= expectedAccountCount) {
            finish("账号数量已核对完成");
            return;
        }
        if (reverseSwipes >= MAX_REVERSE_SWIPES) {
            finish(expectedAccountCount > discovered.size()
                    ? "Turrit 显示 " + expectedAccountCount + " 个，当前只读取到 "
                        + discovered.size() + " 个；已保存已识别账号，未清除旧账号"
                    : "有限次数双向扫描已结束");
            return;
        }

        float start = snapshot.lastRowCenterY > 0
                ? Math.max(screenH * .27f, Math.min(screenH * .52f, snapshot.lastRowCenterY))
                : screenH * .34f;
        float end = Math.min(screenH * .79f, start + screenH * .34f);
        boolean accepted = end - start >= screenH * .16f
                && swipe(screenW * .45f, start, screenW * .45f, end);
        logInfo("向上反向核对 " + (reverseSwipes + 1) + "/" + MAX_REVERSE_SWIPES
                + "：(" + (int)(screenW * .45f) + "," + (int)start + ")->("
                + (int)(screenW * .45f) + "," + (int)end + ")，dispatchAccepted=" + accepted);
        if (!accepted) {
            boolean scrolled = scrollBackwardFallbacks < MAX_SCROLL_ACTION_FALLBACKS
                    && performScrollAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, snapshot);
            if (scrolled) scrollBackwardFallbacks++;
            else {
                finish("列表已停止滚动，已保留本次识别结果");
                return;
            }
        }
        after(SETTLE_MS, () -> sweepAccountsReverse(reverseSwipes + 1));
    }

    /** Same GestureDescription call and 350ms stroke used by active-jisou. */
    private boolean swipe(float x1, float y1, float x2, float y2) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !targetPackage.contentEquals(root.getPackageName())) {
            logWarn("拒绝滑动：当前活动窗口不是目标 Turrit");
            return false;
        }
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS);
        return service.dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    /** Fallback for Turrit builds that expose a scrollable node but ignore injected drags. */
    private boolean performScrollAction(int action, Snapshot snapshot) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !targetPackage.contentEquals(root.getPackageName())) return false;
        AccessibilityNodeInfo candidate = findBestScrollable(root, snapshot);
        if (candidate == null) {
            logWarn("账号区域未找到可滚动无障碍节点");
            return false;
        }
        Rect bounds = new Rect();
        candidate.getBoundsInScreen(bounds);
        boolean accepted = candidate.performAction(action);
        logInfo("滚动节点备用动作：action=" + action + "，class=" + candidate.getClassName()
                + "，bounds=" + bounds + "，accepted=" + accepted);
        return accepted;
    }

    private AccessibilityNodeInfo findBestScrollable(AccessibilityNodeInfo root, Snapshot snapshot) {
        List<NodeCandidate> candidates = new ArrayList<>();
        collectScrollable(root, snapshot, candidates);
        if (candidates.isEmpty()) return null;
        NodeCandidate best = candidates.get(0);
        for (NodeCandidate candidate : candidates) {
            if (candidate.area > best.area) best = candidate;
        }
        return best.node;
    }

    private void collectScrollable(AccessibilityNodeInfo node, Snapshot snapshot,
                                   List<NodeCandidate> output) {
        if (node == null) return;
        if (node.isVisibleToUser() && node.isScrollable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            Rect accountArea = new Rect(0, Math.max(0, snapshot.listTop),
                    screenW, Math.min(screenH, snapshot.addTop));
            Rect intersection = new Rect();
            if (bounds.width() > screenW * .40f && bounds.height() > screenH * .12f
                    && intersection.setIntersect(bounds, accountArea)) {
                long area = (long) intersection.width() * intersection.height();
                output.add(new NodeCandidate(node, area));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectScrollable(node.getChild(i), snapshot, output);
        }
    }

    private static final class NodeCandidate {
        final AccessibilityNodeInfo node;
        final long area;
        NodeCandidate(AccessibilityNodeInfo node, long area) {
            this.node = node;
            this.area = area;
        }
    }

    private void finish(String detail) {
        if (finished) return;
        finished = true;
        running = false;
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
        logInfo("扫描结束：累计=" + discovered.size() + "/" + expectedAccountCount
                + "，原因=" + detail);
        try {
            if (completion != null) {
                completion.done(new ArrayList<>(discovered), expectedAccountCount, detail);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Completion callback failed", error);
            DiagnosticLog.error(service, "账号识别/完成回调", "扫描结果回调失败", error);
        }
    }

    // ---------------------------------------------------------------------
    // Direct parser port from 活跃jisou: findAddAccountNode recursively checks
    // every node (not just text leaves), then accountSnapshot reads only rows
    // between the profile header and Add Account boundary.
    // ---------------------------------------------------------------------

    public static Snapshot readCurrentSnapshot(
            AccessibilityService service,
            String targetPackage,
            int screenW,
            int screenH
    ) {
        if (service == null) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        Snapshot snapshot = parseSnapshot(root, targetPackage, screenW, screenH);
        if (snapshot == null) {
            DiagnosticLog.warn(service, "账号识别/Java节点解析",
                    "首轮列表边界未通过；" + describeParseFailure(root, targetPackage, screenH));
        } else {
            DiagnosticLog.info(service, "账号识别/Java节点解析",
                    "首轮列表边界通过；可见行=" + snapshot.visibleRowCount
                            + "，候选名称=" + snapshot.names.size()
                            + "，总数=" + snapshot.total
                            + "，addTop=" + snapshot.addTop);
        }
        return snapshot;
    }

    public static boolean hasProfileHeader(
            AccessibilityNodeInfo root, int screenW, int screenH
    ) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectTextLeaves(root, leaves);
        return profileHeaderLabel(leaves, screenW, screenH) != null;
    }

    public static String profileHeaderName(
            AccessibilityNodeInfo root, int screenW, int screenH
    ) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectTextLeaves(root, leaves);
        return profileHeaderLabel(leaves, screenW, screenH);
    }

    private static Snapshot parseSnapshot(
            AccessibilityNodeInfo root,
            String targetPackage,
            int screenW,
            int screenH
    ) {
        if (root == null || root.getPackageName() == null
                || !targetPackage.contentEquals(root.getPackageName())
                || screenW <= 0 || screenH <= 0) return null;

        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectTextLeaves(root, leaves);
        // This is the crucial active-jisou implementation: recursively inspect
        // all nodes, including non-leaf containers, for the Add Account boundary.
        AccessibilityNodeInfo add = findAddAccountNode(root, screenH);
        AccessibilityNodeInfo counterNode = null;
        int total = -1;
        for (AccessibilityNodeInfo node : leaves) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            Matcher count = ACCOUNT_COUNTER.matcher(nodeText(node).trim());
            if (count.matches() && bounds.centerX() > screenW * .70f
                    && bounds.centerY() > screenH * .25f
                    && bounds.centerY() < screenH * .83f) {
                total = Math.min(10, Integer.parseInt(count.group(1)));
                counterNode = node;
                break;
            }
        }
        if (add == null && counterNode == null) return null;

        Rect boundary = new Rect();
        (add != null ? add : counterNode).getBoundsInScreen(boundary);
        int addTop = boundary.top;
        String header = profileHeaderLabel(leaves, screenW, screenH);
        int listTop = Math.round(screenH * .085f);
        for (AccessibilityNodeInfo node : leaves) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String value = nodeText(node).trim();
            if (value.matches("@[A-Za-z0-9_]{3,32}") && bounds.centerY() < screenH * .34f) {
                listTop = Math.max(listTop, bounds.bottom + Math.round(screenH * .025f));
            }
        }

        Set<String> names = new LinkedHashSet<>();
        if (header != null) names.add(normalize(header));
        List<String> visibleRows = new ArrayList<>();
        int lastRowCenterY = -1;
        for (AccessibilityNodeInfo node : leaves) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String value = nodeText(node).trim();
            if (bounds.centerY() <= listTop
                    || bounds.centerY() >= addTop - screenH * .010f) continue;
            if (bounds.centerX() < screenW * .13f || bounds.centerX() > screenW * .80f) continue;
            if (validAccountName(value)) {
                String normalized = normalize(value);
                names.add(normalized);
                visibleRows.add(normalized + "@" + Math.round(bounds.centerY() / 18f));
                lastRowCenterY = Math.max(lastRowCenterY, bounds.centerY());
            }
        }
        return new Snapshot(new ArrayList<>(names), total, listTop, addTop,
                lastRowCenterY, visibleRows.size(), visibleRows.toString() + "|" + addTop, true);
    }

    private static String describeParseFailure(
            AccessibilityNodeInfo root, String targetPackage, int screenH
    ) {
        if (root == null) return "rootInActiveWindow=null";
        String actualPackage = safe(root.getPackageName());
        if (!targetPackage.equals(actualPackage)) {
            return "活动窗口包名不匹配；actualPackage=" + actualPackage;
        }
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectTextLeaves(root, leaves);
        AccessibilityNodeInfo add = findAddAccountNode(root, screenH);
        boolean counterFound = false;
        for (AccessibilityNodeInfo node : leaves) {
            Matcher matcher = ACCOUNT_COUNTER.matcher(nodeText(node).trim());
            if (matcher.matches()) {
                counterFound = true;
                break;
            }
        }
        String addClass = add == null ? "none" : safe(add.getClassName());
        Rect addBounds = new Rect();
        if (add != null) add.getBoundsInScreen(addBounds);
        return "textLeafCount=" + leaves.size()
                + "，addAccountNode=" + (add != null)
                + "，addNodeClass=" + addClass
                + "，addNodeBounds=" + addBounds
                + "，counterNode=" + counterFound;
    }

    private static AccessibilityNodeInfo findAddAccountNode(
            AccessibilityNodeInfo root, int screenH
    ) {
        AccessibilityNodeInfo node = findTextRec(root, "添加账号", .06f, .82f, screenH, true);
        if (node == null) node = findTextRec(root, "Add Account", .06f, .82f, screenH, true);
        return node;
    }

    private static AccessibilityNodeInfo findTextRec(
            AccessibilityNodeInfo root, String target,
            float minY, float maxY, int screenH, boolean exact
    ) {
        if (root == null || TextUtils.isEmpty(target)) return null;
        String content = readText(root);
        String description = safe(root.getContentDescription());
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        boolean yOk = bounds.centerY() >= screenH * minY && bounds.centerY() <= screenH * maxY;
        boolean match = exact
                ? target.equals(content) || target.equals(description)
                : content.contains(target) || description.contains(target);
        if (yOk && match && root.isVisibleToUser()) return root;
        for (int index = 0; index < root.getChildCount(); index++) {
            AccessibilityNodeInfo found = findTextRec(
                    root.getChild(index), target, minY, maxY, screenH, exact);
            if (found != null) return found;
        }
        return null;
    }

    private static String profileHeaderLabel(
            List<AccessibilityNodeInfo> leaves, int screenW, int screenH
    ) {
        AccessibilityNodeInfo username = null;
        for (AccessibilityNodeInfo node : leaves) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String value = nodeText(node).trim();
            if (value.matches("@[A-Za-z0-9_]{3,32}")
                    && bounds.centerY() > screenH * .10f
                    && bounds.centerY() < screenH * .34f
                    && bounds.centerX() < screenW * .79f) {
                username = node;
                break;
            }
        }
        if (username != null) {
            Rect usernameBounds = new Rect();
            username.getBoundsInScreen(usernameBounds);
            String selected = null;
            int bestDistance = Integer.MAX_VALUE;
            for (AccessibilityNodeInfo node : leaves) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                String value = nodeText(node).trim();
                int distance = usernameBounds.centerY() - bounds.centerY();
                if (distance > 0 && distance < screenH * .10f && distance < bestDistance
                        && bounds.centerX() > screenW * .18f
                        && bounds.centerX() < screenW * .75f
                        && validAccountName(value)) {
                    selected = value;
                    bestDistance = distance;
                }
            }
            if (selected != null) return normalize(selected);
        }

        String name = null;
        float best = -Float.MAX_VALUE;
        for (AccessibilityNodeInfo node : leaves) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String value = nodeText(node).trim();
            if (!validAccountName(value)) continue;
            if (bounds.left < screenW * .265f || bounds.centerX() > screenW * .83f
                    || bounds.centerY() < screenH * .135f
                    || bounds.centerY() > screenH * .295f
                    || bounds.height() < screenH * .031f) continue;
            String className = safe(node.getClassName());
            if (!className.contains("TextView") && node.getChildCount() != 0) continue;
            float score = bounds.height() * 4f - Math.abs(bounds.centerY() - screenH * .20f);
            if (score > best) {
                best = score;
                name = value;
            }
        }
        return name == null ? null : normalize(name);
    }

    private static void collectTextLeaves(
            AccessibilityNodeInfo node, List<AccessibilityNodeInfo> output
    ) {
        if (node == null) return;
        String className = safe(node.getClassName());
        if (node.isVisibleToUser() && !nodeText(node).trim().isEmpty()
                && (node.getChildCount() == 0 || className.contains("TextView"))) {
            output.add(node);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectTextLeaves(node.getChild(index), output);
        }
    }

    private static String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        String text = readText(node);
        if (!text.trim().isEmpty()) return text;
        return safe(node.getContentDescription());
    }

    private static boolean validAccountName(String value) {
        if (value == null || value.isEmpty() || value.length() > 40
                || value.contains("\n") || value.startsWith("@")) return false;
        if (value.matches("\\d+\\s*/\\s*\\d+") || value.contains("添加账号")) return false;
        switch (value) {
            case "我的": case "Me": case "添加账号": case "Add Account":
            case "聊天": case "联系人": case "发现": case "下载": case "缓存":
            case "实用工具": case "收藏夹": case "设置": case "客服":
                return false;
            default:
                return true;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String readText(AccessibilityNodeInfo node) {
        return node == null ? "" : safe(node.getText());
    }

    private void logInfo(String message) {
        Log.i(TAG, message);
        DiagnosticLog.info(service, "账号识别/Java扫描器", message);
    }

    private void logWarn(String message) {
        Log.w(TAG, message);
        DiagnosticLog.warn(service, "账号识别/Java扫描器", message);
    }
}
