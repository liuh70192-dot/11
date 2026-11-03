package com.example.turritrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 不依赖第三方 UI SDK，适配 MuMu 1080×1920（约360×640dp）纵屏。 */
public class MainActivity extends Activity {
    public static final String EXTRA_RETURN_TAB = "com.example.turritrunner.EXTRA_RETURN_TAB";
    private static final int SELECT_TXT = 801;
    private static final int NAVY = Color.rgb(18, 37, 67);
    private static final int BLUE = Color.rgb(46, 110, 232);
    private static final int BACKGROUND = Color.rgb(246, 248, 252);
    private static final int MUTED = Color.rgb(110, 123, 146);
    private static final int BORDER = Color.rgb(225, 231, 240);
    private static final int GREEN = Color.rgb(27, 145, 96);

    private final Handler h = new Handler(Looper.getMainLooper());
    private Store store;
    private LinearLayout root, body, menu;
    private TextView headline;
    private EditText botInput, titleInput, dailyInput;
    private Spinner countMode;
    private int page = 0;
    private boolean visible;
    private String pendingDetectedDetail;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (headline != null) headline.setText(buildStatusText());
            h.postDelayed(this, 2500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = Store.get(this);
        applyReturnTab(getIntent());
        if (store.isRunning() && !SchedulerService.alive) {
            store.setRunning(false);
            store.interruptJobs();
        }
        buildShell();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyReturnTab(intent);
        if (visible) { render(); h.post(this::showPendingDetectedAccounts); }
    }
    private void applyReturnTab(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_RETURN_TAB)) {
            int tab = intent.getIntExtra(EXTRA_RETURN_TAB, -1);
            if (tab >= 0 && tab <= 4) page = tab;
            intent.removeExtra(EXTRA_RETURN_TAB);
        }
    }
    @Override protected void onResume() {
        super.onResume(); visible = true;
        render(); h.removeCallbacks(heartbeat); h.post(heartbeat);
        h.post(this::showPendingDetectedAccounts);
    }
    @Override protected void onPause() {
        visible = false;
        h.removeCallbacks(heartbeat);
        super.onPause();
    }

    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private GradientDrawable bg(int color, float radius, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (border != 0) d.setStroke(dp(1), border);
        return d;
    }
    private TextView text(String content, float sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(content); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }
    private void add(LinearLayout parent, View child, int width, int height) {
        parent.addView(child, new LinearLayout.LayoutParams(width == -1 ? -1 : dp(width),
                height == -2 ? -2 : dp(height)));
    }
    private void gap(LinearLayout parent, int d) { add(parent, new View(this), -1, d); }
    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(label); b.setTextSize(12.5f);
        b.setTextColor(color == BLUE ? Color.WHITE : NAVY);
        b.setBackground(bg(color, 11, 0));
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        return b;
    }
    private Button primary(String label) { return button(label, BLUE); }
    private Button neutral(String label) { return button(label, Color.rgb(232, 238, 248)); }
    private void weighted(LinearLayout row, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        row.addView(child, p);
    }
    private LinearLayout card(String title) {
        LinearLayout l = column();
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(bg(Color.WHITE, 15, BORDER));
        if (title != null) {
            add(l, text(title, 15.5f, NAVY, true), -1, -2);
            gap(l, 12);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        body.addView(l, p);
        return l;
    }
    private EditText input(String hint, String current, int inputType) {
        EditText e = new EditText(this);
        e.setSingleLine(true); e.setTextSize(14.5f); e.setTextColor(NAVY);
        e.setHintTextColor(MUTED); e.setHint(hint); e.setText(current);
        e.setPadding(dp(11), 0, dp(11), 0); e.setInputType(inputType);
        e.setBackground(bg(Color.rgb(248, 250, 253), 9, BORDER));
        e.setMinHeight(0); e.setMinimumHeight(0);
        return e;
    }
    private void label(LinearLayout box, String text) {
        add(box, text(text, 12, MUTED, false), -1, -2);
        gap(box, 5);
    }
    private void buildShell() {
        root = column(); root.setBackgroundColor(BACKGROUND);
        LinearLayout banner = column();
        banner.setPadding(dp(18), dp(12), dp(18), dp(14)); banner.setBackgroundColor(NAVY);
        add(banner, text("Turrit 任务助手", 21f, Color.WHITE, true), -1, -2);
        gap(banner, 6);
        headline = text(buildStatusText(), 14f, Color.rgb(222, 232, 247), true);
        add(banner, headline, -1, -2);
        add(root, banner, -1, -2);

        menu = row(); menu.setPadding(dp(5), dp(9), dp(5), dp(9));
        menu.setBackgroundColor(Color.WHITE); add(root, menu, -1, -2);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column(); body.setPadding(dp(12), dp(14), dp(12), dp(30));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root); render();
    }
    private String buildStatusText() {
        boolean running = store != null && store.isRunning() && SchedulerService.alive;
        int done = store == null ? 0 : store.countStatus("完成");
        int fail = store == null ? 0 : store.countStatus("失败");
        return (running ? "● 运行中" : "○ 已停止") + "   成功 " + done + "   失败 " + fail;
    }
    private void render() {
        if (body == null) return;
        menu.removeAllViews();
        String[] tabs = {"总览", "账号", "消息库", "任务", "日志"};
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            TextView tab = text(tabs[i], 12, i == page ? Color.WHITE : MUTED, i == page);
            tab.setGravity(Gravity.CENTER);
            tab.setBackground(bg(i == page ? BLUE : Color.WHITE, 10, 0));
            tab.setOnClickListener(v -> { page = selected; render(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(36), 1f);
            p.setMargins(dp(1), 0, dp(1), 0); menu.addView(tab, p);
        }
        body.removeAllViews();
        if (page == 0) overview();
        else if (page == 1) accountPage();
        else if (page == 2) messagePage();
        else if (page == 3) taskPage();
        else logPage();
        headline.setText(buildStatusText());
    }

    private void overview() {
        LinearLayout top = card("运行控制");
        LinearLayout actions = row();
        Button go = primary("▶ 开始任务"); Button stop = neutral("■ 停止任务");
        weighted(actions, go); weighted(actions, stop);
        go.setOnClickListener(v -> startSchedule());
        stop.setOnClickListener(v -> stopSchedule());
        add(top, actions, -1, -2);
        gap(top, 10);
        Button access = neutral(TurritAccessibilityService.current() == null ?
                "开启无障碍权限" : "无障碍已连接 ✓");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        add(top, access, -1, 41);

        LinearLayout stats = card("任务概况");
        List<Store.Account> accounts = store.accounts();
        int enabled = 0; for (Store.Account a : accounts) if (a.enabled) enabled++;
        add(stats, text("已添加账号 " + accounts.size() + "  ·  启用 " + enabled +
                "  ·  消息 " + store.messages().size() + " 条", 14, NAVY, false), -1, -2);
        gap(stats, 11);
        Store.Job next = store.nextWaiting();
        add(stats, text("下次：" + (next == null ? "启动后自动安排" :
                Store.fmt(next.due) + " · " + next.label), 12.5f, BLUE, true), -1, -2);

        LinearLayout listing = card("下一批任务");
        int rows = 0;
        for (Store.Job job : store.jobs()) {
            if (!"等待".equals(job.state)) continue;
            add(listing, text(Store.fmt(job.due) + "     " + job.label, 12, NAVY, false), -1, -2);
            gap(listing, 6);
            if (++rows >= 12) break;
        }
        if (rows == 0) add(listing, text("暂无待执行任务", 12, MUTED, false), -1, -2);
    }
    private void startSchedule() {
        if (store.isRunning() && SchedulerService.alive) { toast("任务已经在运行"); return; }
        if (TurritAccessibilityService.current() == null) {
            toast("请先开启无障碍服务");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
        }
        if (store.bot().trim().isEmpty() || store.expectedTitle().trim().isEmpty()) {
            toast("先到「任务」填写机器人用户名和顶部名称关键词"); page = 3; render(); return;
        }
        if (store.messages().isEmpty()) { toast("先到消息库导入 TXT 文件"); page = 2; render(); return; }
        int enabled = 0;
        for (Store.Account a : store.accounts()) if (a.enabled) enabled++;
        if (enabled == 0) { toast("先到「账号」识别并启用账号"); page = 1; render(); return; }
        new AlertDialog.Builder(this).setTitle("开始执行随机任务？")
            .setMessage("将对 " + enabled + " 个账号安排未来 24 小时的任务，开始后会自动切换 Turrit 并发送文字。\n\n请先用账号「立即测试（不发送）」验证页面识别。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始", (d, w) -> {
                try {
                    Intent intent = new Intent(this, SchedulerService.class).setAction(SchedulerService.START);
                    startForegroundService(intent);
                    h.postDelayed(this::render, 700);
                } catch (Exception e) { toast("启动失败：" + e.getMessage()); }
            }).show();
    }
    private void stopSchedule() {
        if (!SchedulerService.alive && !store.isRunning()) { toast("当前没有运行任务"); return; }
        new AlertDialog.Builder(this).setTitle("停止所有后续任务？")
            .setMessage("当前自动操作也将被取消。再次启动会重新生成随机时间。")
            .setNegativeButton("取消", null)
            .setPositiveButton("停止", (d, w) -> {
                Intent i = new Intent(this, SchedulerService.class).setAction(SchedulerService.STOP);
                startService(i);
                h.postDelayed(this::render, 500);
            }).show();
    }

    private void accountPage() {
        LinearLayout actions = card("账号管理");
        LinearLayout bar = row();
        Button auto = primary("识别 Turrit 账号"); Button manual = neutral("手动添加");
        weighted(bar, auto); weighted(bar, manual); add(actions, bar, -1, -2);
        auto.setOnClickListener(v -> detectAccounts());
        manual.setOnClickListener(v -> manualAccount());
        List<Store.Account> accounts = store.accounts();
        LinearLayout list = card("已保存账号（" + accounts.size() + "）");
        if (accounts.isEmpty()) {
            add(list, text("暂无账号，先点击「识别 Turrit 账号」。", 13, MUTED, false), -1, -2);
            return;
        }
        for (Store.Account a : accounts) {
            LinearLayout one = row();
            CheckBox enabled = new CheckBox(this);
            enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
            enabled.setChecked(a.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                a.enabled = checked; store.saveAccounts(accounts);
            });
            add(one, enabled, 38, 42);
            TextView name = text(a.display(), 13, NAVY, true);
            one.addView(name, new LinearLayout.LayoutParams(0, dp(43), 1f));
            Button edit = neutral("编辑"); Button test = primary("测试");
            add(one, edit, 48, 36); add(one, test, 48, 36);
            edit.setOnClickListener(v -> editAccount(a));
            test.setOnClickListener(v -> testAccount(a));
            add(list, one, -1, -2); gap(list, 5);
        }
    }
    private void detectAccounts() {
        TurritAccessibilityService s = TurritAccessibilityService.current();
        if (s == null) { toast("请先开启无障碍服务"); return; }
        toast("正在扫描，完成后自动保存");
        s.discover((names, detail) -> runOnUiThread(() -> {
            // 保存已经由无障碍服务完成；前台只展示结果，不需要再点「保存」。
            pendingDetectedDetail = detail;
            if (visible) showPendingDetectedAccounts();
        }));
    }
    private void showPendingDetectedAccounts() {
        if (!visible || pendingDetectedDetail == null) return;
        String detail = pendingDetectedDetail;
        pendingDetectedDetail = null;
        render();
        toast(detail);
    }
    private void manualAccount() {
        EditText name = input("输入 Turrit 账号列表显示的准确名称", "", InputType.TYPE_CLASS_TEXT);
        LinearLayout content = column(); content.setPadding(dp(20), dp(10), dp(20), 0);
        add(content, name, -1, 44);
        new AlertDialog.Builder(this).setTitle("添加账号").setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (d,w) -> {
                String label = name.getText().toString().trim();
                if (label.isEmpty()) return;
                store.mergeFoundAccounts(java.util.Collections.singletonList(label)); render();
            }).show();
    }
    private void editAccount(Store.Account a) {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(10), dp(20), 0);
        label(panel, "Turrit 中的真实显示名（改名后自动匹配可能失效）");
        EditText name = input("真实显示名", a.label, InputType.TYPE_CLASS_TEXT);
        add(panel, name, -1, 44); gap(panel, 10);
        label(panel, "仅本地显示的备注名");
        EditText alias = input("备注（可留空）", a.alias, InputType.TYPE_CLASS_TEXT);
        add(panel, alias, -1, 44);
        new AlertDialog.Builder(this).setTitle("编辑账号").setView(panel)
            .setNeutralButton("删除账号", (d, w) -> {
                List<Store.Account> all = store.accounts();
                for (int i = all.size() - 1; i >= 0; i--)
                    if (all.get(i).label.equals(a.label)) all.remove(i);
                store.saveAccounts(all); render();
            }).setNegativeButton("取消", null)
            .setPositiveButton("保存", (d,w) -> {
                List<Store.Account> all = store.accounts();
                for (Store.Account item : all) if (item.label.equals(a.label)) {
                    String proposed = name.getText().toString().trim();
                    if (!proposed.isEmpty()) item.label = proposed;
                    item.alias = alias.getText().toString().trim();
                    break;
                }
                store.saveAccounts(all); render();
            }).show();
    }
    private void testAccount(Store.Account a) {
        TurritAccessibilityService s = TurritAccessibilityService.current();
        if (s == null) { toast("先开启无障碍服务"); return; }
        if (TextUtils.isEmpty(store.bot()) || TextUtils.isEmpty(store.expectedTitle())) {
            toast("请先在任务页保存机器人用户名和顶部名称关键词"); return;
        }
        toast("开始检查账号 " + a.label + "（不发送）");
        s.run(a.label, store.bot(), store.expectedTitle(), new ArrayList<>(), true,
            (ok, info) -> runOnUiThread(() -> toast((ok ? "测试通过：" : "测试未通过：") + info)));
    }
    private void messagePage() {
        LinearLayout card = card("共用文字消息库");
        // 直接输入一个搜索关键词，就能加入共用消息池；不必每次做 TXT 文件。
        LinearLayout keywordRow = row();
        EditText keyword = input("输入关键词，例如：新闻", "", InputType.TYPE_CLASS_TEXT);
        keywordRow.addView(keyword, new LinearLayout.LayoutParams(0, dp(43), 1f));
        Button addKeyword = primary("添加");
        LinearLayout.LayoutParams addParam = new LinearLayout.LayoutParams(dp(62), dp(43));
        addParam.setMargins(dp(8), 0, 0, 0);
        keywordRow.addView(addKeyword, addParam);
        add(card, keywordRow, -1, -2);
        gap(card, 12);
        addKeyword.setOnClickListener(v -> {
            String value = keyword.getText().toString().trim();
            if (value.isEmpty()) { toast("请先输入关键词"); return; }
            if (!store.addKeyword(value)) {
                toast("关键词重复、过长或消息库已满，未添加"); return;
            }
            store.log("手工添加关键词到消息池");
            toast("已加入共用消息库");
            render();
        });
        LinearLayout buttons = row();
        Button choose = primary("导入 TXT 文件"); Button clear = neutral("清空消息库");
        weighted(buttons, choose); weighted(buttons, clear);
        add(card, buttons, -1, -2);
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/octet-stream"});
            startActivityForResult(intent, SELECT_TXT);
        });
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("清空消息库？").setMessage("删除所有已导入的文字，无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空", (d,w) -> { store.replaceMessages(""); render(); })
            .show());
        List<String> items = store.messages();
        LinearLayout preview = card("消息预览（共 " + items.size() + " 条）");
        for (int i = 0; i < Math.min(15, items.size()); i++) {
            String s = items.get(i);
            if (s.length() > 80) s = s.substring(0, 80) + "…";
            add(preview, text((i + 1) + ". " + s, 12.5f, NAVY, false), -1, -2);
            gap(preview, 8);
        }
        if (items.isEmpty()) add(preview, text("未导入文件", 12, MUTED, false), -1, -2);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SELECT_TXT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData(); if (uri == null) return;
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                StringBuilder str = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null && str.length() < 4_000_000) {
                    str.append(line).append('\n');
                }
                int count = store.replaceMessages(str.toString());
                store.log("已导入 TXT：" + count + " 条");
                runOnUiThread(() -> { toast("成功导入 " + count + " 条消息"); render(); });
            } catch (Exception e) { runOnUiThread(() -> toast("TXT 导入失败：" + e.getMessage())); }
        }).start();
    }
    private void taskPage() {
        LinearLayout box = card("任务设置");
        label(box, "机器人用户名（必须是 @用户名，不是显示名称）");
        botInput = input("例如 @example_bot", store.bot(), InputType.TYPE_CLASS_TEXT);
        add(box, botInput, -1, 44); gap(box, 12);
        label(box, "机器人聊天顶部名称关键词（防止发错聊天，必填）");
        titleInput = input("例如 极搜", store.expectedTitle(), InputType.TYPE_CLASS_TEXT);
        add(box, titleInput, -1, 44); gap(box, 12);
        label(box, "每个启用账号，每 24 小时执行次数");
        dailyInput = input("例如 5", String.valueOf(store.perAccountDaily()),
            InputType.TYPE_CLASS_NUMBER);
        add(box, dailyInput, -1, 44); gap(box, 12);
        label(box, "每次任务发送多少条文字");
        countMode = new Spinner(this);
        String[] choices = {"随机 1～2 条", "固定 1 条", "固定 2 条"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countMode.setAdapter(adapter);
        int saved = store.countMode();
        countMode.setSelection(saved == 0 ? 0 : saved == 1 ? 1 : 2);
        countMode.setBackground(bg(Color.rgb(248, 250, 253), 9, BORDER));
        add(box, countMode, -1, 44); gap(box, 14);
        Button save = primary("保存任务设置"); add(box, save, -1, 43);
        save.setOnClickListener(v -> saveTasks());

    }
    private void saveTasks() {
        String bot = botInput.getText().toString().trim();
        String title = titleInput.getText().toString().trim();
        int count;
        try { count = Integer.parseInt(dailyInput.getText().toString().trim()); }
        catch (Exception e) { toast("每日执行次数必须是数字"); return; }
        if (!bot.matches("@?[A-Za-z0-9_]{5,32}")) { toast("机器人用户名格式不正确"); return; }
        if (title.isEmpty()) { toast("请填写机器人聊天顶部名称关键词"); return; }
        if (count < 1 || count > 100) { toast("每日执行次数范围是 1～100"); return; }
        store.saveConfig(bot.startsWith("@") ? bot : "@" + bot, title,
                count, countMode.getSelectedItemPosition());
        toast("设置已保存"); render();
    }
    private void logPage() {
        LinearLayout l = card("最近执行日志");
        Button clear = neutral("清空日志");
        add(l, clear, -1, 40);
        clear.setOnClickListener(v -> {
            store.clearLogs(); render();
        });
        gap(l, 12);
        int i = 0;
        for (String text : store.logs()) {
            add(l, text(text, 12, text.contains("失败") ? Color.rgb(180, 65, 65) : NAVY, false), -1, -2);
            gap(l, 9);
            if (++i >= 50) break;
        }
        if (i == 0) add(l, text("暂无日志", 12, MUTED, false), -1, -2);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
