package com.hc.hotspotauto;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 看门狗：每 15 分钟叫一次，保证监听服务还活着。
 * 国产 ROM 清后台时前台服务也可能被收掉，这里兜一层，
 * 免得「上车时才发现服务早就没了」。
 */
public class KeepAliveJob extends JobService {

    private static final int JOB_ID = 101;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID) != null) return;
            JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(c, KeepAliveJob.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(ji);
        } catch (Throwable t) {
            EvLog.add("KeepAlive", "注册看门狗失败: " + t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        EvLog.add("KeepAlive", "看门狗检查，拉起监听服务");
        HotspotService.start(this);
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
