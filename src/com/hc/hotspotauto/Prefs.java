package com.hc.hotspotauto;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 极简配置存储，无第三方依赖。 */
public final class Prefs {

    private static final String FILE = "hotspot";
    private static SharedPreferences sp;

    private Prefs() {
    }

    public static void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences s() {
        return sp;
    }

    /* ---------------- 触发设备（存 "MAC|名称"） ---------------- */

    public static Set<String> triggers() {
        return new HashSet<>(s().getStringSet("triggers", new HashSet<String>()));
    }

    public static void setTriggers(Set<String> v) {
        s().edit().putStringSet("triggers", new HashSet<>(v)).apply();
    }

    public static List<String> triggerMacs() {
        List<String> out = new ArrayList<>();
        for (String item : triggers()) {
            int i = item.indexOf('|');
            out.add(i >= 0 ? item.substring(0, i) : item);
        }
        return out;
    }

    public static boolean isTrigger(String mac) {
        if (mac == null) return false;
        for (String m : triggerMacs()) {
            if (m.equalsIgnoreCase(mac)) return true;
        }
        return false;
    }

    /* ---------------- 行为开关 ---------------- */

    public static boolean autoOn() {
        return s().getBoolean("autoOn", true);
    }

    public static void setAutoOn(boolean v) {
        s().edit().putBoolean("autoOn", v).apply();
    }

    public static boolean autoOff() {
        return s().getBoolean("autoOff", true);
    }

    public static void setAutoOff(boolean v) {
        s().edit().putBoolean("autoOff", v).apply();
    }

    /** 蓝牙断开后延迟多少秒再关热点（避免上下车抖动）。 */
    public static int offDelaySec() {
        return s().getInt("offDelay", 8);
    }

    public static void setOffDelaySec(int v) {
        s().edit().putInt("offDelay", v).apply();
    }

    /* ---------------- 磁贴关键词 ---------------- */

    public static String keywordsRaw() {
        return s().getString("keywords", "个人热点,热点,Hotspot,Mobile Hotspot,便携式热点");
    }

    public static void setKeywordsRaw(String v) {
        s().edit().putString("keywords", v).apply();
    }

    public static List<String> keywords() {
        List<String> out = new ArrayList<>();
        for (String part : keywordsRaw().split("[,，;；]")) {
            String k = part.trim();
            if (!k.isEmpty()) out.add(k);
        }
        if (out.isEmpty()) out.add("个人热点");
        return out;
    }

    /* ---------------- 上次设定的状态（读不到磁贴状态时兜底） ---------------- */

    public static void markState(boolean on) {
        s().edit().putBoolean("lastState", on)
                .putLong("lastStateTime", System.currentTimeMillis()).apply();
    }

    public static boolean lastState() {
        return s().getBoolean("lastState", false);
    }

    public static long lastStateTime() {
        return s().getLong("lastStateTime", 0L);
    }
}
