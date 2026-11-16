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
        ch.setDescription("매일 아침 8시 자동차 뉴스 요약");
        nm.createNotificationChannel(ch);
    }

    private static String pick(JSONObject n) {
        String ko = n.optString("title_ko", "");
        return ko.isEmpty() ? n.optString("title", "") : ko;
    }

    public static void showNews(Context ctx) {
        ensureChannel(ctx);
        Notification.InboxStyle style = new Notification.InboxStyle();
        String text = "오늘의 국내·해외 자동차 뉴스를 확인하세요.";
        try {
            JSONObject data = new JSONObject(httpGet(NewsAlarm.RAW_BASE + "news.json?t="
                    + System.currentTimeMillis()));
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
