package com.hc.hotspotauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 升级后自动把监听服务拉起来，并注册看门狗。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs.init(context);
        EvLog.init(context);
        EvLog.add("BootReceiver", "收到 " + intent.getAction() + "，拉起服务");
        HotspotService.start(context);
        KeepAliveJob.schedule(context);
    }
}
