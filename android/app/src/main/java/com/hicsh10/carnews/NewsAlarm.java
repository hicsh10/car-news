package com.hicsh10.carnews;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/** 평일 아침 8시 알림 예약과 사용자 설정(알림 켜기·끄기). */
public final class NewsAlarm {
    public static final String RAW_BASE = "https://raw.githubusercontent.com/hicsh10/car-news/main/";
    public static final int HOUR = 8;
    public static final int MINUTE = 0;
    private static final String PREFS = "settings";
    private static final String KEY_ENABLED = "notify_enabled";

    private NewsAlarm() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
        schedule(ctx);
    }

    static boolean isWeekday(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY;
    }

    /** 다음 평일 오전 8시. 공휴일은 알림 시점에 확인해 건너뜀. */
    public static long nextTrigger() {
        Calendar now = Calendar.getInstance();
        Calendar c = (Calendar) now.clone();
        c.set(Calendar.HOUR_OF_DAY, HOUR);
        c.set(Calendar.MINUTE, MINUTE);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (!c.after(now)) c.add(Calendar.DAY_OF_YEAR, 1);
        while (!isWeekday(c)) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        return PendingIntent.getBroadcast(ctx, 800, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx);
        if (!isEnabled(ctx)) {
            am.cancel(pi);
            return;
        }
        long at = nextTrigger();
        try {
            boolean exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exactOk) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }
}
