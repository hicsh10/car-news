package com.hicsh10.carnews;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 8시 알람이 울리면 뉴스를 받아 알림을 띄우고 다음 평일 알람을 다시 예약. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context ctx = context.getApplicationContext();
        NewsAlarm.schedule(ctx);
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Notifier.showNews(ctx, false);
            } finally {
                result.finish();
            }
        }).start();
    }
}
