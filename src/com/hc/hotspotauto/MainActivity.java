package com.hc.hotspotauto;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int LEVEL_OK = 0;
    private static final int LEVEL_WARN = 1;
    private static final int LEVEL_BAD = 2;

    private static final String[] STATUS_LABELS = {"无障碍服务", "蓝牙权限", "电池优化"};
    private static final String[] STATUS_ACTIONS = {"去开启", "去授权", "去设置"};

    private TextView tvHeroStatus;
    private TextView tvHeroDesc;
    private Button btnHeroAction;
    private ImageView icLeft;
    private ImageView icRight;
    private View chipLeft;
    private View chipRight;
    private ImageView icTileOn;
    private ImageView icTileOff;
    private TextView tvOnState;
    private TextView tvOffState;
    private TextView tvDevCount;
    private ListView listDevices;

    private final List<StatusRow> statusRows = new ArrayList<>();
    private final List<Device> devices = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private DeviceAdapter listAdapter;

    private static class StatusRow {
        int index;
        View root;
        View dot;
        TextView value;
        TextView action;
    }

    private static class Device {
        final String name;
        final String mac;
        final String hint;
        final boolean placeholder;

        Device(String name, String mac, String hint, boolean placeholder) {
            this.name = name;
            this.mac = mac;
            this.hint = hint;
            this.placeholder = placeholder;
        }

        static Device placeholder(String title, String hint) {
            return new Device(title, null, hint, true);
        }
    }

    private static class DeviceHolder {
        TextView name;
        TextView mac;
        ImageView check;
    }

    private static class EmptyHolder {
        TextView title;
        TextView hint;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        EvLog.init(this);
        setContentView(R.layout.activity_main);

        tvHeroStatus = findViewById(R.id.tvHeroStatus);
        tvHeroDesc = findViewById(R.id.tvHeroDesc);
        btnHeroAction = findViewById(R.id.btnHeroAction);
        icLeft = findViewById(R.id.icLeft);
        icRight = findViewById(R.id.icRight);
        chipLeft = findViewById(R.id.chipLeft);
        chipRight = findViewById(R.id.chipRight);
        icTileOn = findViewById(R.id.icTileOn);
        icTileOff = findViewById(R.id.icTileOff);
        tvOnState = findViewById(R.id.tvOnState);
        tvOffState = findViewById(R.id.tvOffState);
        tvDevCount = findViewById(R.id.tvDevCount);
        listDevices = findViewById(R.id.listDevices);

        selected.addAll(Prefs.triggers());

        listAdapter = new DeviceAdapter();
        listDevices.setAdapter(listAdapter);
        listDevices.setOnItemClickListener((parent, view, position, id) -> {
            Device d = devices.get(position);
            if (d.placeholder || d.mac == null) return;
            if (!selected.remove(d.mac)) selected.add(d.mac);
            Prefs.setTriggers(selected);
            listAdapter.notifyDataSetChanged();
            updateCount();
            refreshHero();
        });

        findViewById(R.id.btnRefresh).setOnClickListener(v -> loadDevices());
        findViewById(R.id.navOn).setOnClickListener(v -> test(true));
        findViewById(R.id.navOff).setOnClickListener(v -> test(false));
        findViewById(R.id.navSet).setOnClickListener(v -> showSettings());

        findViewById(R.id.tileOn).setOnClickListener(v -> {
            Prefs.setAutoOn(!Prefs.autoOn());
            refreshTiles();
            toast(Prefs.autoOn() ? "已开启：连上设备自动开热点" : "已关闭自动开热点");
        });
        findViewById(R.id.tileOff).setOnClickListener(v -> {
            Prefs.setAutoOff(!Prefs.autoOff());
            refreshTiles();
            toast(Prefs.autoOff() ? "已开启：断开后自动关热点" : "已关闭自动关热点");
        });

        HotspotService.start(this);
        KeepAliveJob.schedule(this);
        requestPerms();

        // adb 远程触发：am start -n com.hc.hotspotauto/.MainActivity -a com.hc.hotspotauto.TEST_ON
        handleTestIntent(getIntent());
    }

    /* ---------------- 主卡片：当前状态 ---------------- */

    private void refreshHero() {
        boolean acc = isAccessibilityOn();
        boolean bt = hasBtPermission();
        boolean ready = acc && bt;

        int tint = getColor(ready ? R.color.accent : R.color.text_tertiary);
        icLeft.setImageTintList(ColorStateList.valueOf(tint));
        icRight.setImageTintList(ColorStateList.valueOf(tint));
        int chip = ready ? R.drawable.bg_chip_icon_accent : R.drawable.bg_chip_icon;
        chipLeft.setBackgroundResource(chip);
        chipRight.setBackgroundResource(chip);

        if (!acc) {
            showHero("未就绪", "无障碍服务没开，点不动热点磁贴",
                    "去开启无障碍", v -> openAccessibility());
            return;
        }
        if (!bt) {
            showHero("未就绪", "蓝牙权限未授权，读不到已配对车机",
                    "去授权蓝牙", v -> requestPerms());
            return;
        }

        List<String> names = selectedNames();
        if (names.isEmpty()) {
            String desc = selected.isEmpty()
                    ? "还没选触发设备。在下面列表里勾选你的车机。"
                    : "已选的设备当前不在配对列表里，可能已取消配对。";
            showHero("待命", desc, null, null);
            return;
        }
        String who = names.size() == 1 ? names.get(0) : names.get(0) + " 等 " + names.size() + " 台";
        showHero("待命", "「" + who + "」连上时自动开热点，断开 "
                + Prefs.offDelaySec() + " 秒后关闭。", null, null);
    }

    private void showHero(String status, String desc, String action, View.OnClickListener click) {
        tvHeroStatus.setText(status);
        tvHeroDesc.setText(desc);
        if (action == null) {
            btnHeroAction.setVisibility(View.GONE);
        } else {
            btnHeroAction.setVisibility(View.VISIBLE);
            btnHeroAction.setText(action);
            btnHeroAction.setOnClickListener(click);
        }
    }

    private void refreshTiles() {
        boolean on = Prefs.autoOn();
        boolean off = Prefs.autoOff();
        tvOnState.setText(on ? "已开启" : "已关闭");
        tvOnState.setTextColor(getColor(on ? R.color.text_primary : R.color.text_tertiary));
        icTileOn.setImageTintList(ColorStateList.valueOf(
                getColor(on ? R.color.accent : R.color.text_tertiary)));

        tvOffState.setText(off ? "延迟 " + Prefs.offDelaySec() + " 秒" : "已关闭");
        tvOffState.setTextColor(getColor(off ? R.color.text_primary : R.color.text_tertiary));
        icTileOff.setImageTintList(ColorStateList.valueOf(
                getColor(off ? R.color.accent : R.color.text_tertiary)));
    }

    private List<String> selectedNames() {
        List<String> out = new ArrayList<>();
        for (Device d : devices) {
            if (!d.placeholder && d.mac != null && selected.contains(d.mac)) out.add(d.name);
        }
        return out;
    }

    private boolean isAccessibilityOn() {
        String val = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(val)) return false;
        return val.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /* ---------------- 设备列表 ---------------- */

    private void loadDevices() {
        devices.clear();

        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (!hasBtPermission()) {
            devices.add(Device.placeholder("没有蓝牙权限", "点主卡片的「去授权蓝牙」，允许访问附近的设备"));
        } else if (bt == null || !bt.isEnabled()) {
            devices.add(Device.placeholder("蓝牙未开启", "打开系统蓝牙后，回来点右上角刷新"));
        } else {
            try {
                Set<BluetoothDevice> bonded = bt.getBondedDevices();
                for (BluetoothDevice d : bonded) {
                    String mac;
                    String name;
                    try {
                        mac = d.getAddress();
                        name = d.getName();
                    } catch (SecurityException e) {
                        continue;
                    }
                    devices.add(new Device(
                            name == null || name.isEmpty() ? "未知设备" : name,
                            mac, null, false));
                }
            } catch (SecurityException e) {
                devices.add(Device.placeholder("没有蓝牙权限", "重新授予权限后再刷新"));
            }
            if (devices.isEmpty()) {
                devices.add(Device.placeholder("没有已配对设备", "先在系统蓝牙设置里和车机配对，再回来刷新"));
            } else {
                Collections.sort(devices, (a, b) -> a.name.compareToIgnoreCase(b.name));
            }
        }

        listAdapter.notifyDataSetChanged();
        updateCount();
        refreshHero();
    }

    private void updateCount() {
        if (devices.isEmpty() || devices.get(0).placeholder) {
            tvDevCount.setText("—");
            return;
        }
        int hit = 0;
        for (Device d : devices) {
            if (selected.contains(d.mac)) hit++;
        }
        tvDevCount.setText("已选 " + hit + " / " + devices.size());
    }

    private class DeviceAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return devices.size();
        }

        @Override
        public Device getItem(int position) {
            return devices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return devices.get(position).placeholder ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return !devices.get(position).placeholder;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Device d = devices.get(position);

            if (d.placeholder) {
                View v = convertView;
                if (v == null || !(v.getTag() instanceof EmptyHolder)) {
                    v = getLayoutInflater().inflate(R.layout.item_empty, parent, false);
                    EmptyHolder h = new EmptyHolder();
                    h.title = v.findViewById(R.id.tvEmptyTitle);
                    h.hint = v.findViewById(R.id.tvEmptyHint);
                    v.setTag(h);
                }
                EmptyHolder h = (EmptyHolder) v.getTag();
                h.title.setText(d.name);
                h.hint.setText(d.hint);
                return v;
            }

            View v = convertView;
            if (v == null || !(v.getTag() instanceof DeviceHolder)) {
                v = getLayoutInflater().inflate(R.layout.item_device, parent, false);
                DeviceHolder h = new DeviceHolder();
                h.name = v.findViewById(R.id.tvName);
                h.mac = v.findViewById(R.id.tvMac);
                h.check = v.findViewById(R.id.ivCheck);
                v.setTag(h);
            }
            DeviceHolder h = (DeviceHolder) v.getTag();
            boolean on = selected.contains(d.mac);
            v.setBackgroundResource(on ? R.drawable.bg_row_selected : R.drawable.bg_row);
            h.name.setText(d.name);
            h.mac.setText(d.mac);
            h.check.setImageResource(on ? R.drawable.bg_check_on : R.drawable.bg_check_off);
            h.check.setContentDescription(on ? "已设为触发设备" : "未选择");
            v.setContentDescription(d.name + "，" + (on ? "已设为触发设备" : "未选择"));
            return v;
        }
    }

    /* ---------------- 设置 ---------------- */

    private void showSettings() {
        final Dialog dialog = new Dialog(this, R.style.DialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_settings);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            // 内容比屏幕高，改成底部弹层：高度封顶，剩下的交给内部 ScrollView 滚
            int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.86f);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.BOTTOM;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = maxH;
            lp.y = dp(26);
            w.setAttributes(lp);
        }

        Switch swOn = dialog.findViewById(R.id.swAutoOn);
        Switch swOff = dialog.findViewById(R.id.swAutoOff);
        swOn.setChecked(Prefs.autoOn());
        swOff.setChecked(Prefs.autoOff());
        swOn.setOnCheckedChangeListener((v, c) -> {
            Prefs.setAutoOn(c);
            refreshTiles();
        });
        swOff.setOnCheckedChangeListener((v, c) -> {
            Prefs.setAutoOff(c);
            refreshTiles();
        });

        final TextView tvDelay = dialog.findViewById(R.id.tvDelay);
        SeekBar sb = dialog.findViewById(R.id.sbDelay);
        int delay = Math.max(1, Math.min(30, Prefs.offDelaySec()));
        sb.setProgress(delay - 1);
        tvDelay.setText(delay + " 秒");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvDelay.setText((progress + 1) + " 秒");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Prefs.setOffDelaySec(seekBar.getProgress() + 1);
                refreshTiles();
                refreshHero();
            }
        });

        final EditText et = dialog.findViewById(R.id.etKeywords);
        et.setText(Prefs.keywordsRaw());
        et.setHint("个人热点,热点,Hotspot");

        buildStatusRows(dialog);
        bindStatusRows();

        final TextView tvDiag = dialog.findViewById(R.id.tvDiag);
        tvDiag.setText(EvLog.tail(5));
        tvDiag.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("events", EvLog.tail(60)));
                    toast("事件日志已复制");
                }
            } catch (Throwable ignored) {
            }
        });

        dialog.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btnSave).setOnClickListener(v -> {
            Prefs.setKeywordsRaw(et.getText().toString());
            toast("关键词已保存");
            dialog.dismiss();
        });

        dialog.show();
    }

    private void buildStatusRows(Dialog dialog) {
        statusRows.clear();
        LinearLayout well = dialog.findViewById(R.id.statusWell);
        well.removeAllViews();

        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < STATUS_LABELS.length; i++) {
            if (i > 0) well.addView(hairline());
            View row = inf.inflate(R.layout.item_status_row, well, false);
            StatusRow sr = new StatusRow();
            sr.index = i;
            sr.root = row;
            sr.dot = row.findViewById(R.id.dot);
            sr.value = row.findViewById(R.id.tvValue);
            sr.action = row.findViewById(R.id.tvAction);
            ((TextView) row.findViewById(R.id.tvLabel)).setText(STATUS_LABELS[i]);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onStatusAction(sr.index);
            });
            well.addView(row);
            statusRows.add(sr);
        }
    }

    private void bindStatusRows() {
        if (statusRows.size() < 3) return;
        boolean acc = isAccessibilityOn();
        boolean bt = hasBtPermission();
        boolean bat = isIgnoringBattery();
        bindRow(statusRows.get(0), acc ? LEVEL_OK : LEVEL_BAD,
                acc ? "已开启" : "未开启", acc ? null : STATUS_ACTIONS[0]);
        bindRow(statusRows.get(1), bt ? LEVEL_OK : LEVEL_BAD,
                bt ? "已授权" : "未授权", bt ? null : STATUS_ACTIONS[1]);
        bindRow(statusRows.get(2), bat ? LEVEL_OK : LEVEL_WARN,
                bat ? "已忽略" : "未忽略", bat ? null : STATUS_ACTIONS[2]);
    }

    private void bindRow(StatusRow r, int level, String value, String action) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(getColor(level == LEVEL_OK ? R.color.ok
                : level == LEVEL_WARN ? R.color.warn : R.color.bad));
        r.dot.setBackground(dot);

        r.value.setText(value);
        r.value.setTextColor(getColor(level == LEVEL_OK ? R.color.text_primary
                : level == LEVEL_WARN ? R.color.warn : R.color.bad));

        if (action == null) {
            // 保留占位，三行「值」列才能纵向对齐
            r.action.setVisibility(View.INVISIBLE);
            r.root.setClickable(false);
        } else {
            r.action.setVisibility(View.VISIBLE);
            r.action.setText(action);
            r.root.setClickable(true);
        }
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.line));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMarginStart(dp(18));
        v.setLayoutParams(lp);
        return v;
    }

    private void onStatusAction(int index) {
        if (index == 0) openAccessibility();
        else if (index == 1) requestPerms();
        else openBattery();
    }

    /* ---------------- 测试 ---------------- */

    private void test(boolean on) {
        TileAccessibilityService svc = TileAccessibilityService.get();
        if (svc == null) {
            toast("无障碍服务没开，无法操作热点");
            openAccessibility();
            return;
        }
        toast("正在" + (on ? "开启" : "关闭") + "热点…");
        svc.setHotspot(on, (ok, msg) -> toast((ok ? "成功：" : "失败：") + msg));
    }

    /* ---------------- 权限与跳转 ---------------- */

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 100);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshHero();
        loadDevices();
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("在列表里找到「热点自动化」并开启");
        } catch (Throwable t) {
            toast("打不开无障碍设置");
        }
    }

    private void openBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请手动到系统设置里关闭电池优化");
            }
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTestIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTiles();
        loadDevices();
        refreshHero();
    }

    private void handleTestIntent(Intent intent) {
        String act = intent == null ? null : intent.getAction();
        if ("com.hc.hotspotauto.SIM_ON".equals(act) || "com.hc.hotspotauto.SIM_OFF".equals(act)) {
            final boolean link = "com.hc.hotspotauto.SIM_ON".equals(act);
            String mac = intent.getStringExtra("mac");
            HotspotService.simulate(this, link, mac);
            return;
        }
        final boolean on;
        if ("com.hc.hotspotauto.TEST_ON".equals(act)) {
            on = true;
        } else if ("com.hc.hotspotauto.TEST_OFF".equals(act)) {
            on = false;
        } else {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> test(on), 1200);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
