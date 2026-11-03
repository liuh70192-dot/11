package com.example.turritrunner;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** 本地配置与运行状态；不采集手机号、Telegram 会话或登录凭据。 */
public final class Store {
    public static final String TURRIT_PKG = "org.telegram.group";
    public static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static Store instance;
    private final SharedPreferences pref;
    private final Random random = new Random();

    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }
    private Store(Context c) { pref = c.getSharedPreferences("turrit_tasks_v1", Context.MODE_PRIVATE); }

    public synchronized String bot() { return pref.getString("bot", ""); }
    public synchronized String expectedTitle() { return pref.getString("expectedTitle", ""); }
    public synchronized int perAccountDaily() { return pref.getInt("daily", 2); }
    public synchronized int countMode() { return pref.getInt("countMode", 0); } // 0: 随机1/2, 1:固定1, 2:固定2
    public synchronized boolean isRunning() { return pref.getBoolean("running", false); }
    public synchronized long cycleStart() { return pref.getLong("cycleStart", 0L); }

    public synchronized void saveConfig(String bot, String title, int daily, int mode) {
        pref.edit().putString("bot", bot.trim())
            .putString("expectedTitle", title.trim())
            .putInt("daily", Math.max(1, Math.min(100, daily)))
            .putInt("countMode", Math.max(0, Math.min(2, mode)))
            .commit();
    }
    public synchronized void setRunning(boolean running) { pref.edit().putBoolean("running", running).commit(); }

    private JSONArray array(String key) {
        try { return new JSONArray(pref.getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void putArray(String key, JSONArray v) { pref.edit().putString(key, v.toString()).commit(); }

    public synchronized List<String> messages() {
        List<String> out = new ArrayList<>();
        JSONArray arr = array("messages");
        for (int i = 0; i < arr.length(); i++) {
            String value = arr.optString(i, "").trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }
    public synchronized int replaceMessages(String txt) {
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        // 一行一条；自动去掉 UTF-8 BOM、空行和完全重复的条目。
        String[] lines = txt.replace("\uFEFF", "").replace("\r", "").split("\n");
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || !seen.add(value)) continue;
            if (value.length() > 4096) value = value.substring(0, 4096);
            arr.put(value);
            if (arr.length() >= 20000) break;
        }
        putArray("messages", arr);
        putArray("shuffle", new JSONArray());
        return arr.length();
    }
    public synchronized String nextMessage() {
        List<String> pool = messages();
        if (pool.isEmpty()) return null;
        JSONArray bag = array("shuffle");
        if (bag.length() == 0 || bag.length() > pool.size()) {
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) indexes.add(i);
            Collections.shuffle(indexes, random);
            bag = new JSONArray();
            for (int idx : indexes) bag.put(idx);
        }
        int idx = bag.optInt(bag.length() - 1, 0);
        JSONArray remain = new JSONArray();
        for (int i = 0; i < bag.length() - 1; i++) remain.put(bag.optInt(i));
        putArray("shuffle", remain);
        return pool.get(Math.min(Math.max(idx, 0), pool.size() - 1));
    }

    public static class Account {
        public String label;
        public String alias;
        public boolean enabled;
        public Account(String label, String alias, boolean enabled) {
            this.label = label; this.alias = alias; this.enabled = enabled;
        }
        public String display() {
            return TextUtils.isEmpty(alias) ? label : alias + "（" + label + "）";
        }
    }
    public synchronized List<Account> accounts() {
        List<Account> result = new ArrayList<>();
        JSONArray arr = array("accounts");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String label = obj.optString("label", "").trim();
            if (!label.isEmpty()) result.add(new Account(label,
                obj.optString("alias", ""), obj.optBoolean("enabled", true)));
        }
        return result;
    }
    public synchronized void saveAccounts(List<Account> accounts) {
        JSONArray arr = new JSONArray();
        for (Account a : accounts) {
            try {
                if (TextUtils.isEmpty(a.label)) continue;
                JSONObject item = new JSONObject();
                item.put("label", a.label.trim());
                item.put("alias", a.alias == null ? "" : a.alias.trim());
                item.put("enabled", a.enabled);
                arr.put(item);
            } catch (Exception ignored) { }
        }
        putArray("accounts", arr);
    }
    public synchronized int mergeFoundAccounts(List<String> found) {
        List<Account> accounts = accounts();
        Set<String> current = new HashSet<>();
        for (Account a : accounts) current.add(a.label);
        int newCount = 0;
        for (String label : found) {
            if (label == null) continue;
            label = label.trim();
            if (label.isEmpty() || !current.add(label)) continue;
            accounts.add(new Account(label, "", true));
            newCount++;
        }
        saveAccounts(accounts);
        return newCount;
    }

    public static class Job {
        public String id, label, state, info;
        public long due;
        public Job(String id, String label, long due, String state, String info) {
            this.id = id; this.label = label; this.due = due; this.state = state; this.info = info;
        }
        JSONObject json() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id); obj.put("label", label); obj.put("due", due);
                obj.put("state", state); obj.put("info", info);
            } catch (Exception ignored) { }
            return obj;
        }
    }
    public synchronized List<Job> jobs() {
        List<Job> jobs = new ArrayList<>();
        JSONArray arr = array("jobs");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            jobs.add(new Job(obj.optString("id", ""), obj.optString("label", ""),
                    obj.optLong("due", 0), obj.optString("state", "等待"),
                    obj.optString("info", "")));
        }
        return jobs;
    }
    private synchronized void saveJobs(List<Job> jobs) {
        JSONArray arr = new JSONArray();
        for (Job job : jobs) arr.put(job.json());
        putArray("jobs", arr);
    }
    public synchronized void changeJob(String id, String state, String info) {
        List<Job> jobs = jobs();
        for (Job job : jobs) {
            if (job.id.equals(id)) { job.state = state; job.info = info; break; }
        }
        saveJobs(jobs);
    }
    /** 从点击开始的时刻起计算24小时；每账号每天N次，按时间片均匀随机，避免全部挤在一起。 */
    public synchronized int planNextCycle(long start) {
        List<Account> accounts = accounts();
        List<Job> jobs = new ArrayList<>();
        int daily = perAccountDaily();
        for (Account a : accounts) {
            if (!a.enabled) continue;
            long slot = DAY_MS / daily;
            for (int i = 0; i < daily; i++) {
                long jitterMin = slot / 10;
                long jitterSpan = Math.max(1, slot * 8 / 10);
                long randomOffset = (long) (random.nextDouble() * jitterSpan);
                long due = start + i * slot + jitterMin + randomOffset;
                jobs.add(new Job(a.label + "-" + start + "-" + i, a.label, due, "等待", ""));
            }
        }
        Collections.sort(jobs, Comparator.comparingLong(j -> j.due));
        saveJobs(jobs);
        pref.edit().putLong("cycleStart", start).commit();
        return jobs.size();
    }
    public synchronized Job nextDue(long now) {
        for (Job job : jobs()) if ("等待".equals(job.state) && job.due <= now) return job;
        return null;
    }
    public synchronized Job nextWaiting() {
        for (Job job : jobs()) if ("等待".equals(job.state)) return job;
        return null;
    }
    public synchronized int countStatus(String state) {
        int n = 0;
        for (Job job : jobs()) if (state.equals(job.state)) n++;
        return n;
    }
    public synchronized void interruptJobs() {
        List<Job> jobs = jobs();
        for (Job j : jobs) if ("执行中".equals(j.state)) {
            j.state = "失败"; j.info = "任务被停止或服务异常中断";
        }
        saveJobs(jobs);
    }
    public synchronized void log(String text) {
        JSONArray current = array("logs");
        JSONArray out = new JSONArray();
        out.put(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date()) + "  " + text);
        for (int i = 0; i < Math.min(99, current.length()); i++) out.put(current.optString(i));
        putArray("logs", out);
    }
    public synchronized List<String> logs() {
        List<String> list = new ArrayList<>();
        JSONArray logs = array("logs");
        for (int i = 0; i < logs.length(); i++) list.add(logs.optString(i));
        return list;
    }
    public synchronized void clearLogs() { putArray("logs", new JSONArray()); }
    public synchronized int messagesPerJob() {
        int mode = countMode();
        return mode == 0 ? 1 + random.nextInt(2) : mode;
    }
    public static String fmt(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }
}
