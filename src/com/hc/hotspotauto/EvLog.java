package com.hc.hotspotauto;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 落地事件日志。
 * 车里的蓝牙事件在电脑上没法复现，所以一律写进 files/events.log，
 * 回到电脑前用 adb 拉出来就能知道到底停在哪一步。
 */
public final class EvLog {

    private static final String FILE = "events.log";
    private static final int MAX_LINES = 400;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static Context app;

    private EvLog() {
    }

    public static void init(Context c) {
        if (app == null && c != null) app = c.getApplicationContext();
    }

    public static void add(String tag, String msg) {
        Log.i(tag, msg);
        if (app == null) return;
        synchronized (LOCK) {
            try {
                File f = new File(app.getFilesDir(), FILE);
                List<String> lines = readAll(f);
                lines.add(FMT.format(new Date()) + " [" + tag + "] " + msg);
                if (lines.size() > MAX_LINES) {
                    lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
                }
                Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
                try {
                    for (String l : lines) {
                        w.write(l);
                        w.write('\n');
                    }
                } finally {
                    w.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<String> readAll(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** 最近 n 行，给界面上的「诊断」区块用。 */
    public static String tail(int n) {
        if (app == null) return "还没有记录";
        List<String> lines = readAll(new File(app.getFilesDir(), FILE));
        if (lines.isEmpty()) return "还没有记录";
        int from = Math.max(0, lines.size() - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
