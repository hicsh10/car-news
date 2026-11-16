package com.hicsh10.carnews;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 뉴스 화면(웹 화면 재사용) + 알림 권한 요청 + 8시 알람 예약. */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // 기사 링크는 휴대폰 기본 브라우저에서 원문으로 열기
                Uri u = req.getUrl();
                if ("about".equals(u.getScheme()) || "data".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        loadPage();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        NewsAlarm.schedule(this);
    }

    /** 최신 화면을 GitHub에서 받고, 실패하면 앱에 들어 있는 화면을 사용. */
    private void loadPage() {
        new Thread(() -> {
            String html;
            try {
                html = Notifier.httpGet(NewsAlarm.RAW_BASE + "index.html?t=" + System.currentTimeMillis());
                if (!html.contains("자동차 뉴스")) throw new IllegalStateException("bad page");
            } catch (Exception e) {
                html = readAsset("index.html");
            }
            final String page = html;
            runOnUiThread(() -> web.loadDataWithBaseURL(NewsAlarm.RAW_BASE, page, "text/html", "UTF-8", null));
        }).start();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<meta charset='utf-8'><p style='font-family:sans-serif;padding:24px'>화면을 불러오지 못했습니다. 인터넷 연결을 확인해 주세요.</p>";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.appResumed && window.appResumed()", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** 화면(JavaScript)에서 부르는 기능. */
    class Bridge {
        @JavascriptInterface
        public void testNotify() {
            new Thread(() -> Notifier.showNews(getApplicationContext())).start();
        }

        @JavascriptInterface
        public boolean notificationsEnabled() {
            NotificationManager nm = getSystemService(NotificationManager.class);
            return nm != null && nm.areNotificationsEnabled();
        }

        @JavascriptInterface
        public String alarmTime() {
            return String.format("%d:%02d", NewsAlarm.HOUR, NewsAlarm.MINUTE);
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            runOnUiThread(() -> startActivity(i));
        }
    }
}
