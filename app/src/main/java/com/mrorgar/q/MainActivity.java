package com.mrorgar.q;

import androidx.webkit.ProxyController;
import androidx.webkit.ProxyConfig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private WebView web;
    private EditText address;
    private LinearLayout root, top, tabStrip, tabRow, addressRow;
    private FrameLayout stage;
    private Button restoreButton;

    private ValueCallback<Uri[]> uploadCallback;
    private DownloadManager downloadManager;

    private static final int PICK_FILE = 601;
    private static final int PICK_MANUAL_UPLOAD = 603;
    private static final int PICK_VRAY = 602;
    private static final int CREATE_SAVE_FILE = 604;

    private boolean fullscreen = false;
    private boolean desktopMode = false;

    private final List<BrowserTab> tabs = new ArrayList<>();
    private int activeTabIndex = -1;

    private byte[] pendingSaveBytes;
    private String pendingSaveMime = "text/html";
    private String pendingSaveName = "page.html";

    private class BrowserTab {
        WebView view;
        String title = "New tab";
        LinearLayout chip;
        Button selectButton;
        Button closeButton;
        String lastUrl = "";

        BrowserTab(WebView view) {
            this.view = view;
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private SharedPreferences prefs() {
        return getPreferences(MODE_PRIVATE);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(32, 33, 36));
        getWindow().setNavigationBarColor(Color.BLACK);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        buildUI();
        String initialUrl = "https://www.google.com";
        if (state != null) {
            String savedUrl = state.getString("current_url");
            if (savedUrl != null && !savedUrl.trim().isEmpty()) initialUrl = savedUrl;
            desktopMode = state.getBoolean("desktop_mode", false);
        } else {
            desktopMode = prefs().getBoolean("desktop_mode", false);
        }
        createTab(initialUrl);
        updateDesktopButton();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null && web.getUrl() != null) out.putString("current_url", web.getUrl());
        out.putBoolean("desktop_mode", desktopMode);
    }

    @Override
    protected void onDestroy() {
        for (BrowserTab t : tabs) {
            try { t.view.destroy(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) { setFullscreen(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    private Button makeButton(String label, float textSize) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(textSize);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(45, 46, 49)));
        return button;
    }

    private void buildUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.rgb(32, 33, 36));
        String[] names = {"Back", "Forward", "Send", "File", "Copy MW", "IP Set", "Save", "Android Desktop", "F Screen"};
        for (String name : names) {
            Button button = makeButton(name, 8.0f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            top.addView(button, params);
            button.setOnClickListener(v -> {
                if ("Android Desktop".equals(name)) toggleDesktopMode();
                else doAction(name);
            });
            if ("Save".equals(name)) {
                button.setOnLongClickListener(v -> { multiDownloadDialog(); return true; });
            }
        }
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(38)));
        addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(3), 0, dp(3), 0);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setHint("Website or search words");
        address.setPadding(dp(10), 0, dp(8), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressRow.addView(address, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button go = makeButton("Go", 10);
        go.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232, 234, 237)));
        go.setTextColor(Color.BLACK);
        addressRow.addView(go, new LinearLayout.LayoutParams(dp(48), dp(42)));
        go.setOnClickListener(v -> loadAddress());
        root.addView(addressRow, new LinearLayout.LayoutParams(-1, dp(42)));
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setBackgroundColor(Color.rgb(238, 239, 241));
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(-2, -1));
        tabRow.addView(tabScroll, new LinearLayout.LayoutParams(0, dp(36), 1));
        Button addTab = new Button(this);
        addTab.setText("+");
        addTab.setTextSize(20);
        addTab.setAllCaps(false);
        addTab.setPadding(0, 0, 0, 0);
        addTab.setMinWidth(0);
        addTab.setMinimumWidth(0);
        tabRow.addView(addTab, new LinearLayout.LayoutParams(dp(44), dp(36)));
        addTab.setOnClickListener(v -> createTab("https://www.google.com"));
        root.addView(tabRow, new LinearLayout.LayoutParams(-1, dp(36)));
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        restoreButton = new Button(this);
        restoreButton.setText("\u2197");
        restoreButton.setTextSize(18);
        restoreButton.setAllCaps(false);
        restoreButton.setVisibility(View.GONE);
        restoreButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(255, 213, 79)));
        restoreButton.setTextColor(Color.BLACK);
        FrameLayout.LayoutParams restoreParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        stage.addView(restoreButton, restoreParams);
        restoreButton.setOnClickListener(v -> { if (fullscreen) setFullscreen(); });
        setContentView(root);
        address.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadAddress();
                return true;
            }
            return false;
        });
    }

    private void createTab(String url) {
        WebView view = new WebView(this);
        BrowserTab tab = new BrowserTab(view);
        tab.lastUrl = url;
        tabs.add(tab);
        view.setVisibility(View.GONE);
        stage.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(3), 0, dp(2), 0);
        tab.chip = chip;
        Button select = makeButton("Tab " + tabs.size(), 10);
        select.setTextColor(Color.BLACK);
        select.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
        tab.selectButton = select;
        chip.addView(select, new LinearLayout.LayoutParams(-2, dp(32)));
        select.setOnClickListener(v -> selectTab(tabs.indexOf(tab)));
        Button close = new Button(this);
        close.setText("\u00D7");
        close.setTextSize(15);
        close.setAllCaps(false);
        close.setPadding(0, 0, 0, 0);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setMinHeight(0);
        close.setMinimumHeight(0);
        tab.closeButton = close;
        chip.addView(close, new LinearLayout.LayoutParams(dp(27), dp(32)));
        close.setOnClickListener(v -> closeTab(tabs.indexOf(tab)));
        tabStrip.addView(chip, new LinearLayout.LayoutParams(-2, dp(36)));
        configureWeb(view);
        selectTab(tabs.size() - 1);
        view.loadUrl(url);
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeTabIndex = index;
        for (int i = 0; i < tabs.size(); i++) {
            BrowserTab tab = tabs.get(i);
            boolean selected = i == index;
            tab.view.setVisibility(selected ? View.VISIBLE : View.GONE);
            int color = selected ? Color.WHITE : Color.rgb(218, 220, 224);
            tab.chip.setBackgroundColor(color);
            tab.selectButton.setBackgroundTintList(ColorStateList.valueOf(color));
            tab.closeButton.setBackgroundTintList(ColorStateList.valueOf(color));
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
            only.title = "New tab";
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
        if (index < activeTabIndex) activeTabIndex--;
        else if (index == activeTabIndex) activeTabIndex = Math.min(index, tabs.size() - 1);
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
        applyUserAgent(target);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cookies.setAcceptThirdPartyCookies(target, true);
        target.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https");
            }
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) { updateTabInfo(target, url); }
            @Override
            public void onPageFinished(WebView view, String url) { updateTabInfo(target, url); }
        });
        target.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title == null || title.trim().isEmpty()) return;
                for (BrowserTab tab : tabs) {
                    if (tab.view == target) {
                        tab.title = title.length() > 20 ? title.substring(0, 20) : title;
                        tab.selectButton.setText(tab.title);
                        break;
                    }
                }
            }
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (target == web) setTitle("Mrorgar-Q \u2022 " + progress + "%");
            }
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
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
        target.setDownloadListener((url, userAgent, disposition, mimeType, length) -> enqueueDownload(url, userAgent, disposition, mimeType));
    }

    private void applyUserAgent(WebView target) {
        WebSettings settings = target.getSettings();
        String defaultUA = WebSettings.getDefaultUserAgent(this);
        if (desktopMode) {
            String desktopUA = defaultUA
                .replace(" (Linux; Android 10; ", " (X11; Linux x86_64) ")
                .replace(" (Linux; Android 11; ", " (X11; Linux x86_64) ")
                .replace(" (Linux; Android 12; ", " (X11; Linux x86_64) ")
                .replace(" (Linux; Android 13; ", " (X11; Linux x86_64) ")
                .replace(" (Linux; Android 14; ", " (X11; Linux x86_64) ")
                .replace(" Mobile Safari/", " Safari/");
            settings.setUserAgentString(desktopUA);
        } else {
            settings.setUserAgentString(defaultUA);
        }
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
    }

    private void toggleDesktopMode() {
        desktopMode = !desktopMode;
        prefs().edit().putBoolean("desktop_mode", desktopMode).apply();
        updateDesktopButton();
        if (web != null) {
            String url = web.getUrl();
            applyUserAgent(web);
            if (url != null && !url.isEmpty()) web.reload();
        }
        toast(desktopMode ? "حالت دسکتاپ فعال شد" : "حالت موبایل فعال شد");
    }

    private void updateDesktopButton() {
        if (top == null) return;
        for (int i = 0; i < top.getChildCount(); i++) {
            View child = top.getChildAt(i);
            if (child instanceof Button) {
                Button button = (Button) child;
                String t = button.getText().toString();
                if ("Android Desktop".equals(t) || "Desktop: ON".equals(t)) {
                    button.setText(desktopMode ? "Desktop: ON" : "Android Desktop");
                    button.setBackgroundTintList(ColorStateList.valueOf(desktopMode ? Color.rgb(46, 125, 50) : Color.rgb(45, 46, 49)));
                    return;
                }
            }
        }
    }

    private void updateTabInfo(WebView target, String url) {
        if (url == null) return;
        for (BrowserTab tab : tabs) {
            if (tab.view == target) {
                tab.lastUrl = url;
                if (tab.title.equals("New tab") || tab.title.startsWith("Tab ")) {
                    String host = Uri.parse(url).getHost();
                    if (host != null && !host.isEmpty()) tab.title = host.length() > 20 ? host.substring(0, 20) : host;
                }
                tab.selectButton.setText(tab.title);
                break;
            }
        }
        if (target == web) address.setText(url);
    }

    private void loadAddress() {
        if (web == null) return;
        String query = address.getText().toString().trim();
        if (query.isEmpty()) return;
        String url;
        if (query.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = query;
        else if (query.contains(" ") || !query.contains(".")) url = "https://www.google.com/search?q=" + Uri.encode(query);
        else url = "https://" + query;
        web.loadUrl(url);
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void doAction(String action) {
        if (web == null) return;
        switch (action) {
            case "Back": if (web.canGoBack()) web.goBack(); break;
            case "Forward": if (web.canGoForward()) web.goForward(); break;
            case "Send": sendContent(); break;
            case "File": pickAnyFile(); break;
            case "Copy MW": copyPage(); break;
            case "IP Set": ipSettings(); break;
            case "Save": savePage(); break;
            case "F Screen": setFullscreen(); break;
        }
    }

    private void sendContent() {
        if (web == null) return;
        String url = web.getUrl();
        if (url == null || url.isEmpty()) { toast("صفحه‌ای برای ارسال باز نیست"); return; }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, url);
        try { startActivity(Intent.createChooser(intent, "ارسال لینک")); }
        catch (Exception e) { toast("ارسال لینک انجام نشد"); }
    }

    private void pickAnyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, PICK_MANUAL_UPLOAD); }
        catch (Exception e) { toast("انتخاب فایل امکان‌پذیر نیست"); }
    }

    private void enqueueDownload(String url, String userAgent, String disposition, String mimeType) {
        if (url == null || url.trim().isEmpty()) { toast("آدرس دانلود معتبر نیست"); return; }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                toast("این نوع آدرس برای دانلود پشتیبانی نمی‌شود");
                return;
            }
            String fileName = URLUtil.guessFileName(url, disposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle(fileName);
            request.setDescription("Mrorgar-Q Downloader");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            if (mimeType != null && !mimeType.trim().isEmpty()) request.setMimeType(mimeType);
            if (userAgent != null && !userAgent.trim().isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            downloadManager.enqueue(request);
            toast("دانلود شروع شد: " + fileName);
        } catch (Exception e) { toast("شروع دانلود ناموفق بود"); }
    }

    private void multiDownloadDialog() {
        if (web == null) return;
        String[] choices = {"دانلود فایل از آدرس", "ذخیره صفحه فعلی", "کپی آدرس صفحه"};
        new AlertDialog.Builder(this)
            .setTitle("ابزار دانلود و ذخیره")
            .setItems(choices, (dialog, which) -> {
                if (which == 0) {
                    EditText input = field("آدرس مستقیم فایل", "https://example.com/file.zip");
                    new AlertDialog.Builder(this)
                        .setTitle("دانلود فایل")
                        .setView(input)
                        .setNegativeButton("لغو", null)
                        .setPositiveButton("دانلود", (d, w) -> {
                            String url = input.getText().toString().trim();
                            if (!url.isEmpty()) enqueueDownload(url, web.getSettings().getUserAgentString(), null, null);
                        }).show();
                } else if (which == 1) savePage();
                else if (which == 2) copyPage();
            }).setNegativeButton("بستن", null).show();
    }

    private void copyPage() {
        if (web == null) return;
        String url = web.getUrl();
        if (url == null || url.isEmpty()) { toast("صفحه‌ای باز نیست"); return; }
        web.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()", result -> {
            try {
                Object parsed = new JSONTokener(result).nextValue();
                String text = parsed == null ? "" : parsed.toString();
                if (text.trim().isEmpty()) text = url;
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Mrorgar-Q", text));
                    toast("محتوای متنی صفحه کپی شد");
                }
            } catch (Exception e) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Mrorgar-Q", url));
                toast("آدرس صفحه کپی شد");
            }
        });
    }

    private EditText field(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextSize(15);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (value != null) { input.setText(value); input.setSelection(input.getText().length()); }
        return input;
    }

    private void ipSettings() {
        String[] options = {"Proxy — پروکسی", "DNS — تنظیم DNS", "Tunnel — تونل", "V2Ray — تنظیمات V2Ray"};
        new AlertDialog.Builder(this)
            .setTitle("IP Set")
            .setItems(options, (dialog, which) -> {
                if (which == 0) applyProxyDialog();
                else if (which == 1) dnsSettings();
                else if (which == 2) tunnelSettings();
                else if (which == 3) vraySettings();
            }).setNegativeButton("بستن", null).show();
    }

    private void applyProxyDialog() {
        EditText host = field("آدرس یا IP پروکسی", prefs().getString("proxy_host", ""));
        EditText port = field("پورت پروکسی", prefs().getString("proxy_port", ""));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
        panel.addView(host);
        panel.addView(port);
        new AlertDialog.Builder(this)
            .setTitle("تنظیم Proxy")
            .setView(panel)
            .setNeutralButton("خاموش‌کردن", (dialog, which) -> disableProxy())
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره و اعمال", (dialog, which) -> applyProxy(host.getText().toString().trim(), port.getText().toString().trim()))
            .show();
    }

    private void applyProxy(String host, String port) {
        if (host == null || host.trim().isEmpty() || port == null || port.trim().isEmpty()) { toast("آدرس و پورت را وارد کن"); return; }
        final int portNumber;
        try {
            portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) { toast("پورت باید عددی بین 1 و 65535 باشد"); return; }
        if (Build.VERSION.SDK_INT < 28) { toast("اعمال پروکسی داخلی به Android 9 یا بالاتر نیاز دارد"); return; }
        try {
            ProxyConfig config = new ProxyConfig.Builder().addProxyRule(host + ":" + portNumber).build();
            ProxyController.getInstance().setProxyOverride(config, Runnable::run, () -> runOnUiThread(() -> {
                prefs().edit().putString("proxy_host", host).putString("proxy_port", String.valueOf(portNumber)).putBoolean("proxy_enabled", true).apply();
                toast("تنظیم پروکسی به WebView ارسال شد");
                if (web != null) web.reload();
            }));
        } catch (Exception e) { toast("اعمال پروکسی ناموفق بود"); }
    }

    private void disableProxy() {
        if (Build.VERSION.SDK_INT < 28) { toast("خاموش‌کردن پروکسی داخلی به Android 9 یا بالاتر نیاز دارد"); return; }
        try {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> runOnUiThread(() -> {
                prefs().edit().putBoolean("proxy_enabled", false).apply();
                toast("پروکسی WebView خاموش شد");
                if (web != null) web.reload();
            }));
        } catch (Exception e) { toast("خاموش‌کردن پروکسی ناموفق بود"); }
    }

    private void dnsSettings() {
        EditText primary = field("DNS اصلی (IP)", prefs().getString("dns_primary", ""));
        EditText secondary = field("DNS دوم (IP)", prefs().getString("dns_secondary", ""));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
        panel.addView(primary);
        panel.addView(secondary);
        new AlertDialog.Builder(this)
            .setTitle("تنظیم DNS")
            .setView(panel)
            .setNeutralButton("پاک‌کردن", (dialog, which) -> {
                prefs().edit().remove("dns_primary").remove("dns_secondary").apply();
                toast("تنظیمات ذخیره‌شده DNS پاک شد");
            })
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره", (dialog, which) -> {
                String p1 = primary.getText().toString().trim();
                String p2 = secondary.getText().toString().trim();
                if (p1.isEmpty() && p2.isEmpty()) { toast("حداقل یک آدرس DNS وارد کن"); return; }
                prefs().edit().putString("dns_primary", p1).putString("dns_secondary", p2).apply();
                toast("DNS ذخیره شد؛ هنوز روی اتصال اعمال نشده است");
            }).show();
    }

    private void tunnelSettings() {
        EditText server = field("آدرس سرور تونل", prefs().getString("tunnel_server", ""));
        EditText port = field("پورت", prefs().getString("tunnel_port", ""));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText type = field("نوع تونل (مثلاً SOCKS5)", prefs().getString("tunnel_type", "SOCKS5"));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
        panel.addView(server);
        panel.addView(port);
        panel.addView(type);
        new AlertDialog.Builder(this)
            .setTitle("تنظیم Tunnel")
            .setView(panel)
            .setNeutralButton("پاک‌کردن", (dialog, which) -> {
                prefs().edit().remove("tunnel_server").remove("tunnel_port").remove("tunnel_type").apply();
                toast("تنظیمات تونل پاک شد");
            })
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره", (dialog, which) -> {
                String h = server.getText().toString().trim();
                String pt = port.getText().toString().trim();
                String tp = type.getText().toString().trim();
                if (h.isEmpty() || pt.isEmpty()) { toast("آدرس و پورت سرور را وارد کن"); return; }
                try {
                    int pn = Integer.parseInt(pt);
                    if (pn < 1 || pn > 65535) throw new NumberFormatException();
                } catch (NumberFormatException e) { toast("پورت معتبر نیست"); return; }
                prefs().edit().putString("tunnel_server", h).putString("tunnel_port", pt).putString("tunnel_type", tp).apply();
                toast("تنظیمات ذخیره شد؛ تونل هنوز متصل نیست");
            }).show();
    }

    private void vraySettings() {
        String[] options = {"واردکردن فایل پیکربندی", "واردکردن لینک V2Ray", "مشاهده تنظیمات ذخیره‌شده", "پاک‌کردن تنظیمات"};
        new AlertDialog.Builder(this)
            .setTitle("V2Ray")
            .setItems(options, (dialog, which) -> {
                if (which == 0) pickVrayFile();
                else if (which == 1) enterVrayLink();
                else if (which == 2) showVrayConfig();
                else if (which == 3) {
                    new AlertDialog.Builder(this)
                        .setTitle("پاک‌کردن V2Ray")
                        .setMessage("تنظیمات ذخیره‌شده پاک شود؟")
                        .setNegativeButton("لغو", null)
                        .setPositiveButton("پاک‌کردن", (d, w) -> {
                            prefs().edit().remove("vray_config").remove("vray_link").apply();
                            toast("تنظیمات V2Ray پاک شد");
                        }).show();
                }
            }).setNegativeButton("بستن", null).show();
    }

    private void pickVrayFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, PICK_VRAY); }
        catch (Exception e) { toast("انتخاب فایل پیکربندی ممکن نشد"); }
    }

    private void enterVrayLink() {
        EditText input = field("لینک vmess://، vless:// یا مشابه", prefs().getString("vray_link", ""));
        new AlertDialog.Builder(this)
            .setTitle("واردکردن لینک V2Ray")
            .setView(input)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره", (dialog, which) -> {
                String link = input.getText().toString().trim();
                if (!(link.startsWith("vmess://") || link.startsWith("vless://") || link.startsWith("trojan://") || link.startsWith("ss://"))) {
                    toast("قالب لینک شناخته‌شده نیست");
                    return;
                }
                prefs().edit().putString("vray_link", link).apply();
                toast("لینک ذخیره شد؛ اتصال V2Ray فعال نشده است");
            }).show();
    }

    private void showVrayConfig() {
        String config = prefs().getString("vray_config", "");
        String link = prefs().getString("vray_link", "");
        String content;
        if (!config.isEmpty()) content = "فایل پیکربندی وارد شده است.\n\n" + config;
        else if (!link.isEmpty()) content = "لینک ذخیره‌شده:\n" + link;
        else content = "هنوز تنظیماتی وارد نشده است.";
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(content);
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(12), dp(16), dp(12));
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("تنظیمات ذخیره‌شده").setView(scroll).setPositiveButton("باشه", null).show();
    }

    private void savePage() {
        if (web == null) return;
        String url = web.getUrl();
        if (url == null || url.isEmpty()) { toast("صفحه‌ای برای ذخیره وجود ندارد"); return; }
        web.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()", result -> {
            try {
                Object parsed = new JSONTokener(result).nextValue();
                String html = parsed == null ? "" : parsed.toString();
                if (html.trim().isEmpty()) { toast("محتوای صفحه قابل ذخیره نیست"); return; }
                pendingSaveBytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                pendingSaveMime = "text/html";
                pendingSaveName = "page.html";
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/html");
                intent.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
                startActivityForResult(intent, CREATE_SAVE_FILE);
            } catch (Exception e) { toast("آماده‌سازی صفحه برای ذخیره ناموفق بود"); }
        });
    }

    private void setFullscreen() {
        fullscreen = !fullscreen;
        if (fullscreen) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            top.setVisibility(View.GONE);
            addressRow.setVisibility(View.GONE);
            tabRow.setVisibility(View.GONE);
            restoreButton.setVisibility(View.VISIBLE);
            toast("حالت تمام‌صفحه فعال شد");
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            top.setVisibility(View.VISIBLE);
            addressRow.setVisibility(View.VISIBLE);
            tabRow.setVisibility(View.VISIBLE);
            restoreButton.setVisibility(View.GONE);
            toast("حالت تمام‌صفحه خاموش شد");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE) {
            if (uploadCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            uploadCallback.onReceiveValue(results);
            uploadCallback = null;
        } else if (requestCode == PICK_MANUAL_UPLOAD) {
            if (resultCode == RESULT_OK && data != null) {
                int count = 1;
                if (data.getClipData() != null) count = data.getClipData().getItemCount();
                toast(count + " فایل انتخاب شد");
            }
        } else if (requestCode == PICK_VRAY) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    String txt = readText(data.getData());
                    if (txt == null || txt.trim().isEmpty()) toast("فایل خالی است");
                    else {
                        if (txt.length() > 20000) txt = txt.substring(0, 20000);
                        prefs().edit().putString("vray_config", txt).apply();
                        toast("فایل پیکربندی ذخیره شد؛ اتصال فعال نشده است");
                    }
                } catch (Exception e) { toast("خواندن فایل ناموفق بود"); }
            }
        } else if (requestCode == CREATE_SAVE_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                if (pendingSaveBytes == null) { toast("داده‌ای برای ذخیره نیست"); return; }
                try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    if (os == null) throw new Exception("stream null");
                    os.write(pendingSaveBytes);
                    os.flush();
                    toast("صفحه ذخیره شد");
                } catch (Exception e) { toast("ذخیره فایل ناموفق بود"); }
                pendingSaveBytes = null;
            }
        }
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int r;
            int total = 0;
            while ((r = is.read(buf)) != -1) {
                total += r;
                if (total > 100000) break;
                bos.write(buf, 0, r);
            }
            return bos.toString("UTF-8");
        }
    }
}