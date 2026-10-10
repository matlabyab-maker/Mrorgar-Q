package com.mrorgar.q;

import androidx.webkit.ProxyController;
import androidx.webkit.ProxyConfig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.database.Cursor;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;
import org.json.JSONObject;

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
    private Button copyMwButton;
    private boolean copyMwArmed = false;
    private ValueCallback<Uri[]> uploadCallback;
    private DownloadManager downloadManager;
    private static final int PICK_FILE = 601;
    private static final int PICK_MANUAL_UPLOAD = 603;
    private static final int PICK_VRAY = 602;
    private static final int CREATE_SAVE_FILE = 604;
    private static final int DNS_VPN_REQUEST = 605;
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
        Button selectButton, closeButton;
        String lastUrl = "";
        BrowserTab(WebView view) { this.view = view; }
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private SharedPreferences prefs() { return getPreferences(MODE_PRIVATE); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(32,33,36));
        getWindow().setNavigationBarColor(Color.BLACK);
        downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        buildUI();
        String initialUrl = "https://www.google.com";
        if (state != null) {
            String savedUrl = state.getString("current_url");
            if (savedUrl != null && !savedUrl.trim().isEmpty()) initialUrl = savedUrl;
            desktopMode = state.getBoolean("desktop_mode", false);
        } else desktopMode = prefs().getBoolean("desktop_mode", false);
        createTab(initialUrl);
        updateDesktopButton();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null && web.getUrl() != null) out.putString("current_url", web.getUrl());
        out.putBoolean("desktop_mode", desktopMode);
    }

    @Override protected void onDestroy() {
        if (uploadCallback != null) { uploadCallback.onReceiveValue(null); uploadCallback = null; }
        for (BrowserTab t : tabs) { try { t.view.destroy(); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (fullscreen) { setFullscreen(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    private Button makeButton(String label, float textSize) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(textSize); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setPadding(dp(2),0,dp(2),0); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(45,46,49)));
        return b;
    }

    private void buildUI() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackgroundColor(Color.rgb(32,33,36));
        String[] names = {"Back","Forward","Send","File","Copy MW","IP Set","Save","Android Desktop","F Screen","Setup"};
        // Same single row, with buttons about 5 mm taller and distinct colors.
        int[][] buttonColors = {
            {69,90,100}, {69,90,100}, {46,125,50}, {21,101,192},
            {123,31,162}, {230,126,34}, {0,121,107}, {0,137,123},
            {63,81,181}, {96,125,139}
        };
        int colorIndex = 0;
        for (String name : names) {
            Button b = makeButton(name,9f);
            b.setPadding(dp(1),0,dp(1),0);
            b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(buttonColors[colorIndex][0],buttonColors[colorIndex][1],buttonColors[colorIndex][2])));
            // All top-row buttons use exactly the same width and height.
            top.addView(b,new LinearLayout.LayoutParams(0,dp(59),1f));
            if ("Copy MW".equals(name)) copyMwButton = b;
            b.setOnClickListener(v -> { if ("Android Desktop".equals(name)) toggleDesktopMode(); else if ("Save".equals(name)) multiDownloadDialog(); else doAction(name); });
            colorIndex++;
        }
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(59)));
        addressRow = new LinearLayout(this); addressRow.setOrientation(LinearLayout.HORIZONTAL); addressRow.setGravity(Gravity.CENTER_VERTICAL); addressRow.setPadding(dp(3),0,dp(3),0);
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(14); address.setHint("Website or search words"); address.setPadding(dp(10),0,dp(8),0); address.setImeOptions(EditorInfo.IME_ACTION_GO); address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        addressRow.addView(address,new LinearLayout.LayoutParams(0,dp(42),1));
        Button go = makeButton("Go",10); go.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232,234,237))); go.setTextColor(Color.BLACK);
        addressRow.addView(go,new LinearLayout.LayoutParams(dp(48),dp(42))); go.setOnClickListener(v -> loadAddress());
        root.addView(addressRow,new LinearLayout.LayoutParams(-1,dp(42)));
        tabRow = new LinearLayout(this); tabRow.setOrientation(LinearLayout.HORIZONTAL); tabRow.setGravity(Gravity.CENTER_VERTICAL); tabRow.setBackgroundColor(Color.rgb(238,239,241));
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this); tabStrip.setOrientation(LinearLayout.HORIZONTAL); scroll.addView(tabStrip,new HorizontalScrollView.LayoutParams(-2,-1));
        tabRow.addView(scroll,new LinearLayout.LayoutParams(0,dp(36),1));
        Button add = new Button(this); add.setText("+"); add.setTextSize(20); add.setAllCaps(false); add.setPadding(0,0,0,0); add.setMinWidth(0); add.setMinimumWidth(0);
        tabRow.addView(add,new LinearLayout.LayoutParams(dp(44),dp(36))); add.setOnClickListener(v -> createTab("https://www.google.com"));
        root.addView(tabRow,new LinearLayout.LayoutParams(-1,dp(36)));
        stage = new FrameLayout(this); root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        restoreButton = new Button(this); restoreButton.setText("↗"); restoreButton.setTextSize(18); restoreButton.setAllCaps(false); restoreButton.setVisibility(View.GONE); restoreButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(255,213,79))); restoreButton.setTextColor(Color.BLACK);
        stage.addView(restoreButton,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.CENTER_VERTICAL));
        restoreButton.setOnClickListener(v -> { if (fullscreen) setFullscreen(); });
        setContentView(root);
        address.setOnEditorActionListener((v,id,event) -> { if (id==EditorInfo.IME_ACTION_GO||id==EditorInfo.IME_ACTION_DONE||id==EditorInfo.IME_ACTION_SEARCH) { loadAddress(); return true; } return false; });
    }

    private void createTab(String url) {
        WebView view = new WebView(this); BrowserTab tab = new BrowserTab(view); tab.lastUrl = url; tabs.add(tab); view.setVisibility(View.GONE);
        stage.addView(view,0,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout chip = new LinearLayout(this); chip.setOrientation(LinearLayout.HORIZONTAL); chip.setGravity(Gravity.CENTER_VERTICAL); chip.setPadding(dp(3),0,dp(2),0); tab.chip = chip;
        Button select = makeButton("Tab "+tabs.size(),10); select.setTextColor(Color.BLACK); select.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE)); tab.selectButton=select;
        chip.addView(select,new LinearLayout.LayoutParams(-2,dp(32))); select.setOnClickListener(v -> selectTab(tabs.indexOf(tab)));
        Button close = new Button(this); close.setText("×"); close.setTextSize(15); close.setAllCaps(false); close.setPadding(0,0,0,0); close.setMinWidth(0); close.setMinimumWidth(0); close.setMinHeight(0); close.setMinimumHeight(0); tab.closeButton=close;
        chip.addView(close,new LinearLayout.LayoutParams(dp(27),dp(32))); close.setOnClickListener(v -> closeTab(tabs.indexOf(tab)));
        tabStrip.addView(chip,new LinearLayout.LayoutParams(-2,dp(36)));
        configureWeb(view);
        view.setOnTouchListener((v, event) -> {
            if (copyMwArmed && event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.postDelayed(() -> handleCopyMWSelection(), 120);
            }
            return false;
        });
        selectTab(tabs.size()-1); view.loadUrl(url);
    }

    private void selectTab(int index) {
        if (index<0||index>=tabs.size()) return; activeTabIndex=index;
        for (int i=0;i<tabs.size();i++) { BrowserTab t=tabs.get(i); boolean selected=i==index; t.view.setVisibility(selected?View.VISIBLE:View.GONE); int c=selected?Color.WHITE:Color.rgb(218,220,224); t.chip.setBackgroundColor(c); t.selectButton.setBackgroundTintList(ColorStateList.valueOf(c)); t.closeButton.setBackgroundTintList(ColorStateList.valueOf(c)); t.selectButton.setText(t.title); }
        web=tabs.get(index).view; String u=web.getUrl(); address.setText(u==null?"":u);
    }

    private void closeTab(int index) {
        if (index<0||index>=tabs.size()) return;
        if (tabs.size()==1) { BrowserTab t=tabs.get(0); t.view.stopLoading(); t.title="New tab"; t.selectButton.setText(t.title); t.view.loadUrl("https://www.google.com"); selectTab(0); return; }
        BrowserTab removed=tabs.remove(index); stage.removeView(removed.view); tabStrip.removeView(removed.chip); removed.view.stopLoading(); removed.view.destroy();
        if (index<activeTabIndex) activeTabIndex--; else if (index==activeTabIndex) activeTabIndex=Math.min(index,tabs.size()-1); selectTab(activeTabIndex);
    }

    private void configureWeb(WebView target) {
        WebSettings s=target.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true); s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setLoadsImagesAutomatically(true); s.setAllowFileAccess(true); s.setAllowContentAccess(true); s.setMediaPlaybackRequiresUserGesture(false); applyUserAgent(target);
        CookieManager cm=CookieManager.getInstance(); cm.setAcceptCookie(true); if (Build.VERSION.SDK_INT>=21) cm.setAcceptThirdPartyCookies(target,true);
        target.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { String scheme=r.getUrl().getScheme(); return scheme!=null&&!scheme.equalsIgnoreCase("http")&&!scheme.equalsIgnoreCase("https"); }
            @Override public void onPageStarted(WebView v,String url,Bitmap icon) { updateTabInfo(target,url); }
            @Override public void onPageFinished(WebView v,String url) { updateTabInfo(target,url); }
        });
        target.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView v,String title) { if (title==null||title.trim().isEmpty()) return; for (BrowserTab t:tabs) if (t.view==target) { t.title=title.length()>20?title.substring(0,20):title; t.selectButton.setText(t.title); break; } }
            @Override public void onProgressChanged(WebView v,int progress) { if (target==web) setTitle("Mrorgar-Q • "+progress+"%"); }
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if (uploadCallback!=null) { uploadCallback.onReceiveValue(null); uploadCallback=null; }
                uploadCallback=callback;
                try { Intent intent=params.createIntent(); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); startActivityForResult(intent,PICK_FILE); }
                catch (Exception e) { uploadCallback=null; callback.onReceiveValue(null); toast("بازکردن انتخاب‌گر فایل ناموفق بود"); }
                return true;
            }
        });
        target.setDownloadListener((url,ua,disposition,mime,length) -> enqueueDownload(url,ua,disposition,mime));
    }

    private void applyUserAgent(WebView target) {
        String ua=WebSettings.getDefaultUserAgent(this);
        if (desktopMode) {
            String chrome="Chrome/"; int start=ua.indexOf(chrome); String version="131.0.0.0";
            if (start>=0) { int end=ua.indexOf(' ',start); if (end>start) version=ua.substring(start+chrome.length(),end); }
            ua="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "+chrome+version+" Safari/537.36";
        }
        target.getSettings().setUserAgentString(ua); target.getSettings().setUseWideViewPort(true); target.getSettings().setLoadWithOverviewMode(true);
    }

    private void toggleDesktopMode() {
        desktopMode=!desktopMode; prefs().edit().putBoolean("desktop_mode",desktopMode).apply(); updateDesktopButton();
        if (web!=null) { String u=web.getUrl(); applyUserAgent(web); if (u!=null&&!u.isEmpty()) web.reload(); }
        toast(desktopMode?"حالت دسکتاپ فعال شد":"حالت موبایل فعال شد");
    }

    private void updateDesktopButton() {
        if (top==null) return;
        for (int i=0;i<top.getChildCount();i++) { View v=top.getChildAt(i); if (v instanceof Button) { Button b=(Button)v; String t=b.getText().toString(); if ("Android Desktop".equals(t)||"Desktop: ON".equals(t)) { b.setText(desktopMode?"Desktop: ON":"Android Desktop"); b.setBackgroundTintList(ColorStateList.valueOf(desktopMode?Color.rgb(46,125,50):Color.rgb(45,46,49))); return; } } }
    }

    private void updateTabInfo(WebView target,String url) {
        if (url==null) return;
        for (BrowserTab t:tabs) if (t.view==target) { t.lastUrl=url; if (t.title.equals("New tab")||t.title.startsWith("Tab ")) { String host=Uri.parse(url).getHost(); if (host!=null&&!host.isEmpty()) t.title=host.length()>20?host.substring(0,20):host; } t.selectButton.setText(t.title); break; }
        if (target==web) address.setText(url);
    }

    private void loadAddress() {
        if (web==null) return; String q=address.getText().toString().trim(); if (q.isEmpty()) return; String url;
        if (q.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url=q; else if (q.contains(" ")||!q.contains(".")) url="https://www.google.com/search?q="+Uri.encode(q); else url="https://"+q;
        web.loadUrl(url); InputMethodManager k=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE); if (k!=null) k.hideSoftInputFromWindow(address.getWindowToken(),0);
    }

    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }

    private void doAction(String action) {
        if (web==null) return;
        switch(action) { case "Back": if(web.canGoBack())web.goBack(); break; case "Forward":if(web.canGoForward())web.goForward();break; case "Send":sendContent();break; case "File":pickAnyFile();break; case "Copy MW":armCopyMW();break; case "IP Set":ipSettings();break; case "Save":savePage();break; case "F Screen":setFullscreen();break; case "Setup":setupPanel();break; }
    }

    // Send uses the current page's active form or send button. It never opens Android's external Share sheet.
    private void sendContent() {
        if (web==null) return;
        String js="(function(){try{"+
            "var a=document.activeElement;"+
            "function vis(e){return !!(e&&(e.offsetWidth||e.offsetHeight||e.getClientRects().length))&&!e.disabled;}"+
            "function click(e){if(vis(e)){e.click();return true;}return false;}"+
            "var f=a&&(a.form||(a.closest&&a.closest('form')));"+
            "if(f){var sb=f.querySelector('button[type=submit],input[type=submit]');if(click(sb))return 'submitted';if(f.requestSubmit){f.requestSubmit();return 'submitted';}f.submit();return 'submitted';}"+
            "var ss=['button[aria-label*=send i]','button[title*=send i]','[role=button][aria-label*=send i]','button[data-testid*=send i]','button[aria-label*=ارسال]','button[title*=ارسال]','[role=button][aria-label*=ارسال]','button[type=submit]','input[type=submit]'];"+
            "for(var i=0;i<ss.length;i++){var es=document.querySelectorAll(ss[i]);for(var j=0;j<es.length;j++)if(click(es[j]))return 'submitted';}"+
            "var bs=document.querySelectorAll('button,[role=button],input[type=button],input[type=submit]');"+
            "for(var k=0;k<bs.length;k++){var e=bs[k],tx=(e.innerText||e.value||e.getAttribute('aria-label')||e.title||'').trim().toLowerCase();if(vis(e)&&/^(send|submit|ارسال|فرستادن|ارسال پیام)$/.test(tx)){e.click();return 'submitted';}}"+
            "if(a&&(a.isContentEditable||/^(TEXTAREA|INPUT)$/.test(a.tagName))){a.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',bubbles:true}));a.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',bubbles:true}));return 'enter';}"+
            "return 'notfound';}catch(e){return 'error';}})()";
        web.evaluateJavascript(js,result -> {
            if (result==null) { toast("ارسال در این صفحه تشخیص داده نشد"); return; }
            if (result.contains("submitted")||result.contains("enter")) toast("درخواست ارسال به صفحه داده شد");
            else if (result.contains("notfound")) toast("متن را در کادر سایت انتخاب کن؛ دکمه ارسال سایت پیدا نشد");
            else toast("ارسال در این صفحه انجام نشد");
        });
    }

    // Opens the upload input belonging to the current website, so onShowFileChooser can return the file to that site.
    private void pickAnyFile() {
        if (web==null) return;
        String js="(function(){try{var a=Array.from(document.querySelectorAll('input[type=file]'));if(!a.length)return 'no_input';var e=a.find(function(x){return x.offsetWidth||x.offsetHeight||x.getClientRects().length;})||a[0];e.click();return 'clicked';}catch(e){return 'error';}})()";
        web.evaluateJavascript(js,result -> {
            if (result==null||result.contains("no_input")) toast("این صفحه ورودی آپلود فایل ندارد؛ ابتدا فرم آپلود سایت را باز کن");
            else if (result.contains("error")) toast("بازکردن فرم آپلود سایت ناموفق بود");
            else if (result.contains("clicked")) toast("درخواست انتخاب فایل به فرم سایت داده شد");
            // The actual file picker is launched by WebChromeClient.onShowFileChooser.
        });
    }

    private void enqueueDownload(String url,String userAgent,String disposition,String mimeType) {
        if (url==null||url.trim().isEmpty()) { toast("آدرس دانلود معتبر نیست"); return; }
        try {
            Uri uri=Uri.parse(url); String scheme=uri.getScheme();
            if (scheme==null||!(scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https"))) { toast("این نوع آدرس برای دانلود پشتیبانی نمی‌شود"); return; }
            String name=URLUtil.guessFileName(url,disposition,mimeType); DownloadManager.Request r=new DownloadManager.Request(uri); r.setTitle(name); r.setDescription("Mrorgar-Q Downloader"); r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); r.setAllowedOverMetered(true); r.setAllowedOverRoaming(true);
            if (mimeType!=null&&!mimeType.trim().isEmpty()) r.setMimeType(mimeType); if (userAgent!=null&&!userAgent.trim().isEmpty()) r.addRequestHeader("User-Agent",userAgent); String cookie=CookieManager.getInstance().getCookie(url); if(cookie!=null&&!cookie.isEmpty())r.addRequestHeader("Cookie",cookie); r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name); long id=downloadManager.enqueue(r); toast("دانلود شروع شد: "+name); showDownloadManager();
        } catch(Exception e) { toast("شروع دانلود ناموفق بود"); }
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "نامشخص";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L*1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", bytes/1024.0);
        if (bytes < 1024L*1024*1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes/(1024.0*1024));
        return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes/(1024.0*1024*1024));
    }

    private String downloadStatus(int status) {
        if (status==DownloadManager.STATUS_PENDING) return "در انتظار شروع";
        if (status==DownloadManager.STATUS_RUNNING) return "در حال دانلود";
        if (status==DownloadManager.STATUS_PAUSED) return "مکث‌شده توسط سیستم/شبکه";
        if (status==DownloadManager.STATUS_SUCCESSFUL) return "تکمیل‌شده";
        if (status==DownloadManager.STATUS_FAILED) return "ناموفق";
        return "وضعیت نامشخص";
    }

    private void showDownloadManager() {
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12),dp(8),dp(12),dp(4));
        TextView summary=new TextView(this); summary.setTextSize(12); summary.setTextColor(Color.DKGRAY); summary.setPadding(0,0,0,dp(8)); panel.addView(summary);
        ScrollView scroll=new ScrollView(this); LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list); panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(390)));
        final AlertDialog dialog=new AlertDialog.Builder(this).setTitle("دانلودها • Mrorgar-Q").setView(panel).setPositiveButton("بستن",null).setNeutralButton("تازه‌سازی",null).create();
        Runnable populate=()->{
            list.removeAllViews(); int count=0, running=0, done=0, failed=0;
            DownloadManager.Query q=new DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_PENDING|DownloadManager.STATUS_RUNNING|DownloadManager.STATUS_PAUSED|DownloadManager.STATUS_SUCCESSFUL|DownloadManager.STATUS_FAILED);
            Cursor c=null;
            try {
                c=downloadManager.query(q);
                if(c!=null) while(c.moveToNext()) {
                    count++; long id=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)); String title=c.getString(c.getColumnIndex(DownloadManager.COLUMN_TITLE)); if(title==null||title.trim().isEmpty())title="فایل دانلودی";
                    int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); long sofar=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)); long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if(status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PENDING)running++; if(status==DownloadManager.STATUS_SUCCESSFUL)done++; if(status==DownloadManager.STATUS_FAILED)failed++;
                    LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(9),dp(8),dp(9),dp(8)); card.setBackgroundColor(Color.rgb(245,246,248)); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.bottomMargin=dp(8); list.addView(card,cp);
                    TextView name=new TextView(this); name.setText(title); name.setTextColor(Color.BLACK); name.setTextSize(14); name.setMaxLines(2); name.setTypeface(null,android.graphics.Typeface.BOLD); card.addView(name);
                    TextView state=new TextView(this); state.setText(downloadStatus(status)+"  •  "+formatBytes(sofar)+" / "+formatBytes(total)); state.setTextColor(status==DownloadManager.STATUS_FAILED?Color.RED:Color.DKGRAY); state.setTextSize(12); card.addView(state);
                    ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); if(total>0){bar.setIndeterminate(false);bar.setMax(100);bar.setProgress((int)Math.max(0,Math.min(100,(sofar*100L)/total)));}else bar.setIndeterminate(status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PENDING); card.addView(bar,new LinearLayout.LayoutParams(-1,dp(8)));
                    LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); card.addView(actions);
                    if(status==DownloadManager.STATUS_SUCCESSFUL){Button open=makeButton("بازکردن",11);actions.addView(open,new LinearLayout.LayoutParams(-2,dp(34)));open.setOnClickListener(v->{try{Uri file=downloadManager.getUriForDownloadedFile(id);if(file==null){toast("آدرس فایل پیدا نشد");return;}Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(file,getContentResolver().getType(file));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}catch(Exception e){toast("برنامه‌ای برای بازکردن این فایل پیدا نشد");}});}
                    Button remove=makeButton(status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PENDING?"لغو دانلود":"حذف از فهرست",10);actions.addView(remove,new LinearLayout.LayoutParams(-2,dp(34)));remove.setOnClickListener(v->{downloadManager.remove(id);toast("دانلود از فهرست حذف شد");showDownloadManager();});
                }
            } catch(Exception e) { TextView err=new TextView(this);err.setText("خواندن فهرست دانلودها ناموفق بود.");list.addView(err); } finally { if(c!=null)c.close(); }
            if(count==0){TextView empty=new TextView(this);empty.setText("هنوز دانلودی در فهرست نیست. می‌توانی یک آدرس مستقیم اضافه کنی.");empty.setPadding(dp(8),dp(20),dp(8),dp(20));list.addView(empty);}
            summary.setText("کل: "+count+"   •   فعال/در انتظار: "+running+"   •   تکمیل: "+done+"   •   ناموفق: "+failed);
        };
        populate.run(); dialog.setOnShowListener(x->{dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->populate.run());}); dialog.show();
    }

    private void multiDownloadDialog() {
        String[] choices={"مشاهده پنجره دانلودها","دانلود فایل از آدرس","ذخیره صفحه فعلی","کپی آدرس صفحه"};
        new AlertDialog.Builder(this).setTitle("ابزار دانلود و ذخیره").setItems(choices,(d,w)->{
            if(w==0)showDownloadManager();
            else if(w==1){EditText input=field("آدرس مستقیم فایل","https://example.com/file.zip");new AlertDialog.Builder(this).setTitle("دانلود فایل از آدرس").setView(input).setNegativeButton("لغو",null).setPositiveButton("دانلود",(dd,ww)->{String u=input.getText().toString().trim();if(!u.isEmpty())enqueueDownload(u,web==null?WebSettings.getDefaultUserAgent(this):web.getSettings().getUserAgentString(),null,null);}).show();}
            else if(w==2)savePage(); else copyCurrentUrl();
        }).setNegativeButton("بستن",null).show();
    }

    // One-shot Copy MW selection mode: wait quietly for the next tap in the page.
    private void armCopyMW() {
        if (web == null) { toast("صفحه‌ای باز نیست"); return; }
        if (copyMwArmed) {
            copyMwArmed = false; resetCopyMwButton();
            if (web != null) web.evaluateJavascript("(function(){if(window.__mrorgarCopyMWHandler)document.removeEventListener('click',window.__mrorgarCopyMWHandler,true);window.__mrorgarCopyMWHandler=null;window.__mrorgarCopyMWResult='';return true;})()", null);
            toast("حالت Copy MW لغو شد"); return;
        }
        copyMwArmed = true;
        if (copyMwButton != null) { copyMwButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(255,193,7))); copyMwButton.setTextColor(Color.BLACK); }
        String js = "(function(){try{"+
            "if(window.__mrorgarCopyMWHandler)document.removeEventListener('click',window.__mrorgarCopyMWHandler,true);"+
            "window.__mrorgarCopyMWHandler=function(ev){try{"+
            "var t=ev.target;if(!t||t===document.documentElement||t===document.body)t=(t&&t.parentElement)||document.body;"+
            "var box=t.closest?t.closest('[role=dialog],dialog,[aria-modal=true],[class*=modal i],[class*=popup i],[class*=popover i]'):null;"+
            "if(!box&&t.closest)box=t.closest('article,section,main,form,[class*=menu i],li');"+
            "if(!box&&t.closest)box=t.closest('div');"+
            "if(!box||box===document.body||box===document.documentElement)box=t.parentElement||t;"+
            "ev.preventDefault();ev.stopPropagation();if(ev.stopImmediatePropagation)ev.stopImmediatePropagation();"+
            "document.removeEventListener('click',window.__mrorgarCopyMWHandler,true);window.__mrorgarCopyMWHandler=null;"+
            "window.__mrorgarCopyMWResult=JSON.stringify({html:box.outerHTML||'',text:(box.innerText||box.textContent||'').trim()});return true;"+
            "}catch(e){window.__mrorgarCopyMWResult=JSON.stringify({html:'',text:''});return true;}};"+
            "window.__mrorgarCopyMWResult='';document.addEventListener('click',window.__mrorgarCopyMWHandler,true);return 'armed';"+
            "}catch(e){return 'error';}})()";
        web.evaluateJavascript(js, result -> {
            if (result == null || result.contains("error")) { copyMwArmed=false; resetCopyMwButton(); toast("فعال‌کردن Copy MW ناموفق بود"); }
        });
    }

    private void resetCopyMwButton() {
        if (copyMwButton != null) { copyMwButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(123,31,162))); copyMwButton.setTextColor(Color.WHITE); }
    }

    private void copyCurrentUrl() {
        if (web == null || web.getUrl() == null) { toast("صفحه‌ای باز نیست"); return; }
        android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(c!=null){c.setPrimaryClip(ClipData.newPlainText("Mrorgar-Q",web.getUrl()));toast("آدرس صفحه کپی شد");}
    }

    private void handleCopyMWSelection() {
        if (web == null || !copyMwArmed) return;
        web.evaluateJavascript("(function(){var r=window.__mrorgarCopyMWResult||'';window.__mrorgarCopyMWResult='';return r;})()", result -> {
            copyMwArmed=false; resetCopyMwButton();
            try {
                Object decoded = new JSONTokener(result == null ? "null" : result).nextValue();
                if (!(decoded instanceof String)) throw new Exception("No selection data");
                JSONObject data = new JSONObject((String)decoded);
                String html=data.optString("html",""); String txt=data.optString("text","").trim();
                if(html.isEmpty()&&txt.isEmpty()){toast("این بخش قابل کپی نبود");return;}
                android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(c!=null){c.setPrimaryClip(ClipData.newHtmlText("Mrorgar-Q Copy MW",txt,html));toast("بخش انتخاب‌شده کپی شد");}
            } catch(Exception e) { toast("کپی این بخش انجام نشد"); }
        });
    }

    private EditText field(String hint,String value) { EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextSize(15);e.setPadding(dp(12),dp(8),dp(12),dp(8));if(value!=null){e.setText(value);e.setSelection(e.getText().length());}return e; }

    private void setupPanel() {
        String message = "تنظیمات تکمیلی مرورگر\n\n"+
                "دکمه‌های Back، Forward، Send، File، Copy MW، IP Set، Save، Android Desktop و F Screen روی نوار اصلی باقی می‌مانند.\n\n"+
                "این بخش برای گزینه‌های کم‌استفادهٔ آینده در نظر گرفته شده است؛ فعلاً گزینهٔ تنظیمیِ دیگری به آن اضافه نشده تا دکمهٔ غیرعملیاتی نمایش داده نشود.";
        new AlertDialog.Builder(this)
                .setTitle("Setup")
                .setMessage(message)
                .setPositiveButton("باشه", null)
                .show();
    }

    private void ipSettings() {
        String[] options={"Proxy — پروکسی","DNS — تنظیم DNS","Tunnel — تونل","V2Ray — تنظیمات V2Ray"};
        new AlertDialog.Builder(this).setTitle("IP Set").setItems(options,(d,w)->{if(w==0)applyProxyDialog();else if(w==1)dnsSettings();else if(w==2)tunnelSettings();else vraySettings();}).setNegativeButton("بستن",null).show();
    }

    private void applyProxyDialog() {
        EditText host=field("آدرس یا IP پروکسی",prefs().getString("proxy_host",""));EditText port=field("پورت پروکسی",prefs().getString("proxy_port",""));port.setInputType(InputType.TYPE_CLASS_NUMBER);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(20),dp(8),dp(20),dp(4));p.addView(host);p.addView(port);
        new AlertDialog.Builder(this).setTitle("تنظیم Proxy").setView(p).setNeutralButton("خاموش‌کردن",(d,w)->disableProxy()).setNegativeButton("لغو",null).setPositiveButton("ذخیره و اعمال",(d,w)->applyProxy(host.getText().toString().trim(),port.getText().toString().trim())).show();
    }

    private void applyProxy(String host,String port) {
        if(host==null||host.trim().isEmpty()||port==null||port.trim().isEmpty()){toast("آدرس و پورت را وارد کن");return;}final int pn;
        try{pn=Integer.parseInt(port);if(pn<1||pn>65535)throw new NumberFormatException();}catch(NumberFormatException e){toast("پورت باید عددی بین 1 و 65535 باشد");return;}
        if(Build.VERSION.SDK_INT<28){toast("اعمال پروکسی داخلی به Android 9 یا بالاتر نیاز دارد");return;}
        try{ProxyConfig config=new ProxyConfig.Builder().addProxyRule(host+":"+pn).build();ProxyController.getInstance().setProxyOverride(config,Runnable::run,()->runOnUiThread(()->{prefs().edit().putString("proxy_host",host).putString("proxy_port",String.valueOf(pn)).putBoolean("proxy_enabled",true).apply();toast("تنظیم پروکسی به WebView ارسال شد");if(web!=null)web.reload();}));}catch(Exception e){toast("اعمال پروکسی ناموفق بود");}
    }

    private void disableProxy() {
        if(Build.VERSION.SDK_INT<28){toast("خاموش‌کردن پروکسی داخلی به Android 9 یا بالاتر نیاز دارد");return;}
        try{ProxyController.getInstance().clearProxyOverride(Runnable::run,()->runOnUiThread(()->{prefs().edit().putBoolean("proxy_enabled",false).apply();toast("پروکسی WebView خاموش شد");if(web!=null)web.reload();}));}catch(Exception e){toast("خاموش‌کردن پروکسی ناموفق بود");}
    }

    private void dnsSettings() {
        EditText a=field("DNS اصلی (IPv4؛ مثال 1.1.1.1)",prefs().getString("dns_primary",""));
        EditText b=field("DNS دوم (IPv4؛ اختیاری)",prefs().getString("dns_secondary",""));
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(20),dp(8),dp(20),dp(4));p.addView(a);p.addView(b);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("DNS واقعی دستگاه").setMessage("برای اعمال DNS، اندروید اجازهٔ اتصال VPN محلی را می‌خواهد. فقط درخواست‌های DNS از این سرویس عبور می‌کنند؛ ترافیک عادی اینترنت از تونل عبور نمی‌کند.").setView(p).setNeutralButton("خاموش‌کردن DNS",null).setNegativeButton("لغو",null).setPositiveButton("اعمال و اتصال",null).create();
        dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view->{stopDnsVpnService();dialog.dismiss();});dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{String x=a.getText().toString().trim(),y=b.getText().toString().trim();if(x.isEmpty()&&y.isEmpty()){toast("حداقل یک آدرس DNS وارد کن");return;}if((!x.isEmpty()&&!isValidIpv4(x))||(!y.isEmpty()&&!isValidIpv4(y))){toast("فقط آدرس IPv4 معتبر وارد کن؛ مثلاً 1.1.1.1");return;}if(x.isEmpty()){x=y;y="";}prefs().edit().putString("dns_primary",x).putString("dns_secondary",y).apply();Intent prepare=VpnService.prepare(this);if(prepare!=null){startActivityForResult(prepare,DNS_VPN_REQUEST);}else startDnsVpnService();dialog.dismiss();});});dialog.show();
    }

    private boolean isValidIpv4(String value) {
        String[] parts=value.split("\\.",-1);if(parts.length!=4)return false;
        for(String part:parts){if(part.isEmpty()||part.length()>3)return false;for(int i=0;i<part.length();i++)if(!Character.isDigit(part.charAt(i)))return false;try{int n=Integer.parseInt(part);if(n<0||n>255)return false;}catch(Exception e){return false;}}
        return true;
    }

    private void startDnsVpnService() {
        String primary=prefs().getString("dns_primary","").trim(),secondary=prefs().getString("dns_secondary","").trim();
        Intent i=new Intent(this,DnsVpnService.class).setAction(DnsVpnService.ACTION_START).putExtra(DnsVpnService.EXTRA_PRIMARY,primary).putExtra(DnsVpnService.EXTRA_SECONDARY,secondary);
        try{if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);toast("درخواست اتصال DNS ارسال شد؛ وضعیت VPN را بررسی کن.");}catch(Exception e){toast("شروع سرویس DNS ناموفق بود: "+e.getMessage());}
    }

    private void stopDnsVpnService() {
        try{Intent i=new Intent(this,DnsVpnService.class).setAction(DnsVpnService.ACTION_STOP);startService(i);prefs().edit().remove("dns_primary").remove("dns_secondary").apply();toast("درخواست خاموش‌کردن DNS ارسال شد");}catch(Exception e){toast("خاموش‌کردن DNS ناموفق بود");}
    }

    private void tunnelSettings() {
        EditText host=field("آدرس سرور تونل",prefs().getString("tunnel_server",""));EditText port=field("پورت",prefs().getString("tunnel_port",""));port.setInputType(InputType.TYPE_CLASS_NUMBER);EditText type=field("نوع تونل (مثلاً SOCKS5)",prefs().getString("tunnel_type","SOCKS5"));LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(20),dp(8),dp(20),dp(4));p.addView(host);p.addView(port);p.addView(type);
        new AlertDialog.Builder(this).setTitle("تنظیم Tunnel").setView(p).setNeutralButton("پاک‌کردن",(d,w)->{prefs().edit().remove("tunnel_server").remove("tunnel_port").remove("tunnel_type").apply();toast("تنظیمات تونل پاک شد");}).setNegativeButton("لغو",null).setPositiveButton("ذخیره",(d,w)->{String h=host.getText().toString().trim(),pt=port.getText().toString().trim(),tp=type.getText().toString().trim();if(h.isEmpty()||pt.isEmpty()){toast("آدرس و پورت سرور را وارد کن");return;}try{int n=Integer.parseInt(pt);if(n<1||n>65535)throw new NumberFormatException();}catch(NumberFormatException e){toast("پورت معتبر نیست");return;}prefs().edit().putString("tunnel_server",h).putString("tunnel_port",pt).putString("tunnel_type",tp).apply();toast("تنظیمات ذخیره شد؛ تونل هنوز متصل نیست");}).show();
    }

    private void vraySettings() {
        String[] options={"واردکردن فایل پیکربندی","واردکردن لینک V2Ray","مشاهده تنظیمات ذخیره‌شده","پاک‌کردن تنظیمات"};
        new AlertDialog.Builder(this).setTitle("V2Ray").setItems(options,(d,w)->{if(w==0)pickVrayFile();else if(w==1)enterVrayLink();else if(w==2)showVrayConfig();else new AlertDialog.Builder(this).setTitle("پاک‌کردن V2Ray").setMessage("تنظیمات ذخیره‌شده پاک شود؟").setNegativeButton("لغو",null).setPositiveButton("پاک‌کردن",(dd,ww)->{prefs().edit().remove("vray_config").remove("vray_link").apply();toast("تنظیمات V2Ray پاک شد");}).show();}).setNegativeButton("بستن",null).show();
    }

    private void pickVrayFile() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,PICK_VRAY);}catch(Exception e){toast("انتخاب فایل پیکربندی ممکن نشد");} }

    private void enterVrayLink() {
        EditText input=field("لینک vmess://، vless:// یا مشابه",prefs().getString("vray_link",""));new AlertDialog.Builder(this).setTitle("واردکردن لینک V2Ray").setView(input).setNegativeButton("لغو",null).setPositiveButton("ذخیره",(d,w)->{String link=input.getText().toString().trim();if(!(link.startsWith("vmess://")||link.startsWith("vless://")||link.startsWith("trojan://")||link.startsWith("ss://"))){toast("قالب لینک شناخته‌شده نیست");return;}prefs().edit().putString("vray_link",link).apply();toast("لینک ذخیره شد؛ اتصال V2Ray فعال نشده است");}).show();
    }

    private void showVrayConfig() {
        String c=prefs().getString("vray_config",""),l=prefs().getString("vray_link","");String content=!c.isEmpty()?"فایل پیکربندی وارد شده است.\n\n"+c:!l.isEmpty()?"لینک ذخیره‌شده:\n"+l:"هنوز تنظیماتی وارد نشده است.";ScrollView s=new ScrollView(this);TextView t=new TextView(this);t.setText(content);t.setTextIsSelectable(true);t.setPadding(dp(16),dp(12),dp(16),dp(12));s.addView(t);new AlertDialog.Builder(this).setTitle("تنظیمات ذخیره‌شده").setView(s).setPositiveButton("باشه",null).show();
    }

    private void savePage() {
        if(web==null)return;String url=web.getUrl();if(url==null||url.isEmpty()){toast("صفحه‌ای برای ذخیره وجود ندارد");return;}
        web.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()",result->{try{Object parsed=new JSONTokener(result).nextValue();String html=parsed==null?"":parsed.toString();if(html.trim().isEmpty()){toast("محتوای صفحه قابل ذخیره نیست");return;}pendingSaveBytes=html.getBytes(java.nio.charset.StandardCharsets.UTF_8);pendingSaveMime="text/html";pendingSaveName="page.html";Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/html");i.putExtra(Intent.EXTRA_TITLE,pendingSaveName);startActivityForResult(i,CREATE_SAVE_FILE);}catch(Exception e){toast("آماده‌سازی صفحه برای ذخیره ناموفق بود");}});
    }

    private void setFullscreen() {
        fullscreen=!fullscreen;
        if(fullscreen){getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);top.setVisibility(View.GONE);addressRow.setVisibility(View.GONE);tabRow.setVisibility(View.GONE);restoreButton.setVisibility(View.VISIBLE);toast("حالت تمام‌صفحه فعال شد");}
        else{getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);top.setVisibility(View.VISIBLE);addressRow.setVisibility(View.VISIBLE);tabRow.setVisibility(View.VISIBLE);restoreButton.setVisibility(View.GONE);toast("حالت تمام‌صفحه خاموش شد");}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==DNS_VPN_REQUEST){if(resultCode==RESULT_OK)startDnsVpnService();else toast("اجازهٔ VPN داده نشد؛ DNS اعمال نشد");return;}
        if(requestCode==PICK_FILE){
            if(uploadCallback==null)return;Uri[] results=null;
            if(resultCode==RESULT_OK&&data!=null){if(data.getClipData()!=null){int n=data.getClipData().getItemCount();results=new Uri[n];for(int i=0;i<n;i++)results[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)results=new Uri[]{data.getData()};}
            uploadCallback.onReceiveValue(results);uploadCallback=null;
        } else if(requestCode==PICK_MANUAL_UPLOAD) {
            // Retained for compatibility with earlier versions; File now invokes the website's own file input.
            if(resultCode==RESULT_OK&&data!=null)toast("برای آپلود، ورودی فایل همان سایت را فعال کن");
        } else if(requestCode==PICK_VRAY&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            try{String txt=readText(data.getData());if(txt==null||txt.trim().isEmpty())toast("فایل خالی است");else{if(txt.length()>20000)txt=txt.substring(0,20000);prefs().edit().putString("vray_config",txt).apply();toast("فایل پیکربندی ذخیره شد؛ اتصال فعال نشده است");}}catch(Exception e){toast("خواندن فایل ناموفق بود");}
        } else if(requestCode==CREATE_SAVE_FILE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            if(pendingSaveBytes==null){toast("داده‌ای برای ذخیره نیست");return;}try(OutputStream os=getContentResolver().openOutputStream(data.getData())){if(os==null)throw new Exception("stream null");os.write(pendingSaveBytes);os.flush();toast("صفحه ذخیره شد");}catch(Exception e){toast("ذخیره فایل ناموفق بود");}pendingSaveBytes=null;
        }
    }

    private String readText(Uri uri) throws Exception {
        try(InputStream is=getContentResolver().openInputStream(uri)){if(is==null)return null;byte[] buf=new byte[8192];java.io.ByteArrayOutputStream bos=new java.io.ByteArrayOutputStream();int r,total=0;while((r=is.read(buf))!=-1){total+=r;if(total>100000)break;bos.write(buf,0,r);}return bos.toString("UTF-8");}
    }
}
