package com.example.turritrunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/** 前台服务：只响应用户明确点击的开始/停止操作；系统重启后不会自动发送。 */
public class SchedulerService extends Service {
    public static final String START = "com.example.turritrunner.START";
    public static final String STOP = "com.example.turritrunner.STOP";
    private static final String CHANNEL = "schedule_run";
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean processing;
    private String activeJobId;
    private PowerManager.WakeLock wakeLock;
    public static boolean alive;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { tick(); }
            catch (Exception e) { Store.get(SchedulerService.this).log("调度异常：" + e.getMessage()); }
            if (running) h.postDelayed(this, 4000);
        }
    };

    @Override public void onCreate() { super.onCreate(); alive = true; createChannel(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (STOP.equals(action)) { stopNow("用户手动停止"); return START_NOT_STICKY; }
        if (!START.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        startForeground(101, notification("任务运行中 · 等待下一次随机任务"));
        Store store = Store.get(this);
        if (store.bot().trim().isEmpty() || store.expectedTitle().trim().isEmpty()
                || store.messages().isEmpty() || store.accounts().isEmpty()) {
            store.log("启动失败：缺少机器人用户名、顶部名称关键词、消息或账号");
            stopNow("启动配置不完整");
            return START_NOT_STICKY;
        }
        int enabled = 0;
        for (Store.Account a : store.accounts()) if (a.enabled) enabled++;
        if (enabled == 0 || TurritAccessibilityService.current() == null) {
            store.log("启动失败：无启用账号或无障碍未连接");
            stopNow("请先启用账号与无障碍服务");
            return START_NOT_STICKY;
        }
        h.removeCallbacks(ticker);
        // 明确开始即重建一轮 24 小时的随机计划。已完成的旧任务不会被重发。
        store.interruptJobs();
        int count = store.planNextCycle(System.currentTimeMillis());
        store.setRunning(true);
        store.log("开始运行：" + enabled + " 个账号，未来24小时计划 " + count + " 次");
        running = true; processing = false; activeJobId = null;
        h.post(ticker);
        return START_NOT_STICKY;
    }
    private Notification notification(String state) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, SchedulerService.class).setAction(STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Turrit 任务助手")
            .setContentText(state)
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止任务", stop)
            .build();
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "任务调度",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示正在运行的 Turrit 随机定时任务");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
    private void updateNotification(String state) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(101, notification(state));
    }
    private void tick() {
        Store store = Store.get(this);
        if (!store.isRunning()) { stopNow("检测到用户停止状态"); return; }
        if (processing) return;
        long now = System.currentTimeMillis();
        long start = store.cycleStart();
        if (start <= 0 || now >= start + Store.DAY_MS) {
            int planned = store.planNextCycle(now);
            store.log("新一轮随机安排：" + planned + " 次");
        }
        Store.Job next = store.nextDue(now);
        if (next == null) return;
        TurritAccessibilityService automation = TurritAccessibilityService.current();
        if (automation == null) {
            store.changeJob(next.id, "失败", "无障碍连接已断开");
            store.log(next.label + "：跳过，未连接无障碍");
            return;
        }
        if (automation.isBusy()) return;
        processing = true;
        activeJobId = next.id;
        store.changeJob(next.id, "执行中", "");
        int count = Math.min(store.messagesPerJob(), store.messages().size());
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String value = store.nextMessage();
            if (value != null) texts.add(value);
        }
        if (texts.isEmpty()) {
            store.changeJob(next.id, "失败", "消息池为空");
            processing = false; activeJobId = null; return;
        }
        updateNotification("正在执行账号 " + next.label + " · " + texts.size() + " 条");
        holdWakeLock();
        automation.run(next.label, store.bot(), store.expectedTitle(), texts, false,
            (ok, detail) -> {
                releaseWakeLock();
                Store s = Store.get(SchedulerService.this);
                // 用户已点停止后，不覆盖手动停止记录，不开启下一任务。
                if (running && activeJobId != null) {
                    s.changeJob(next.id, ok ? "完成" : "失败", detail);
                }
                processing = false;
                activeJobId = null;
                if (running) updateNotification("任务运行中 · 等待下一次随机任务");
            });
    }
    private void holdWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurritMuMu:singleTask");
            wakeLock.acquire(4 * 60 * 1000L);
        } catch (Exception ignored) { }
    }
    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Exception ignored) { }
        wakeLock = null;
    }
    private void stopNow(String reason) {
        running = false;
        h.removeCallbacks(ticker);
        TurritAccessibilityService auto = TurritAccessibilityService.current();
        if (processing && auto != null) auto.cancel("任务已手动停止");
        releaseWakeLock();
        Store store = Store.get(this);
        store.setRunning(false);
        store.interruptJobs();
        store.log("已停止：" + reason);
        processing = false; activeJobId = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy() {
        running = false; alive = false;
        h.removeCallbacks(ticker);
        releaseWakeLock();
        Store store = Store.get(this);
        if (store.isRunning()) {
            // 被系统杀掉后再次打开 APK，也必须让用户手动点击开始。
            store.setRunning(false);
            store.interruptJobs();
            store.log("调度服务已退出；需要手动重新开始");
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
