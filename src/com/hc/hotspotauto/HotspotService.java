package com.hc.hotspotauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/** 常驻前台服务：双重监听已配对车机的蓝牙连接，触发热点开关。 */
public class HotspotService extends Service {

    private static final String TAG = "HotspotService";
    private static final String CHANNEL = "watch";
    private static final int NOTI_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private BtWatch watch;
    private Runnable pendingOff;

    private long lastRequestAt;
    private boolean lastRequestOn;
    private String lastResult = "等待蓝牙事件";

    /** 车机是否连着，null 表示还不知道。用来吃掉广播和轮询的重复事件。 */
    private Boolean carLinked;

    public static void start(Context ctx) {
        send(ctx, new Intent(ctx, HotspotService.class));
    }

    /** 给服务发一条指令（服务本身不导出，只能从应用内发起）。 */
    public static void send(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            EvLog.add(TAG, "启动服务失败: " + t);
        }
    }

    /** 本地模拟一次蓝牙连接 / 断开，便于验证整条链路。 */
    public static void simulate(Context ctx, boolean connected, String mac) {
        Intent i = new Intent(ctx, HotspotService.class);
        i.setAction(connected ? "com.hc.hotspotauto.SIM_ON" : "com.hc.hotspotauto.SIM_OFF");
        if (mac != null) i.putExtra("mac", mac);
        send(ctx, i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        EvLog.init(this);
        EvLog.add(TAG, "服务创建，触发设备=" + Prefs.triggerMacs()
                + " autoOn=" + Prefs.autoOn() + " autoOff=" + Prefs.autoOff());
        createChannel();
        startForeground(NOTI_ID, buildNotification());
        registerBtReceiver();
        KeepAliveJob.schedule(this);
        startWatch();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if ("com.hc.hotspotauto.SIM_ON".equals(a) || "com.hc.hotspotauto.SIM_OFF".equals(a)) {
                simulate(intent, "com.hc.hotspotauto.SIM_ON".equals(a));
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        EvLog.add(TAG, "服务销毁");
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiver = null;
        }
        if (watch != null) {
            watch.stop();
            watch = null;
        }
        cancelPendingOff();
        super.onDestroy();
    }

    /** 本地模拟一次蓝牙事件，用来验证「事件 → 开热点」这段链路。 */
    private void simulate(Intent intent, boolean connected) {
        String mac = intent.getStringExtra("mac");
        if (mac == null || mac.isEmpty()) {
            List<String> macs = Prefs.triggerMacs();
            mac = macs.isEmpty() ? "" : macs.get(0);
        }
        EvLog.add(TAG, "模拟事件 " + (connected ? "连接" : "断开") + " " + mac);
        if (!mac.isEmpty()) handleDevice(mac, connected, "模拟");
    }

    /* ---------------- 路径一：系统广播 ---------------- */

    private void registerBtReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBtBroadcast(intent);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            // 监听系统受保护广播，用 EXPORTED 最稳；NOT_EXPORTED 在个别 ROM 上收不到
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            EvLog.add(TAG, "广播接收器已注册");
        } catch (Throwable t) {
            EvLog.add(TAG, "注册广播接收器失败: " + t);
        }
    }

    private void onBtBroadcast(Intent intent) {
        String action = intent.getAction();
        BluetoothDevice dev;
        try {
            dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Throwable t) {
            EvLog.add(TAG, "取 EXTRA_DEVICE 失败: " + t);
            return;
        }
        if (dev == null) {
            EvLog.add(TAG, "广播里没有设备对象: " + action);
            return;
        }

        String mac = "";
        String name = "?";
        try {
            mac = dev.getAddress();
        } catch (Throwable t) {
            EvLog.add(TAG, "拿不到 MAC（缺 BLUETOOTH_CONNECT）: " + t);
        }
        try {
            name = dev.getName();
        } catch (Throwable ignored) {
        }

        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
        EvLog.add(TAG, "广播 " + (connected ? "CONNECTED " : "DISCONNECTED ") + mac + " " + name
                + (Prefs.isTrigger(mac) ? " [命中]" : " [非触发设备]"));
        if (mac.isEmpty() || !Prefs.isTrigger(mac)) return;
        handleDevice(mac, connected, "广播");
    }

    /* ---------------- 路径二：profile 轮询 ---------------- */

    private void startWatch() {
        watch = new BtWatch(this);
        watch.start((mac, name, connected, firstPoll) -> {
            boolean hit = Prefs.isTrigger(mac);
            EvLog.add(TAG, "轮询 " + (connected ? "已连接 " : "已断开 ") + mac + " " + name
                    + (firstPoll ? " [初次扫描]" : "") + (hit ? " [命中]" : " [非触发设备]"));
            if (hit) handleDevice(mac, connected, firstPoll ? "轮询(启动时已在连)" : "轮询");
        });
    }

    /* ---------------- 决策 ---------------- */

    private void handleDevice(String mac, boolean connected, String why) {
        if (carLinked != null && carLinked == connected) {
            EvLog.add(TAG, "车机状态没变（" + (connected ? "已连" : "已断") + "），忽略这条");
            return;
        }
        carLinked = connected;

        if (connected) {
            cancelPendingOff();
            if (Prefs.autoOn()) {
                request(true, why + "：车机已连接");
            } else {
                EvLog.add(TAG, "已连接，但「连上就开热点」是关着的");
            }
        } else {
            cancelPendingOff();
            if (Prefs.autoOff()) {
                final String src = why;
                int delay = Math.max(3, Prefs.offDelaySec());
                pendingOff = () -> request(false, src + "：车机已断开");
                handler.postDelayed(pendingOff, delay * 1000L);
                EvLog.add(TAG, "断开，将在 " + delay + " 秒后关热点");
            }
        }
    }

    private void cancelPendingOff() {
        if (pendingOff != null) {
            handler.removeCallbacks(pendingOff);
            pendingOff = null;
        }
    }

    private void request(boolean on, String why) {
        long now = System.currentTimeMillis();
        if (now - lastRequestAt < 3000L && lastRequestOn == on) return;
        lastRequestAt = now;
        lastRequestOn = on;

        TileAccessibilityService svc = TileAccessibilityService.get();
        if (svc == null) {
            lastResult = "无障碍服务没开，无法操作热点";
            EvLog.add(TAG, why + " -> " + lastResult);
            updateNotification();
            return;
        }
        lastResult = "正在" + (on ? "开启" : "关闭") + "热点…";
        EvLog.add(TAG, why + " -> " + lastResult);
        updateNotification();

        svc.setHotspot(on, (ok, msg) -> {
            lastResult = (ok ? "✓ " : "✗ ") + msg;
            EvLog.add(TAG, "结果 " + lastResult);
            updateNotification();
        });
    }

    /* ---------------- 通知 ---------------- */

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "热点自动化", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("热点自动化运行中")
                .setContentText(lastResult)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }
}
