package com.hicsh10.carnews;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** news.json을 받아 아침 뉴스 알림을 띄움. 백그라운드 스레드에서 호출해야 함. */
public final class Notifier {
    static final String CHANNEL = "morning_news";

    private Notifier() {}

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "아침 뉴스 알림",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("평일 아침 8시 자동차 뉴스 요약");
        nm.createNotificationChannel(ch);
    }

    private static String pick(JSONObject n) {
        String ko = n.optString("title_ko", "");
        return ko.isEmpty() ? n.optString("title", "") : ko;
    }

    /** 서버 목록을 못 받을 때 쓰는 공휴일(평일에 걸친 날만). 최신 목록은 news.json의 holidays. */
    static final Set<String> FALLBACK_HOLIDAYS = new HashSet<>(Arrays.asList(
            "2026-12-25",
            "2027-01-01", "2027-02-08", "2027-02-09", "2027-03-01", "2027-05-03", "2027-05-05",
            "2027-05-13", "2027-07-19", "2027-08-16", "2027-09-14", "2027-09-15", "2027-09-16",
            "2027-10-04", "2027-10-11", "2027-12-27"));

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date());
    }

    /**
     * 아침 뉴스 알림. force=false(정기 알림)이면 알림 꺼짐·주말·공휴일에는 보내지 않음.
     * force=true(테스트)는 항상 보냄.
     */
    public static void showNews(Context ctx, boolean force) {
        if (!force) {
            if (!NewsAlarm.isEnabled(ctx)) return;
            if (!NewsAlarm.isWeekday(Calendar.getInstance())) return;
            if (FALLBACK_HOLIDAYS.contains(today())) return;
        }
        ensureChannel(ctx);
        Notification.InboxStyle style = new Notification.InboxStyle();
        String text = "오늘의 국내·해외 자동차 뉴스를 확인하세요.";
        try {
            JSONObject data = new JSONObject(httpGet(NewsAlarm.RAW_BASE + "news.json?t="
                    + System.currentTimeMillis()));
            JSONArray hol = data.optJSONArray("holidays");
            if (!force && hol != null) {
                String t = today();
                for (int i = 0; i < hol.length(); i++) if (t.equals(hol.optString(i))) return;
            }
            JSONObject cats = data.getJSONObject("categories");
            JSONArray dom = cats.optJSONArray("domestic");
            JSONArray glo = cats.optJSONArray("global");
            int nd = dom == null ? 0 : dom.length();
            int ng = glo == null ? 0 : glo.length();
            for (int i = 0; i < Math.min(3, nd); i++) style.addLine("[국내] " + pick(dom.getJSONObject(i)));
            for (int i = 0; i < Math.min(2, ng); i++) style.addLine("[해외] " + pick(glo.getJSONObject(i)));
            style.setSummaryText("국내 " + nd + " · 해외 " + ng);
            if (nd > 0) text = pick(dom.getJSONObject(0));
            else if (ng > 0) text = pick(glo.getJSONObject(0));
        } catch (Exception e) {
            style.addLine(text);
        }

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setColor(0xFF0F1B2D)
                .setContentTitle("🚗 오늘의 자동차 뉴스")
                .setContentText(text)
                .setStyle(style)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(800, n);
            } catch (SecurityException ignored) {
                // 알림 권한이 꺼져 있음
            }
        }
    }
}
