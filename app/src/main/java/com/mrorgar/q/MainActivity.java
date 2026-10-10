package com.mrorgar.q;

import androidx.webkit.ProxyController;
import androidx.webkit.ProxyConfig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private WebView web;
    private EditText address;
    private LinearLayout root, top, tabStrip, tabRow, addressRow;
    private FrameLayout stage;
    private Button restore;
    private ValueCallback<Uri[]> uploadCallback;

    private static final int PICK_FILE = 601;
    private static final int PICK_MANUAL_UPLOAD = 603;
    private static final int PICK_VRAY = 602;

    private boolean fullscreen = false;
    private DownloadManager downloadManager;

    private final List<BrowserTab> tabs = new ArrayList<>();
    private int activeTabIndex = -1;

    private class BrowserTab {
        WebView view;
        String title = "New tab";
        LinearLayout chip;
        Button selectButton;
        Button closeButton;

        BrowserTab(WebView view) {
            this.view = view;
        }
    }

    private int dp(float value) {
        return (int) (
            value * getResources().getDisplayMetrics().density + 0.5f
        );
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(Color.rgb(32, 33, 36));
        getWindow().setNavigationBarColor(Color.BLACK);

        downloadManager = (DownloadManager)
            getSystemService(DOWNLOAD_SERVICE);

        buildUI();

        String initialUrl = "https://www.google.com";

        if (state != null) {
            String savedUrl = state.getString("current_url");
            if (savedUrl == null) savedUrl = state.getString("url");
            if (savedUrl != null && !savedUrl.trim().isEmpty()) {
                initialUrl = savedUrl;
            }
        }

        createTab(initialUrl);
    }

    private void buildUI() {

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.rgb(32, 33, 36));

        String[] names = {
            "Back", "Forward", "Send", "File",
            "Copy MW", "IP Set", "Save", "F Screen"
        };

        for (String name : names) {
            Button button = new Button(this);

            button.setText(name);
            button.setTextSize(8.5f);
            button.setTextColor(Color.WHITE);
            button.setAllCaps(false);
            button.setPadding(0, 0, 0, 0);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);

            button.setBackgroundTintList(
                ColorStateList.valueOf(Color.rgb(45, 46, 49))
            );

            top.addView(
                button,
                new LinearLayout.LayoutParams(0, dp(38), 1)
            );

            button.setOnClickListener(v -> doAction(name));
        }

        top.getChildAt(6).setOnLongClickListener(v -> {
            multiDownloadDialog();
            return true;
        });

        root.addView(
            top,
            new LinearLayout.LayoutParams(-1, dp(38))
        );

        addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setHint("Website or search words");
        address.setPadding(dp(10), 0, dp(8), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(
            InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_URI
        );

        addressRow.addView(
            address,
            new LinearLayout.LayoutParams(0, dp(42), 1)
        );

        Button setup = new Button(this);
        setup.setText("Setup");
        setup.setTextSize(10);
        setup.setAllCaps(false);
        setup.setPadding(dp(4), 0, dp(4), 0);
        setup.setMinWidth(0);
        setup.setMinimumWidth(0);
        setup.setBackgroundTintList(
            ColorStateList.valueOf(Color.rgb(232, 234, 237))
        );

        addressRow.addView(
            setup,
            new LinearLayout.LayoutParams(dp(62), dp(42))
        );

        setup.setOnClickListener(v -> showSetup());

        root.addView(
            addressRow,
            new LinearLayout.LayoutParams(-1, dp(42))
        );

        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setBackgroundColor(Color.rgb(238, 239, 241));

        HorizontalScrollView tabScroll =
            new HorizontalScrollView(this);

        tabScroll.setHorizontalScrollBarEnabled(false);

        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);

        tabScroll.addView(
            tabStrip,
            new HorizontalScrollView.LayoutParams(-2, -1)
        );

        tabRow.addView(
            tabScroll,
            new LinearLayout.LayoutParams(0, dp(36), 1)
        );

        Button addTab = new Button(this);
        addTab.setText("+");
        addTab.setTextSize(20);
        addTab.setAllCaps(false);
        addTab.setPadding(0, 0, 0, 0);
        addTab.setMinWidth(0);
        addTab.setMinimumWidth(0);

        addTab.setBackgroundTintList(
            ColorStateList.valueOf(Color.rgb(218, 220, 224))
        );

        tabRow.addView(
            addTab,
            new LinearLayout.LayoutParams(dp(44), dp(36))
        );

        addTab.setOnClickListener(v ->
            createTab("https://www.google.com")
        );

        root.addView(
            tabRow,
            new LinearLayout.LayoutParams(-1, dp(36))
        );

        stage = new FrameLayout(this);

        root.addView(
            stage,
            new LinearLayout.LayoutParams(-1, 0, 1)
        );

        setContentView(root);

        address.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadAddress();
                return true;
            }

            return false;
        });
    }

    private void showSetup() {
        new AlertDialog.Builder(this)
            .setTitle("Setup")
            .setMessage(
                "این بخش برای امکانات جدید آینده آماده است.\n\n" +
                "تمام دکمه‌های فعلی مرورگر بیرون از Setup باقی می‌مانند."
            )
            .setPositiveButton("OK", null)
            .show();
    }

    private void createTab(String url) {
        WebView view = new WebView(this);
        BrowserTab tab = new BrowserTab(view);

        tabs.add(tab);

        view.setVisibility(View.GONE);
        stage.addView(
            view,
            0,
            new FrameLayout.LayoutParams(-1, -1)
        );

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(3), 0, dp(2), 0);
        tab.chip = chip;

        Button select = new Button(this);
        select.setText("Tab " + tabs.size());
        select.setTextSize(10);
        select.setAllCaps(false);
        select.setPadding(dp(4), 0, dp(4), 0);
        select.setMinWidth(0);
        select.setMinimumWidth(0);
        select.setMinHeight(0);
        select.setMinimumHeight(0);

        tab.selectButton = select;

        chip.addView(
            select,
            new LinearLayout.LayoutParams(-2, dp(32))
        );

        select.setOnClickListener(v ->
            selectTab(tabs.indexOf(tab))
        );

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(15);
        close.setAllCaps(false);
        close.setPadding(0, 0, 0, 0);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setMinHeight(0);
        close.setMinimumHeight(0);

        tab.closeButton = close;

        chip.addView(
            close,
            new LinearLayout.LayoutParams(dp(27), dp(32))
        );

        close.setOnClickListener(v ->
            closeTab(tabs.indexOf(tab))
        );

        tabStrip.addView(
            chip,
            new LinearLayout.LayoutParams(-2, dp(36))
        );

        configureWeb(view);
        selectTab(tabs.size() - 1);
        view.loadUrl(url);
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        activeTabIndex = index;

        for (int i = 0; i < tabs.size(); i++) {
            BrowserTab tab = tabs.get(i);
            boolean selected = (i == index);

            tab.view.setVisibility(
                selected ? View.VISIBLE : View.GONE
            );

            int color = selected
                ? Color.WHITE
                : Color.rgb(218, 220, 224);

            tab.chip.setBackgroundColor(color);

            tab.selectButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(color)
            );

            tab.closeButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(color)
            );

            tab.selectButton.setText(tab.title);
        }

        web = tabs.get(index).view;

        String currentUrl = web.getUrl();
        address.setText(currentUrl == null ? "" : currentUrl);
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        if (tabs.size() == 1) {
            BrowserTab only = tabs.get(0);
            only.view.stopLoading();
            only.title = "Google";
            only.selectButton.setText(only.title);
            only.view.loadUrl("https://www.google.com");
            selectTab(0);
            return;
        }

        BrowserTab removed = tabs.remove(index);

        stage.removeView(removed.view);
        tabStrip.removeView(removed.chip);

        removed.view.stopLoading();
        removed.view.destroy();

        if (index < activeTabIndex) {
            activeTabIndex--;
        } else if (index == activeTabIndex) {
            activeTabIndex = Math.min(index, tabs.size() - 1);
        }

        for (int i = 0; i < tabs.size(); i++) {
            BrowserTab tab = tabs.get(i);

            if (tab.title.startsWith("Tab ")) {
                tab.title = "Tab " + (i + 1);
            }

            tab.selectButton.setText(tab.title);
        }

        selectTab(activeTabIndex);
    }

    private void configureWeb(WebView target) {
        WebSettings settings = target.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        String oldUA = settings.getUserAgentString();
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("Chrome/([0-9.]+)")
                .matcher(oldUA);

        String chromeVersion = "131.0.0.0";
        if (matcher.find()) {
            chromeVersion = matcher.group(1);
        }

        settings.setUserAgentString(
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/" + chromeVersion + " Safari/537.36"
        );

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);

        if (Build.VERSION.SDK_INT >= 21) {
            cookies.setAcceptThirdPartyCookies(target, true);
        }

        target.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view,
                WebResourceRequest request
            ) {
                String scheme = request.getUrl().getScheme();

                if (scheme == null ||
                    scheme.equalsIgnoreCase("http") ||
                    scheme.equalsIgnoreCase("https")) {
                    return false;
                }

                return true;
            }

            @Override
            public void onPageStarted(
                WebView view,
                String url,
                Bitmap favicon
            ) {
                updateTabInfo(target, url);
            }

            @Override
            public void onPageFinished(
                WebView view,
                String url
            ) {
                updateTabInfo(target, url);
            }
        });

        target.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title == null || title.trim().isEmpty()) return;

                for (BrowserTab tab : tabs) {
                    if (tab.view == target) {
                        tab.title = title.length() > 20
                            ? title.substring(0, 20)
                            : title;

                        tab.selectButton.setText(tab.title);
                        break;
                    }
                }
            }

            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (target == web) {
                    setTitle("Mrorgar-Q • " + progress + "%");
                }
            }

            @Override
            public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> callback,
                FileChooserParams params
            ) {
                if (uploadCallback != null) {
                    uploadCallback.onReceiveValue(null);
                }

                uploadCallback = callback;

                try {
                    Intent intent = params.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, PICK_FILE);
                } catch (Exception e) {
                    uploadCallback = null;
                    callback.onReceiveValue(null);
                    toast("بازکردن انتخاب‌گر فایل ناموفق بود");
                }

                return true;
            }
        });

        target.setDownloadListener(
            (url, userAgent, disposition, mimeType, length) ->
                enqueueDownload(url, userAgent, disposition, mimeType)
        );
    }

    private void updateTabInfo(WebView target, String url) {
        for (BrowserTab tab : tabs) {
            if (tab.view == target) {
                if (tab.title.startsWith("Tab ") ||
                    tab.title.equals("New tab")) {
                    String host = Uri.parse(url).getHost();

                    if (host != null && !host.isEmpty()) {
                        tab.title = host.length() > 20
                            ? host.substring(0, 20)
                            : host;
                    }
                }

                tab.selectButton.setText(tab.title);
                break;
            }
        }

        if (target == web) {
            address.setText(url);
        }
    }

    private void loadAddress() {
        if (web == null) return;

        String query = address.getText().toString().trim();
        if (query.isEmpty()) return;

        String url;

        if (query.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            url = query;
        } else if (query.contains(" ") || !query.contains(".")) {
            url = "https://www.google.com/search?q=" +
                Uri.encode(query);
        } else {
            url = "https://" + query;
        }

        web.loadUrl(url);

        InputMethodManager keyboard =
            (InputMethodManager) getSystemService(
                INPUT_METHOD_SERVICE
            );

        keyboard.hideSoftInputFromWindow(
            address.getWindowToken(),
            0
        );
    }

    private void toast(String message) {
        Toast.makeText(
            MainActivity.this,
            message,
            Toast.LENGTH_SHORT
        ).show();
    }

    private void doAction(String action) {
        switch (action) {
            case "Back":
                if (web != null && web.canGoBack()) web.goBack();
                else toast("صفحه قبلی وجود ندارد");
                break;
            case "Forward":
                if (web != null && web.canGoForward()) web.goForward();
                else toast("صفحه بعدی وجود ندارد");
                break;
            case "Send":
                sendContent();
                break;
            case "File":
                pickAnyFile();
                break;
            case "Copy MW":
                copyPage();
                break;
            case "IP Set":
                ipSettings();
                break;
            case "Save":
                savePage();
                break;
            case "F Screen":
                setFullscreen();
                break;
            default:
                toast("این فرمان شناخته نشد");
                break;
        }
    }

    private void sendContent() {
        if (web == null) return;

        String url = web.getUrl();
        if (url == null || url.isEmpty()) {
            toast("صفحه‌ای برای ارسال وجود ندارد");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, url);

        try {
            startActivity(Intent.createChooser(intent, "ارسال صفحه"));
        } catch (Exception e) {
            toast("برنامه‌ای برای ارسال پیدا نشد");
        }
    }

    private void pickAnyFile() {
        if (web == null) return;

        web.evaluateJavascript(
            "(function() {" +
            " var e=document.querySelector('input[type=file]');" +
            " if(e){e.click();return 'found';}" +
            " return 'notfound';" +
            "})()",
            result -> {
                if (result == null || result.contains("notfound")) {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    try {
                        startActivityForResult(
                            Intent.createChooser(intent, "انتخاب فایل"),
                            PICK_MANUAL_UPLOAD
                        );
                    } catch (Exception e) {
                        toast("انتخاب فایل ممکن نیست");
                    }
                }
            }
        );
    }

    private void enqueueDownload(String url, String userAgent,
                                 String contentDisposition,
                                 String mimeType) {
        if (url == null || url.isEmpty()) return;

        try {
            DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(url));

            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );

            if (mimeType != null && !mimeType.isEmpty()) {
                request.setMimeType(mimeType);
            }

            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }

            if (web != null && web.getUrl() != null) {
                String cookies = CookieManager.getInstance()
                    .getCookie(web.getUrl());

                if (cookies != null && !cookies.isEmpty()) {
                    request.addRequestHeader("Cookie", cookies);
                }
            }

            String fileName = URLUtil.guessFileName(
                url, contentDisposition, mimeType
            );

            request.setTitle(fileName);
            request.setDescription("دانلود توسط Mrorgar-Q");
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, fileName
            );

            DownloadManager manager =
                (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

            if (manager == null) {
                toast("مدیر دانلود اندروید در دسترس نیست");
                return;
            }

            manager.enqueue(request);
            toast("دانلود شروع شد: " + fileName);

        } catch (Exception e) {
            toast("شروع دانلود ناموفق بود: " + e.getMessage());
        }
    }

    private void multiDownloadDialog() {
        final EditText input = new EditText(this);
        input.setHint("هر نشانی در یک خط");

        new AlertDialog.Builder(this)
            .setTitle("دانلود چند فایل")
            .setMessage("پیوندهای مستقیم فایل‌ها را وارد کن.")
            .setView(input)
            .setNegativeButton("لغو", null)
            .setPositiveButton("شروع", (dialog, which) -> {
                String value = input.getText().toString().trim();

                if (value.isEmpty()) {
                    toast("پیوندی وارد نشده است");
                    return;
                }

                String[] urls = value.split("\\s+");
                int count = 0;

                for (String item : urls) {
                    String url = item.trim();

                    if (url.startsWith("https://") ||
                        url.startsWith("http://")) {
                        enqueueDownload(
                            url,
                            web != null
                                ? web.getSettings().getUserAgentString()
                                : null,
                            null,
                            null
                        );
                        count++;
                    }
                }

                toast("تعداد پیوندهای پذیرفته‌شده: " + count);
            })
            .show();
    }

    private void copyPage() {
        if (web == null) return;

        web.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML;})()",
            result -> {
                try {
                    if (result == null || "null".equals(result)) {
                        toast("محتوایی برای کپی وجود ندارد");
                        return;
                    }

                    String html = new org.json.JSONTokener(result)
                        .nextValue().toString();

                    android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);

                    if (clipboard != null) {
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Web Page HTML", html)
                        );
                        toast("کد HTML صفحه کپی شد");
                    }
                } catch (Exception e) {
                    toast("کپی صفحه ناموفق بود");
                }
            }
        );
    }

    private EditText field(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);

        if (value != null) {
            edit.setText(value);
        }

        return edit;
    }

    private void ipSettings() {
        String host = getPreferences(0)
            .getString("proxy_host", "");
        String port = getPreferences(0)
            .getString("proxy_port", "");

        EditText hostField = field("Proxy host / IP", host);
        EditText portField = field("Port", port);
        portField.setInputType(
            android.text.InputType.TYPE_CLASS_NUMBER
        );

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(8), dp(16), dp(8));
        layout.addView(hostField);
        layout.addView(portField);

        new AlertDialog.Builder(this)
            .setTitle("IP / Proxy")
            .setView(layout)
            .setNegativeButton("لغو", null)
            .setNeutralButton("قطع پروکسی", (d, w) -> disableProxy())
            .setPositiveButton("اعمال", (d, w) ->
                applyProxy(
                    hostField.getText().toString().trim(),
                    portField.getText().toString().trim()
                )
            )
            .show();
    }

    private void applyProxy(String host, String port) {
        if (host.isEmpty() || port.isEmpty()) {
            toast("آدرس و پورت را وارد کن");
            return;
        }

        try {
            int number = Integer.parseInt(port);

            if (number < 1 || number > 65535) {
                throw new IllegalArgumentException("پورت نامعتبر است");
            }

            getPreferences(0).edit()
                .putString("proxy_host", host)
                .putString("proxy_port", port)
                .apply();

            if (Build.VERSION.SDK_INT >= 28) {
                androidx.webkit.ProxyConfig config =
                    new androidx.webkit.ProxyConfig.Builder()
                        .addProxyRule(host + ":" + number)
                        .build();

                androidx.webkit.ProxyController.getInstance()
                    .setProxyOverride(
                        config,
                        getMainExecutor(),
                        () -> toast("تنظیم پروکسی اعمال شد")
                    );
            } else {
                toast("پروکسی سراسری WebView به Android 9 یا بالاتر نیاز دارد");
            }
        } catch (IllegalArgumentException e) {
            toast("آدرس یا پورت پروکسی نامعتبر است");
        } catch (Exception e) {
            toast("اعمال پروکسی ناموفق بود");
        }
    }

    private void disableProxy() {
        if (Build.VERSION.SDK_INT >= 28) {
            androidx.webkit.ProxyController.getInstance()
                .clearProxyOverride(
                    getMainExecutor(),
                    () -> toast("پروکسی پاک شد")
                );
        } else {
            toast("این نسخهٔ اندروید از این روش پشتیبانی نمی‌کند");
        }
    }

    private void dnsSettings() {
        EditText primary = field(
            "DNS اصلی",
            getPreferences(0).getString("dns_primary", "")
        );

        EditText secondary = field(
            "DNS جایگزین",
            getPreferences(0).getString("dns_secondary", "")
        );

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(8), dp(16), dp(8));
        layout.addView(primary);
        layout.addView(secondary);

        new AlertDialog.Builder(this)
            .setTitle("DNS")
            .setView(layout)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره", (d, w) -> {
                getPreferences(0).edit()
                    .putString("dns_primary",
                        primary.getText().toString().trim())
                    .putString("dns_secondary",
                        secondary.getText().toString().trim())
                    .apply();

                toast("مقادیر DNS ذخیره شدند");
                toast("ذخیره DNS به‌تنهایی DNS شبکه را تغییر نمی‌دهد");
            })
            .show();
    }

    private void tunnelSettings() {
        EditText config = field(
            "نشانی یا تنظیمات تونل",
            getPreferences(0).getString("tunnel_config", "")
        );

        new AlertDialog.Builder(this)
            .setTitle("Tunnel")
            .setView(config)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره", (d, w) -> {
                getPreferences(0).edit()
                    .putString("tunnel_config",
                        config.getText().toString().trim())
                    .apply();

                toast("تنظیمات تونل ذخیره شد؛ تونل هنوز فعال نشده است");
            })
            .show();
    }

    private void vraySettings() {
        EditText config = field(
            "نشانی یا پیکربندی V2Ray",
            getPreferences(0).getString("vray_config", "")
        );

        new AlertDialog.Builder(this)
            .setTitle("V2Ray")
            .setView(config)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره", (d, w) -> {
                getPreferences(0).edit()
                    .putString("vray_config",
                        config.getText().toString().trim())
                    .apply();

                toast("پیکربندی ذخیره شد؛ اتصال V2Ray فعال نشده است");
            })
            .show();
    }

    private void savePage() {
        if (web == null) return;

        web.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML;})()",
            result -> {
                try {
                    if (result == null || "null".equals(result)) {
                        toast("صفحه آمادهٔ ذخیره نیست");
                        return;
                    }

                    String html = new org.json.JSONTokener(result)
                        .nextValue().toString();

                    File dir = getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS
                    );

                    if (dir == null) {
                        toast("محل ذخیره در دسترس نیست");
                        return;
                    }

                    if (!dir.exists() && !dir.mkdirs()) {
                        toast("ساخت پوشهٔ ذخیره ناموفق بود");
                        return;
                    }

                    String name = "Mrorgar-page-" +
                        System.currentTimeMillis() + ".html";

                    File file = new File(dir, name);

                    try (FileOutputStream out =
                             new FileOutputStream(file)) {
                        out.write(html.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8
                        ));
                    }

                    toast("صفحه ذخیره شد: " + file.getAbsolutePath());

                } catch (Exception e) {
                    toast("ذخیرهٔ صفحه ناموفق بود");
                }
            }
        );
    }

    private void setFullscreen() {
        fullscreen = !fullscreen;

        if (fullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );

            if (addressRow != null) addressRow.setVisibility(View.GONE);
            if (tabRow != null) tabRow.setVisibility(View.GONE);
            if (top != null) top.setVisibility(View.GONE);

        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE
            );

            if (addressRow != null) addressRow.setVisibility(View.VISIBLE);
            if (tabRow != null) tabRow.setVisibility(View.VISIBLE);
            if (top != null) top.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE) {
            if (uploadCallback != null) {
                Uri[] results = null;

                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];

                        for (int i = 0; i < count; i++) {
                            results[i] =
                                data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }

                uploadCallback.onReceiveValue(results);
                uploadCallback = null;
            }
            return;
        }

        if (requestCode == PICK_MANUAL_UPLOAD) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();

                if (uri != null) {
                    toast("فایل انتخاب شد؛ برای ارسال باید آن را در فرم سایت انتخاب کنی");
                }
            }
            return;
        }

        if (requestCode == PICK_VRAY) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();

                if (uri != null) {
                    try (InputStream input = getContentResolver()
                            .openInputStream(uri)) {
                        if (input == null) {
                            toast("فایل قابل خواندن نیست");
                            return;
                        }

                        ByteArrayOutputStream output =
                            new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int length;

                        while ((length = input.read(buffer)) != -1) {
                            output.write(buffer, 0, length);
                        }

                        String config = output.toString("UTF-8");

                        getPreferences(0).edit()
                            .putString("vray_config", config)
                            .apply();

                        toast("متن فایل ذخیره شد؛ اتصال V2Ray فعال نشده است");

                    } catch (Exception e) {
                        toast("خواندن فایل ناموفق بود");
                    }
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen();
            return;
        }

        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }

        if (tabs != null && tabs.size() > 1 && activeTabIndex > 0) {
            selectTab(activeTabIndex - 1);
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (web != null && web.getUrl() != null) {
            outState.putString("current_url", web.getUrl());
        }

        if (tabs != null) {
            outState.putInt("active_tab_index", activeTabIndex);
        }
    }

    @Override
    protected void onDestroy() {
        if (uploadCallback != null) {
            uploadCallback.onReceiveValue(null);
            uploadCallback = null;
        }

        for (BrowserTab tab : tabs) {
            try {
                tab.view.stopLoading();
                tab.view.destroy();
            } catch (Exception ignored) {}
        }
        tabs.clear();

        super.onDestroy();
    }

}