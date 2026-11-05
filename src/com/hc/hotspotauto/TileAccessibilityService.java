package com.hc.hotspotauto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 通过无障碍服务代替手动操作：展开快捷设置面板 -> 点「个人热点」磁贴 -> 收起。
 * 这是 Android 16/17 上普通应用（无 root、无 Shizuku）唯一还能开关热点的路径。
 */
public class TileAccessibilityService extends AccessibilityService {

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    private static final String TAG = "TileA11y";
    private static TileAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean busy;
    private boolean wantOn;
    private Callback callback;
    private AccessibilityNodeInfo tile;
    private int gestureTries;
    private boolean usedTap;
    private int verifyRound;

    public static TileAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Prefs.init(this);
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要监听事件，全部按需主动查询
    }

    @Override
    public void onInterrupt() {
    }

    public boolean isBusy() {
        return busy;
    }

    /** 把热点切到目标状态。已经是目标状态时不会重复点击。 */
    public void setHotspot(boolean on, Callback cb) {
        if (busy) {
            if (cb != null) cb.onDone(false, "上一个操作还没结束");
            return;
        }
        busy = true;
        wantOn = on;
        callback = cb;
        tile = null;
        gestureTries = 0;
        usedTap = false;
        verifyRound = 0;
        wakeScreen();
        handler.postDelayed(this::openPanel, 600);
    }

    /* ---------------- 步骤 1：展开面板 ---------------- */

    private void openPanel() {
        // 面板已经开着就别再发一次，否则 global action 会把它收起来
        if (isPanelOpen()) {
            Log.i(TAG, "openPanel: 面板已经展开，直接找磁贴");
            handler.postDelayed(this::findTileStep, 150);
            return;
        }
        boolean sent = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sent = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "GLOBAL_ACTION_QUICK_SETTINGS threw: " + t);
        }
        Log.i(TAG, "openPanel globalAction=" + sent + " sdk=" + Build.VERSION.SDK_INT);
        handler.postDelayed(this::findTileStep, sent ? 800 : 250);
    }

    private boolean isPanelOpen() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pkg = root.getPackageName();
            return pkg != null && pkg.toString().contains("systemui");
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 步骤 2：找磁贴 ---------------- */

    private void findTileStep() {
        AccessibilityNodeInfo found = findTile();
        if (found != null) {
            Log.i(TAG, "tile found: " + describe(found) + " => state=" + readState(found));
            tile = found;
            handler.postDelayed(this::decide, 150);
            return;
        }
        if (gestureTries < 2) {
            gestureTries++;
            boolean ok = swipeDown();
            Log.w(TAG, "tile not found, swipeDown #" + gestureTries + " dispatched=" + ok);
            handler.postDelayed(this::findTileStep, 800);
            return;
        }
        dumpTree("tile not found after 2 swipes");
        finish(false, "没找到热点磁贴。请确认「个人热点」磁贴在第一屏，或修改关键词。");
    }

    /* ---------------- 步骤 3：读状态决定是否点击 ---------------- */

    private void decide() {
        Boolean state = readState(tile);
        Log.i(TAG, "decide wantOn=" + wantOn + " actual=" + state);
        if (state != null && state == wantOn) {
            closePanel();
            finish(true, wantOn ? "热点本来就开着，跳过" : "热点本来就关着，跳过");
            return;
        }
        if (state == null) {
            long dt = System.currentTimeMillis() - Prefs.lastStateTime();
            if (dt < 15 * 60 * 1000L && Prefs.lastState() == wantOn) {
                closePanel();
                finish(true, "按上次记录判断已是目标状态，跳过");
                return;
            }
        }
        handler.postDelayed(this::doClick, 100);
    }

    /* ---------------- 步骤 4：点击 ---------------- */

    private void doClick() {
        boolean ok = clickTile(tile);
        Log.i(TAG, "doClick clicked=" + ok + " node=" + describe(tile));
        handler.postDelayed(this::verify, 1100);
    }

    /* ---------------- 步骤 5：复核 ---------------- */

    private void verify() {
        verifyRound++;
        AccessibilityNodeInfo t = findTile();
        Boolean state = t == null ? null : readState(t);
        Log.i(TAG, "verify#" + verifyRound + " state=" + state + (t == null ? " (panel closed)" : ""));
        if (t == null) {
            Prefs.markState(wantOn);
            finish(true, wantOn ? "已发出开启指令，热点正在启动" : "已发出关闭指令");
            return;
        }
        if (state != null && state == wantOn) {
            Prefs.markState(wantOn);
            closePanel();
            finish(true, wantOn ? "热点已开启" : "热点已关闭");
            return;
        }
        // 热点启动要一两秒，多轮询几次，别急着判失败反复点击
        if (verifyRound < 3) {
            handler.postDelayed(this::verify, 800);
            return;
        }
        if (!usedTap) {
            usedTap = true;
            boolean ok = false;
            try {
                ok = t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "verify retry ACTION_CLICK=" + ok);
            if (ok) {
                verifyRound = 0;
                handler.postDelayed(this::verify, 900);
                return;
            }
        }
        closePanel();
        finish(false, "点了但状态没变化，可能磁贴位置或关键词不对");
    }

    private void finish(boolean ok, String msg) {
        busy = false;
        Callback cb = callback;
        callback = null;
        tile = null;
        Log.i(TAG, "result " + ok + " : " + msg);
        if (cb != null) cb.onDone(ok, msg);
    }

    /* ---------------- 节点查找 ---------------- */

    private AccessibilityNodeInfo findTile() {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            return null;
        }
        if (root == null) return null;

        // 只在 SystemUI 的窗口里找，避免误抓自己的界面或通知
        CharSequence pk = root.getPackageName();
        String pkg = pk == null ? "" : pk.toString();
        if (!pkg.contains("systemui")) {
            Log.w(TAG, "active window=" + pkg + " 不是系统界面，说明面板没展开");
            return null;
        }

        List<String> kws = Prefs.keywords();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo weak = null;
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 800) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            int score = matchScore(n, kws);
            if (score == 2) return n;
            if (score == 1 && weak == null) weak = n;
            int count = n.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return weak;
    }

    /**
     * 2 = 完全命中关键词；1 = 关键词打头且节点像个开关；0 = 不是。
     * 精确匹配是关键：之前用 contains 会误抓「热点自动化运行中」这种通知文本。
     */
    private int matchScore(AccessibilityNodeInfo n, List<String> kws) {
        String cd = norm(str(n.getContentDescription()));
        String tx = norm(str(n.getText()));
        for (String k : kws) {
            String kk = norm(k);
            if (kk.isEmpty()) continue;
            if (cd.equals(kk) || tx.equals(kk)) return 2;
        }
        for (String k : kws) {
            String kk = norm(k);
            if (kk.isEmpty()) continue;
            if (cd.startsWith(kk) && cd.length() <= kk.length() + 6 && isToggleLike(n)) return 1;
            if (tx.startsWith(kk) && tx.length() <= kk.length() + 6 && isToggleLike(n)) return 1;
        }
        return 0;
    }

    private boolean isToggleLike(AccessibilityNodeInfo n) {
        if (n.isClickable() || n.isCheckable()) return true;
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && n.getStateDescription() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String norm(String s) {
        return s.replace(" ", "").replace("\u00A0", "").replace("　", "").trim();
    }

    private Boolean readState(AccessibilityNodeInfo n) {
        if (n == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(str(n.getContentDescription())).append('|');
        sb.append(str(n.getText())).append('|');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                sb.append(str(n.getStateDescription()));
            } catch (Throwable ignored) {
            }
        }
        String s = sb.toString().toLowerCase();

        String[] off = {"关闭", "未开启", "已停用", "off", "禁用", "off。"};
        String[] on = {"开启", "打开", "已启用", "启用", "on"};
        for (String k : off) {
            if (s.contains(k)) return Boolean.FALSE;
        }
        for (String k : on) {
            if (s.contains(k)) return Boolean.TRUE;
        }
        Boolean c = checkChild(n, 0);
        if (c != null) return c;
        return null;
    }

    private Boolean checkChild(AccessibilityNodeInfo n, int depth) {
        if (depth > 4) return null;
        if (n.isCheckable()) return n.isChecked();
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            if (c.isCheckable()) return c.isChecked();
            Boolean r = checkChild(c, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /* ---------------- 动作 ---------------- */

    private boolean clickTile(AccessibilityNodeInfo n) {
        if (n == null) return false;
        // 坐标点击最接近真人触摸，OxygenOS 的磁贴不响应 ACTION_CLICK，所以优先用它
        AccessibilityNodeInfo target = n;
        for (int i = 0; i < 4; i++) {
            if (target.isClickable() || target.isCheckable()) break;
            AccessibilityNodeInfo p = target.getParent();
            if (p == null) break;
            target = p;
        }
        Rect r = new Rect();
        target.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            return tap(r.centerX(), r.centerY());
        }
        try {
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 60);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private boolean swipeDown() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels;
        float h = dm.heightPixels;
        Path p = new Path();
        p.moveTo(w / 2f, h * 0.01f);
        p.lineTo(w / 2f, h * 0.78f);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 280);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    /** 只在确认面板开着的时候才按返回，避免误退出用户正在用的 App。 */
    private void closePanel() {
        if (!isPanelOpen()) return;
        performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "closePanel: 发送 BACK");
        // Android 16 上 BACK 有时收不掉面板，半秒后复查，还在就用上滑手势
        handler.postDelayed(this::forceClosePanel, 500);
    }

    private void forceClosePanel() {
        if (busy || !isPanelOpen()) return;
        Log.w(TAG, "closePanel: BACK 没生效，改用上滑手势");
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float x = dm.widthPixels / 2f;
            Path path = new Path();
            path.moveTo(x, dm.heightPixels * 0.80f);
            path.lineTo(x, dm.heightPixels * 0.15f);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 260);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        } catch (Throwable ignored) {
        }
    }

    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "hotspot:wake");
            wl.acquire(8000);
        } catch (Throwable ignored) {
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    /** 单个节点的可读描述，用于排查。 */
    private String describe(AccessibilityNodeInfo n) {
        if (n == null) return "null";
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        StringBuilder sb = new StringBuilder();
        sb.append(n.getClassName())
                .append(" text=").append(str(n.getText()))
                .append(" cd=").append(str(n.getContentDescription()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                sb.append(" sd=").append(str(n.getStateDescription()));
            } catch (Throwable ignored) {
            }
        }
        sb.append(" click=").append(n.isClickable())
                .append(" check=").append(n.isCheckable())
                .append(" ").append(r.toShortString());
        return sb.toString();
    }

    /** 把当前窗口的控件树打进 logcat，找磁贴失败时用。 */
    private void dumpTree(String reason) {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            Log.w(TAG, "dumpTree: root unavailable " + t);
            return;
        }
        if (root == null) {
            Log.w(TAG, "dumpTree(" + reason + "): root is null —— 面板根本没展开，或屏幕没亮");
            return;
        }
        Log.w(TAG, "dumpTree(" + reason + ") root pkg=" + root.getPackageName()
                + " keywords=" + Prefs.keywords());
        dumpNode(root, 0, new int[]{0});
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, int[] budget) {
        if (n == null || depth > 14 || budget[0] > 200) return;
        budget[0]++;
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) pad.append("· ");
        Log.w(TAG, pad + describe(n));
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            dumpNode(n.getChild(i), depth + 1, budget);
        }
    }
}
