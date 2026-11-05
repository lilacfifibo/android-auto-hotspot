package com.hc.hotspotauto;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 备用触发器：直接轮询蓝牙 profile 上已连接的设备，完全不走广播。
 *
 * 为什么要两条路：ACL 广播在部分 ROM / 部分系统版本上会被拦掉或延迟，
 * 光靠 onReceive 一旦漏掉就「纹丝不动」。这里每 10 秒对 A2DP / HFP
 * 两个 profile 取一次已连接设备列表，用差集推连接与断开，和广播互为保险。
 */
public final class BtWatch {

    public interface Listener {
        /** firstPoll = 服务刚起来时的初次扫描结果，此时设备可能早就连着。 */
        void onLink(String mac, String name, boolean connected, boolean firstPoll);
    }

    private static final int[] PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET};
    private static final long PERIOD_MS = 10000L;

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<Integer, BluetoothProfile> proxies = new HashMap<>();
    private final Set<String> last = new HashSet<>();

    private Listener listener;
    private boolean firstPoll = true;
    private boolean started;
    private int emptyRounds;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            if (started) h.postDelayed(this, PERIOD_MS);
        }
    };

    public BtWatch(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void start(Listener l) {
        if (started) return;
        listener = l;
        started = true;
        connectProxies();
        h.postDelayed(tick, 2000L);
        EvLog.add("BtWatch", "轮询启动，间隔 " + (PERIOD_MS / 1000) + " 秒");
    }

    public void stop() {
        started = false;
        h.removeCallbacks(tick);
    }

    private void connectProxies() {
        BluetoothAdapter adapter;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            EvLog.add("BtWatch", "取 BluetoothAdapter 失败: " + t);
            return;
        }
        if (adapter == null) {
            EvLog.add("BtWatch", "这台机器没有蓝牙适配器");
            return;
        }

        BluetoothProfile.ServiceListener sl = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                proxies.put(profile, proxy);
                EvLog.add("BtWatch", "profile " + name(profile) + " 就绪");
            }

            @Override
            public void onServiceDisconnected(int profile) {
                proxies.remove(profile);
                EvLog.add("BtWatch", "profile " + name(profile) + " 断开");
            }
        };

        for (int p : PROFILES) {
            try {
                adapter.getProfileProxy(ctx, sl, p);
            } catch (Throwable t) {
                EvLog.add("BtWatch", "getProfileProxy(" + name(p) + ") 失败: " + t);
            }
        }
    }

    private static String name(int profile) {
        if (profile == BluetoothProfile.A2DP) return "A2DP";
        if (profile == BluetoothProfile.HEADSET) return "HFP";
        return String.valueOf(profile);
    }

    private void poll() {
        Map<String, String> now = new HashMap<>();
        for (Map.Entry<Integer, BluetoothProfile> e : proxies.entrySet()) {
            try {
                for (BluetoothDevice d : e.getValue().getConnectedDevices()) {
                    if (d == null) continue;
                    String mac = d.getAddress();
                    if (mac == null) continue;
                    String nm;
                    try {
                        nm = d.getName();
                    } catch (Throwable t) {
                        nm = "?";
                    }
                    now.put(mac, nm);
                }
            } catch (Throwable t) {
                EvLog.add("BtWatch", "读 " + name(e.getKey()) + " 设备失败: " + t);
            }
        }

        if (proxies.isEmpty()) {
            emptyRounds++;
            if (emptyRounds == 3) EvLog.add("BtWatch", "profile 代理还没拿到，先空转");
            return;
        }

        if (firstPoll) {
            firstPoll = false;
            last.clear();
            last.addAll(now.keySet());
            if (now.isEmpty()) {
                EvLog.add("BtWatch", "初次扫描：当前没有已连设备");
            } else {
                EvLog.add("BtWatch", "初次扫描：已连 " + now.size() + " 台");
                for (Map.Entry<String, String> e : now.entrySet()) {
                    if (listener != null) listener.onLink(e.getKey(), e.getValue(), true, true);
                }
            }
            return;
        }

        for (Map.Entry<String, String> e : now.entrySet()) {
            if (!last.contains(e.getKey()) && listener != null) {
                listener.onLink(e.getKey(), e.getValue(), true, false);
            }
        }
        for (String mac : new HashSet<>(last)) {
            if (!now.containsKey(mac) && listener != null) {
                listener.onLink(mac, "?", false, false);
            }
        }

        last.clear();
        last.addAll(now.keySet());
    }

    /** 当前已连设备的快照，调试用。 */
    public String snapshot() {
        if (proxies.isEmpty()) return "profile 代理未就绪";
        StringBuilder sb = new StringBuilder();
        for (String mac : last) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(mac);
        }
        return sb.length() == 0 ? "无已连设备" : sb.toString();
    }
}
