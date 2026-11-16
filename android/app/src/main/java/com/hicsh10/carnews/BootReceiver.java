package com.hicsh10.carnews;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 휴대폰 재시작·앱 업데이트·시간 변경 후 알람을 다시 예약. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        NewsAlarm.schedule(context.getApplicationContext());
    }
}
