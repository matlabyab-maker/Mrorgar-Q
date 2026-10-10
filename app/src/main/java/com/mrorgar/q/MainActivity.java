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
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Base64;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
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
import android.widget.Switch;
import android.widget.Toast;

import org.json.JSONTokener;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private EditText address;
    private LinearLayout root, top, tabStrip, tabRow, addressRow;
    private FrameLayout screenHost, stage;
    private Button sendButton;
    private android.view.ViewGroup sendOriginalParent;
    private android.view.ViewGroup.LayoutParams sendOriginalLayoutParams;
    private int sendOriginalIndex = -1;
    private boolean sendIsFloating = false;
    private float sendDownRawX, sendDownRawY;
    private int sendStartLeft, sendStartTop;
    private boolean sendDragArmed = false, sendDragMoved = false;
    private Button restoreButton;
    private Button bookmarkButton;
    private Button copyMwButton;
    private Button windowSizeButton;
    private WindowManager overlayWindowManager;
    private WindowManager.LayoutParams overlayWindowParams;
    private boolean floatingWindowActive = false;
    private int windowSizeStep = 0; // 0=normal, 1..3=progressively smaller
    private int zoomPercent = 100;
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
    private float restoreTouchDownX, restoreTouchDownY;
    private int restoreStartLeft, restoreStartTop;
    private boolean restoreMoved = false;
    private final List<BrowserTab> tabs = new ArrayList<>();
    private int activeTabIndex = -1;
    private byte[] pendingSaveBytes;
    private String pendingSaveMime = "text/html";
    private String pendingSaveName = "page.html";

    // Hourly random light effect for the real top toolbar buttons.
    private final Handler hourlyLightHandler = new Handler(Looper.getMainLooper());
    private final Random hourlyLightRandom = new Random();
    private final Map<View, ColorStateList> hourlyOriginalTints = new HashMap<>();
    private final Map<View, Integer> hourlyOriginalTextColors = new HashMap<>();
    private Runnable hourlyClockCheck;
    private Runnable hourlyLightStep;
    private int hourlyLightGeneration = 0;
    private long lastHourlyFlashKey = Long.MIN_VALUE;

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
        // Keep the WebView visible and correctly resized when the Android IME opens.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().setStatusBarColor(Color.rgb(32,33,36));
        getWindow().setNavigationBarColor(Color.BLACK);
        downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        buildUI();
        desktopMode = state != null
                ? state.getBoolean("desktop_mode", prefs().getBoolean("desktop_mode", false))
                : prefs().getBoolean("desktop_mode", false);
        zoomPercent = desktopMode ? 40 : 100;
        boolean restored = restoreBrowserSession();
        if (!restored) {
            String initialUrl = "mrorgar://home";
            if (state != null) {
                String savedUrl = state.getString("current_url");
                if (savedUrl != null && !savedUrl.trim().isEmpty()) initialUrl = savedUrl.startsWith("https://mrorgar.home/") ? "mrorgar://home" : savedUrl;
            }
            createTab(initialUrl);
        }
        updateDesktopButton();
    }

    @Override protected void onResume() {
        super.onResume();
        startHourlyLightClock();
    }

    // Persist every open WebView's navigation history, current page and tab order.
    // This lets the browser recover its session after Android reclaims the process.
    @Override protected void onPause() {
        stopHourlyLightClock();
        saveBrowserSession();
        // Keep the WebView active while it is shown in the user's floating overlay window.
        if (!floatingWindowActive) {
            for (BrowserTab t : tabs) { try { t.view.onPause(); } catch (Exception ignored) {} }
        }
        super.onPause();
    }

    @Override protected void onStop() {
        saveBrowserSession();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        saveBrowserSession();
        super.onSaveInstanceState(out);
        if (web != null && web.getUrl() != null) out.putString("current_url", web.getUrl());
        out.putBoolean("desktop_mode", desktopMode);
    }

    private void saveBrowserSession() {
        try {
            JSONArray savedTabs = new JSONArray();
            for (BrowserTab t : tabs) {
                JSONObject item = new JSONObject();
                String url = t.view.getUrl();
                item.put("url", url == null ? t.lastUrl : url);
                item.put("title", t.title == null ? "New tab" : t.title);
                item.put("scrollX", t.view.getScrollX());
                item.put("scrollY", t.view.getScrollY());
                Bundle state = new Bundle();
                t.view.saveState(state);
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.writeBundle(state);
                    item.put("webState", Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP));
                } finally { parcel.recycle(); }
                savedTabs.put(item);
            }
            prefs().edit().putString("browser_session_tabs", savedTabs.toString())
                    .putInt("browser_session_active", Math.max(0, activeTabIndex))
                    .putBoolean("desktop_mode", desktopMode).apply();
        } catch (Exception ignored) { /* Keep the last good session if a page cannot be serialized. */ }
    }

    private boolean restoreBrowserSession() {
        String raw = prefs().getString("browser_session_tabs", "");
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            JSONArray savedTabs = new JSONArray(raw);
            if (savedTabs.length() == 0) return false;
            int desiredActive = Math.max(0, Math.min(prefs().getInt("browser_session_active", 0), savedTabs.length() - 1));
            for (int i = 0; i < savedTabs.length(); i++) {
                JSONObject item = savedTabs.getJSONObject(i);
                String url = item.optString("url", "mrorgar://home");
                String encoded = item.optString("webState", "");
                boolean restoredWebState = false;
                WebView view = new WebView(this);
                BrowserTab tab = new BrowserTab(view);
                tab.lastUrl = url;
                tab.title = item.optString("title", "New tab");
                tabs.add(tab);
                view.setVisibility(View.GONE);
                stage.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.HORIZONTAL);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(dp(3), 0, dp(2), 0);
                tab.chip = chip;
                Button select = makeButton("▣ "+tab.title, 10);
                select.setTextColor(Color.BLACK);
                select.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
                tab.selectButton = select;
                chip.addView(select, new LinearLayout.LayoutParams(-2, dp(32)));
                enableTabDrag(select, tab);
                Button close = new Button(this);
                close.setText("✕"); close.setTextSize(15); close.setAllCaps(false); close.setPadding(0,0,0,0);
                close.setMinWidth(0); close.setMinimumWidth(0); close.setMinHeight(0); close.setMinimumHeight(0);
                tab.closeButton = close;
                chip.addView(close, new LinearLayout.LayoutParams(dp(27), dp(32)));
                close.setOnClickListener(v -> closeTab(tabs.indexOf(tab)));
                tabStrip.addView(chip, new LinearLayout.LayoutParams(-2, dp(36)));
                configureWeb(view);
                view.setOnTouchListener((v, event) -> {
                    if (copyMwArmed && event.getAction() == MotionEvent.ACTION_UP) v.postDelayed(() -> handleCopyMWSelection(), 120);
                    return false;
                });
                if (!encoded.isEmpty()) {
                    Parcel parcel = Parcel.obtain();
                    try {
                        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                        parcel.unmarshall(bytes, 0, bytes.length);
                        parcel.setDataPosition(0);
                        Bundle webState = parcel.readBundle(getClassLoader());
                        if (webState != null) restoredWebState = view.restoreState(webState) != null;
                    } catch (Exception ignored) { restoredWebState = false; }
                    finally { parcel.recycle(); }
                }
                if (!restoredWebState) {
                    if ("mrorgar://home".equals(url) || url.startsWith("https://mrorgar.home/")) loadHomePage(view);
                    else if (!url.isEmpty()) view.loadUrl(url);
                }
                final int sx = item.optInt("scrollX", 0), sy = item.optInt("scrollY", 0);
                view.post(() -> view.scrollTo(sx, sy));
            }
            selectTab(desiredActive);
            return !tabs.isEmpty();
        } catch (Exception e) {
            for (BrowserTab t : tabs) { try { stage.removeView(t.view); t.view.destroy(); } catch (Exception ignored) {} }
            tabs.clear(); tabStrip.removeAllViews(); activeTabIndex = -1;
            return false;
        }
    }

    @Override protected void onDestroy() {
        if (floatingWindowActive) {
            try { if (overlayWindowManager != null && root.getParent() != null) overlayWindowManager.removeView(root); } catch (Exception ignored) {}
            floatingWindowActive = false;
        }
        if (uploadCallback != null) { uploadCallback.onReceiveValue(null); uploadCallback = null; }
        for (BrowserTab t : tabs) { try { t.view.destroy(); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (fullscreen) { setFullscreen(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    private void startHourlyLightClock() {
        stopHourlyLightClock();
        hourlyClockCheck = new Runnable() {
            @Override public void run() {
                Calendar now = Calendar.getInstance();
                if (now.get(Calendar.MINUTE) == 0) {
                    long key = (((long) now.get(Calendar.YEAR) * 400L + now.get(Calendar.DAY_OF_YEAR)) * 24L)
                            + now.get(Calendar.HOUR_OF_DAY);
                    if (key != lastHourlyFlashKey) {
                        lastHourlyFlashKey = key;
                        beginHourlyRandomLight();
                    }
                }
                hourlyLightHandler.postDelayed(this, 500L);
            }
        };
        hourlyLightHandler.post(hourlyClockCheck);
    }

    private void stopHourlyLightClock() {
        if (hourlyClockCheck != null) hourlyLightHandler.removeCallbacks(hourlyClockCheck);
        if (hourlyLightStep != null) hourlyLightHandler.removeCallbacks(hourlyLightStep);
        hourlyClockCheck = null;
        hourlyLightStep = null;
        restoreHourlyToolbarColors();
    }

    private void beginHourlyRandomLight() {
        if (top == null || top.getChildCount() == 0) return;
        final int generation = ++hourlyLightGeneration;
        hourlyOriginalTints.clear();
        hourlyOriginalTextColors.clear();
        for (int i = 0; i < top.getChildCount(); i++) {
            View child = top.getChildAt(i);
            if (child instanceof Button) {
                Button b = (Button) child;
                hourlyOriginalTints.put(b, b.getBackgroundTintList());
                hourlyOriginalTextColors.put(b, b.getCurrentTextColor());
            }
        }
        final long endAt = android.os.SystemClock.uptimeMillis() + 2000L;
        hourlyLightStep = new Runnable() {
            @Override public void run() {
                if (generation != hourlyLightGeneration) return;
                if (android.os.SystemClock.uptimeMillis() >= endAt) {
                    restoreHourlyToolbarColors();
                    hourlyLightStep = null;
                    return;
                }
                for (int i = 0; i < top.getChildCount(); i++) {
                    View child = top.getChildAt(i);
                    if (!(child instanceof Button)) continue;
                    Button b = (Button) child;
                    int r = 70 + hourlyLightRandom.nextInt(186);
                    int g = 70 + hourlyLightRandom.nextInt(186);
                    int bl = 70 + hourlyLightRandom.nextInt(186);
                    int color = Color.rgb(r, g, bl);
                    b.setBackgroundTintList(ColorStateList.valueOf(color));
                    b.setTextColor((r * 299 + g * 587 + bl * 114) / 1000 >= 150 ? Color.BLACK : Color.WHITE);
                }
                hourlyLightHandler.postDelayed(this, 120L);
            }
        };
        hourlyLightHandler.post(hourlyLightStep);
    }

    private void restoreHourlyToolbarColors() {
        hourlyLightGeneration++;
        for (Map.Entry<View, ColorStateList> entry : hourlyOriginalTints.entrySet()) {
            View v = entry.getKey();
            if (v instanceof Button) {
                ((Button) v).setBackgroundTintList(entry.getValue());
                Integer textColor = hourlyOriginalTextColors.get(v);
                if (textColor != null) ((Button) v).setTextColor(textColor);
            }
        }
        hourlyOriginalTints.clear();
        hourlyOriginalTextColors.clear();
    }

    private Button makeButton(String label, float textSize) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(textSize); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setPadding(dp(2),0,dp(2),0); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(45,46,49)));
        return b;
    }

    private void buildUI() {
        screenHost = new FrameLayout(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackgroundColor(Color.rgb(32,33,36));
        String[] names = {"Back","Forward","Copy MW","IP Set","Save","Android Desktop","F Screen","Setup","File","Send"};
        String[] buttonLabels = {"‹\nBack", "›\nForward", "▣\nCopy MW", "⚙\nIP Set", "↓\nSave", "▤\nDesktop", "⛶\nFull", "☰\nSetup", "▰\nFile", "➤\nSend"};
        // Same single row, with buttons about 5 mm taller and distinct colors.
        int[][] buttonColors = {
            {69,90,100}, {69,90,100}, {123,31,162}, {230,126,34},
            {0,121,107}, {0,137,123}, {63,81,181}, {96,125,139},
            {21,101,192}, {46,125,50}
        };
        int colorIndex = 0;
        for (int buttonIndex = 0; buttonIndex < names.length; buttonIndex++) {
            String name = names[buttonIndex];
            Button b = makeButton(buttonLabels[buttonIndex],9f);
            b.setSingleLine(false);
            b.setGravity(Gravity.CENTER);
            b.setLineSpacing(0, 0.9f);
            b.setPadding(0,0,0,0);
            // Use a drawable without the stock Button's wide built-in insets, so the gaps shrink.
            b.setBackground(new android.graphics.drawable.GradientDrawable());
            b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(buttonColors[colorIndex][0],buttonColors[colorIndex][1],buttonColors[colorIndex][2])));
            // Equal width/height for every top-row button, with minimal visual gaps.
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(0,dp(59),1f);
            buttonLp.setMargins(dp(1),0,dp(1),0);
            top.addView(b,buttonLp);
            if ("Copy MW".equals(name)) copyMwButton = b;
            if ("Send".equals(name)) {
                sendButton = b;
                b.setOnClickListener(v -> doAction("Send"));
                enableFourSecondSendDrag(b);
            } else {
                b.setOnClickListener(v -> {
                    if ("Android Desktop".equals(name)) toggleDesktopMode();
                    else if ("Save".equals(name)) multiDownloadDialog();
                    else doAction(name);
                });
                enableTwoSecondDragReorder(b, top);
            }
            colorIndex++;
        }
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(59)));
        addressRow = new LinearLayout(this); addressRow.setOrientation(LinearLayout.HORIZONTAL); addressRow.setGravity(Gravity.CENTER_VERTICAL); addressRow.setPadding(dp(3),0,dp(3),0);
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(14); address.setHint("Website or search words"); address.setPadding(dp(10),0,dp(8),0); address.setImeOptions(EditorInfo.IME_ACTION_GO); address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        addressRow.addView(address,new LinearLayout.LayoutParams(0,dp(42),1));
        bookmarkButton = new Button(this); bookmarkButton.setText("☆"); bookmarkButton.setTextSize(23); bookmarkButton.setAllCaps(false); bookmarkButton.setPadding(0,0,0,0); bookmarkButton.setMinWidth(0); bookmarkButton.setMinimumWidth(0); bookmarkButton.setTextColor(Color.rgb(90,90,90)); bookmarkButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232,234,237)));
        addressRow.addView(bookmarkButton,new LinearLayout.LayoutParams(dp(42),dp(42)));
        bookmarkButton.setOnClickListener(v -> toggleBookmark());
        bookmarkButton.setOnLongClickListener(v -> { showBookmarks(); return true; });
        Button go = makeButton("⌕ Go",10); go.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232,234,237))); go.setTextColor(Color.BLACK);
        addressRow.addView(go,new LinearLayout.LayoutParams(dp(48),dp(42))); go.setOnClickListener(v -> loadAddress());
        Button home = makeButton("⌂ خانه",10); home.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(0,121,107))); home.setTextColor(Color.WHITE);
        addressRow.addView(home,new LinearLayout.LayoutParams(dp(48),dp(42))); home.setOnClickListener(v -> { if (web != null) loadHomePage(web); });
        root.addView(addressRow,new LinearLayout.LayoutParams(-1,dp(42)));
        tabRow = new LinearLayout(this); tabRow.setOrientation(LinearLayout.HORIZONTAL); tabRow.setGravity(Gravity.CENTER_VERTICAL); tabRow.setBackgroundColor(Color.rgb(238,239,241));
        // These two navigation buttons are fixed: they are deliberately not attached to the drag/reorder handler.
        Button history = makeButton("◷ History",9f); history.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(69,90,100)));
        tabRow.addView(history,new LinearLayout.LayoutParams(dp(62),dp(34))); history.setOnClickListener(v -> showHistory());
        Button favorites = makeButton("★ Favorites",9f); favorites.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(230,126,34)));
        tabRow.addView(favorites,new LinearLayout.LayoutParams(dp(72),dp(34))); favorites.setOnClickListener(v -> showBookmarks());
        Button zoomOut = makeButton("−",15); zoomOut.setContentDescription("کوچک‌کردن صفحه تا ۴۰٪");
        tabRow.addView(zoomOut,new LinearLayout.LayoutParams(dp(31),dp(34)));
        zoomOut.setOnClickListener(v -> changePageZoom(-20));
        Button zoomIn = makeButton("+",15); zoomIn.setContentDescription("بزرگ‌کردن صفحه تا ۲۰۰٪");
        tabRow.addView(zoomIn,new LinearLayout.LayoutParams(dp(31),dp(34)));
        zoomIn.setOnClickListener(v -> changePageZoom(20));
        windowSizeButton = makeButton("▣",14); windowSizeButton.setContentDescription("تغییر اندازه پنجره مرورگر؛ نگه‌داشتن برای خروج از حالت شناور");
        windowSizeButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(69,90,100)));
        tabRow.addView(windowSizeButton,new LinearLayout.LayoutParams(dp(34),dp(34)));
        windowSizeButton.setOnClickListener(v -> cycleBrowserWindowSize());
        windowSizeButton.setOnLongClickListener(v -> { if (floatingWindowActive) exitFloatingBrowserWindow(); else toast("برای پنجره شناور، دکمه را لمس کن و مجوز نمایش روی برنامه‌های دیگر را بده"); return true; });
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this); tabStrip.setOrientation(LinearLayout.HORIZONTAL); scroll.addView(tabStrip,new HorizontalScrollView.LayoutParams(-2,-1));
        tabRow.addView(scroll,new LinearLayout.LayoutParams(0,dp(36),1));
        Button add = new Button(this); add.setText("＋"); add.setTextSize(20); add.setAllCaps(false); add.setPadding(0,0,0,0); add.setMinWidth(0); add.setMinimumWidth(0);
        tabRow.addView(add,new LinearLayout.LayoutParams(dp(38),dp(36))); add.setOnClickListener(v -> createTab("https://www.google.com"));
        root.addView(tabRow,new LinearLayout.LayoutParams(-1,dp(36)));
        stage = new FrameLayout(this); root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        restoreButton = new Button(this); restoreButton.setText("⤢"); restoreButton.setTextSize(18); restoreButton.setAllCaps(false); restoreButton.setVisibility(View.GONE); restoreButton.setTextColor(Color.WHITE);
        restoreButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(137,171,192))); restoreButton.setBackgroundColor(Color.rgb(137,171,192)); restoreButton.setAlpha(0.92f); restoreButton.setElevation(dp(5)); restoreButton.setPadding(0,0,0,0);
        FrameLayout.LayoutParams restoreLp = new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.LEFT); restoreLp.leftMargin=dp(8); restoreLp.topMargin=dp(90);
        stage.addView(restoreButton,restoreLp);
        restoreButton.setOnTouchListener((v,event)->{
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)restoreButton.getLayoutParams();
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN: restoreTouchDownX=event.getRawX(); restoreTouchDownY=event.getRawY(); restoreStartLeft=lp.leftMargin; restoreStartTop=lp.topMargin; restoreMoved=false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx=event.getRawX()-restoreTouchDownX,dy=event.getRawY()-restoreTouchDownY;
                    if(Math.abs(dx)>dp(4)||Math.abs(dy)>dp(4))restoreMoved=true;
                    if(restoreMoved){int maxX=Math.max(0,stage.getWidth()-restoreButton.getWidth());int maxY=Math.max(0,stage.getHeight()-restoreButton.getHeight());lp.gravity=Gravity.TOP|Gravity.LEFT;lp.leftMargin=Math.max(0,Math.min(maxX,restoreStartLeft+(int)dx));lp.topMargin=Math.max(0,Math.min(maxY,restoreStartTop+(int)dy));restoreButton.setLayoutParams(lp);} return true;
                case MotionEvent.ACTION_UP: if(!restoreMoved&&fullscreen)setFullscreen(); v.performClick(); return true;
                case MotionEvent.ACTION_CANCEL:return true;
            } return true;
        });
        screenHost.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screenHost);
        address.setOnEditorActionListener((v,id,event) -> { if (id==EditorInfo.IME_ACTION_GO||id==EditorInfo.IME_ACTION_DONE||id==EditorInfo.IME_ACTION_SEARCH) { loadAddress(); return true; } return false; });
    }


    // Send behavior:
    // - Hold for four seconds while on the toolbar to detach it as a floating button.
    // - Once floating, it can be dragged immediately on the next touch.
    // - Hold it still for four seconds while floating to return it to its toolbar slot.
    private void enableFourSecondSendDrag(Button button) {
        final boolean[] longHoldTriggered = {false};
        final boolean[] floatingAtDown = {false};
        final Runnable[] holdAction = new Runnable[1];

        holdAction[0] = () -> {
            longHoldTriggered[0] = true;
            sendDragArmed = true;
            if (sendIsFloating) {
                restoreSendToToolbar();
                Toast.makeText(MainActivity.this, "دکمهٔ Send به نوار اصلی برگشت", Toast.LENGTH_SHORT).show();
            } else {
                attachSendAsFloating();
                Toast.makeText(MainActivity.this, "Send شناور شد؛ برای جابه‌جایی دوباره لمس و بکشید", Toast.LENGTH_SHORT).show();
            }
            button.setElevation(sendIsFloating ? dp(9) : 0);
        };

        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    sendDragArmed = false;
                    sendDragMoved = false;
                    longHoldTriggered[0] = false;
                    floatingAtDown[0] = sendIsFloating;
                    sendDownRawX = event.getRawX();
                    sendDownRawY = event.getRawY();

                    if (sendIsFloating) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
                        sendStartLeft = lp.leftMargin;
                        sendStartTop = lp.topMargin;
                        // While floating, movement starts immediately. A stationary 4-second hold
                        // restores the button instead of requiring an impossible drag-after-reparent.
                        sendDragArmed = true;
                    } else {
                        int[] hostLoc = new int[2];
                        int[] buttonLoc = new int[2];
                        screenHost.getLocationOnScreen(hostLoc);
                        button.getLocationOnScreen(buttonLoc);
                        sendStartLeft = buttonLoc[0] - hostLoc[0];
                        sendStartTop = buttonLoc[1] - hostLoc[1];
                    }
                    button.postDelayed(holdAction[0], 4000L);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - sendDownRawX;
                    float dy = event.getRawY() - sendDownRawY;
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                        sendDragMoved = true;
                        // Dragging a floating Send cancels the stationary hold-to-restore timer.
                        button.removeCallbacks(holdAction[0]);
                    }
                    if (floatingAtDown[0] && sendIsFloating && sendDragMoved) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
                        int maxX = Math.max(0, screenHost.getWidth() - button.getWidth());
                        int maxY = Math.max(0, screenHost.getHeight() - button.getHeight());
                        lp.gravity = Gravity.TOP | Gravity.LEFT;
                        lp.leftMargin = Math.max(0, Math.min(maxX, sendStartLeft + (int) dx));
                        lp.topMargin = Math.max(0, Math.min(maxY, sendStartTop + (int) dy));
                        button.setLayoutParams(lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    button.removeCallbacks(holdAction[0]);
                    button.setElevation(sendIsFloating ? dp(5) : 0);
                    boolean shouldClick = !sendDragMoved && !longHoldTriggered[0];
                    sendDragArmed = false;
                    if (shouldClick) button.performClick();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    button.removeCallbacks(holdAction[0]);
                    sendDragArmed = false;
                    return true;
            }
            return true;
        });
    }

    private void attachSendAsFloating() {
        if (sendButton == null || screenHost == null) return;
        if (!sendIsFloating) {
            int[] hostLoc = new int[2];
            int[] buttonLoc = new int[2];
            screenHost.getLocationOnScreen(hostLoc);
            sendButton.getLocationOnScreen(buttonLoc);
            sendStartLeft = buttonLoc[0] - hostLoc[0];
            sendStartTop = buttonLoc[1] - hostLoc[1];
            if (sendButton.getParent() instanceof android.view.ViewGroup) {
                sendOriginalParent = (android.view.ViewGroup) sendButton.getParent();
                sendOriginalIndex = sendOriginalParent.indexOfChild(sendButton);
                sendOriginalLayoutParams = sendButton.getLayoutParams();
                sendOriginalParent.removeView(sendButton);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(64), dp(48), Gravity.TOP | Gravity.LEFT);
            lp.leftMargin = Math.max(0, sendStartLeft);
            lp.topMargin = Math.max(0, sendStartTop);
            sendButton.setAlpha(0.96f);
            sendButton.setElevation(dp(9));
            screenHost.addView(sendButton, lp);
            sendIsFloating = true;
        }
    }

    private void restoreSendToToolbar() {
        if (sendButton == null || !sendIsFloating || sendOriginalParent == null) return;
        if (sendButton.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) sendButton.getParent()).removeView(sendButton);
        }
        int index = sendOriginalIndex;
        if (index < 0 || index > sendOriginalParent.getChildCount()) index = sendOriginalParent.getChildCount();
        android.view.ViewGroup.LayoutParams params = sendOriginalLayoutParams;
        if (params == null) params = new LinearLayout.LayoutParams(0, dp(59), 1f);
        sendButton.setAlpha(1f);
        sendButton.setElevation(0);
        sendOriginalParent.addView(sendButton, index, params);
        sendIsFloating = false;
    }

    // Hold a toolbar button briefly (650 ms), then drag horizontally to reorder it.
    // A normal short tap still triggers the button's original action.
    private void enableTwoSecondDragReorder(Button button, LinearLayout parent) {
        final boolean[] armed = {false};
        final boolean[] dragging = {false};
        final float[] downRawX = {0f};
        final float[] downRawY = {0f};
        final int[] originalIndex = {-1};
        final Runnable armDrag = () -> {
            armed[0] = true;
            Toast.makeText(MainActivity.this, "آمادهٔ جابه‌جایی؛ بکشید", Toast.LENGTH_SHORT).show();
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    armed[0] = false;
                    dragging[0] = false;
                    downRawX[0] = event.getRawX();
                    downRawY[0] = event.getRawY();
                    originalIndex[0] = parent.indexOfChild(button);
                    button.postDelayed(armDrag, 650L);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX[0];
                    float dy = event.getRawY() - downRawY[0];
                    if (armed[0] && (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5))) {
                        dragging[0] = true;
                        button.setAlpha(0.72f);
                        button.setElevation(dp(8));
                        float x = event.getRawX();
                        int target = parent.getChildCount() - 1;
                        for (int i = 0; i < parent.getChildCount(); i++) {
                            View child = parent.getChildAt(i);
                            int[] loc = new int[2]; child.getLocationOnScreen(loc);
                            if (x < loc[0] + child.getWidth() / 2f) { target = i; break; }
                        }
                        int current = parent.indexOfChild(button);
                        if (target != current && target >= 0) {
                            android.view.ViewGroup.LayoutParams lp = button.getLayoutParams();
                            parent.removeView(button);
                            parent.addView(button, Math.min(target, parent.getChildCount()), lp);
                        }
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    button.removeCallbacks(armDrag);
                    button.setAlpha(1f);
                    button.setElevation(0f);
                    if (dragging[0]) {
                        dragging[0] = false;
                        armed[0] = false;
                        return true;
                    }
                    boolean heldLongEnough = armed[0];
                    armed[0] = false;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) button.performClick();
                    return true;
            }
            return true;
        });
    }

    private void createTab(String url) {
        WebView view = new WebView(this); BrowserTab tab = new BrowserTab(view); tab.lastUrl = url; tabs.add(tab); view.setVisibility(View.GONE);
        stage.addView(view,0,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout chip = new LinearLayout(this); chip.setOrientation(LinearLayout.HORIZONTAL); chip.setGravity(Gravity.CENTER_VERTICAL); chip.setPadding(dp(3),0,dp(2),0); tab.chip = chip;
        Button select = makeButton("▣ Tab "+tabs.size(),10); select.setTextColor(Color.BLACK); select.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE)); tab.selectButton=select;
        chip.addView(select,new LinearLayout.LayoutParams(-2,dp(32)));
        enableTabDrag(select, tab);
        Button close = new Button(this); close.setText("✕"); close.setTextSize(15); close.setAllCaps(false); close.setPadding(0,0,0,0); close.setMinWidth(0); close.setMinimumWidth(0); close.setMinHeight(0); close.setMinimumHeight(0); tab.closeButton=close;
        chip.addView(close,new LinearLayout.LayoutParams(dp(27),dp(32))); close.setOnClickListener(v -> closeTab(tabs.indexOf(tab)));
        tabStrip.addView(chip,new LinearLayout.LayoutParams(-2,dp(36)));
        configureWeb(view);
        view.setOnTouchListener((v, event) -> {
            if (copyMwArmed && event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.postDelayed(() -> handleCopyMWSelection(), 120);
            }
            return false;
        });
        selectTab(tabs.size()-1); if ("mrorgar://home".equals(url)) loadHomePage(view); else view.loadUrl(url);
    }

    // Hold a tab title briefly, then drag it horizontally to reorder tabs.
    // A normal tap still selects the tab; the close button remains independent.
    private void enableTabDrag(Button select, BrowserTab tab) {
        final boolean[] armed = {false};
        final boolean[] dragging = {false};
        final float[] downX = {0f};
        final float[] downY = {0f};
        final Runnable arm = () -> {
            armed[0] = true;
            Toast.makeText(MainActivity.this, "زبانه آمادهٔ جابه‌جایی است؛ آن را بکشید", Toast.LENGTH_SHORT).show();
        };
        select.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                armed[0] = false;
                dragging[0] = false;
                downX[0] = event.getRawX();
                downY[0] = event.getRawY();
                select.postDelayed(arm, 650L);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - downX[0];
                float dy = event.getRawY() - downY[0];
                if (armed[0] && (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5))) {
                    dragging[0] = true;
                    select.setAlpha(0.7f);
                    select.setElevation(dp(5));
                    float x = event.getRawX();
                    int target = tabStrip.getChildCount() - 1;
                    for (int i = 0; i < tabStrip.getChildCount(); i++) {
                        View child = tabStrip.getChildAt(i);
                        int[] location = new int[2];
                        child.getLocationOnScreen(location);
                        if (x < location[0] + child.getWidth() / 2f) { target = i; break; }
                    }
                    int current = tabStrip.indexOfChild(tab.chip);
                    if (target >= 0 && target != current) {
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tab.chip.getLayoutParams();
                        tabStrip.removeView(tab.chip);
                        tabStrip.addView(tab.chip, Math.min(target, tabStrip.getChildCount()), lp);
                        // Keep the logical tab list in the same order as the visible strip.
                        BrowserTab moved = tab;
                        int oldListIndex = tabs.indexOf(moved);
                        int newListIndex = Math.max(0, Math.min(target, tabs.size() - 1));
                        if (oldListIndex >= 0 && oldListIndex != newListIndex) {
                            tabs.remove(oldListIndex);
                            tabs.add(Math.min(newListIndex, tabs.size()), moved);
                        }
                        activeTabIndex = indexOfWeb(web);
                    }
                    return true;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                select.removeCallbacks(arm);
                select.setAlpha(1f);
                select.setElevation(0f);
                if (dragging[0]) {
                    dragging[0] = false;
                    armed[0] = false;
                    return true;
                }
                boolean wasArmed = armed[0];
                armed[0] = false;
                if (action == MotionEvent.ACTION_UP && !wasArmed) selectTab(tabs.indexOf(tab));
                return true;
            }
            return true;
        });
    }

    private int indexOfWeb(WebView view) { for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).view == view) return i; return -1; }

    private void selectTab(int index) {
        if (index<0||index>=tabs.size()) return; activeTabIndex=index;
        for (int i=0;i<tabs.size();i++) { BrowserTab t=tabs.get(i); boolean selected=i==index; t.view.setVisibility(selected?View.VISIBLE:View.GONE); int c=selected?Color.WHITE:Color.rgb(218,220,224); t.chip.setBackgroundColor(c); t.selectButton.setBackgroundTintList(ColorStateList.valueOf(c)); t.closeButton.setBackgroundTintList(ColorStateList.valueOf(c)); t.selectButton.setText("▣ "+t.title); }
        web=tabs.get(index).view; String u=web.getUrl(); address.setText(u==null?"":u); updateBookmarkButton(u);
    }

    private void closeTab(int index) {
        if (index<0||index>=tabs.size()) return;
        if (tabs.size()==1) { BrowserTab t=tabs.get(0); t.view.stopLoading(); t.title="خانه"; t.selectButton.setText("▣ "+t.title); loadHomePage(t.view); selectTab(0); return; }
        BrowserTab removed=tabs.remove(index); stage.removeView(removed.view); tabStrip.removeView(removed.chip); removed.view.stopLoading(); removed.view.destroy();
        if (index<activeTabIndex) activeTabIndex--; else if (index==activeTabIndex) activeTabIndex=Math.min(index,tabs.size()-1); selectTab(activeTabIndex);
    }

    private void changePageZoom(int delta) {
        if (web == null) return;
        int next = Math.max(40, Math.min(200, zoomPercent + delta));
        if (next == zoomPercent) {
            toast(next <= 40 ? "حداقل بزرگ‌نمایی ۴۰٪ است" : "حداکثر بزرگ‌نمایی ۲۰۰٪ است");
            return;
        }
        float factor = next / (float) zoomPercent;
        try {
            // zoomBy changes the live page scale without applying the scale twice.
            web.getSettings().setSupportZoom(true);
            web.getSettings().setBuiltInZoomControls(true);
            web.getSettings().setDisplayZoomControls(false);
            web.zoomBy(factor);
            zoomPercent = next;
            toast("بزرگ‌نمایی صفحه: " + zoomPercent + "%");
        } catch (Exception e) {
            toast("این صفحه اجازهٔ تغییر مقیاس را نمی‌دهد");
        }
    }

    // Each press cycles: floating 1 -> floating 2 -> floating 3 -> normal app window.
    // The first press immediately creates a genuinely smaller overlay, rather than a full-screen overlay.
    private void cycleBrowserWindowSize() {
        if (Build.VERSION.SDK_INT < 23) { toast("پنجره شناور به اندروید ۶ یا بالاتر نیاز دارد"); return; }
        if (!Settings.canDrawOverlays(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                toast("مجوز نمایش روی برنامه‌های دیگر را فعال کن، سپس دکمه پنجره را دوباره بزن");
            } catch (Exception e) { toast("تنظیم مجوز پنجره شناور باز نشد"); }
            return;
        }
        if (!floatingWindowActive) {
            try {
                if (root.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) root.getParent()).removeView(root);
                overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                overlayWindowParams = new WindowManager.LayoutParams(
                        Math.max(dp(240), (int)(getScreenWidthPx() * 0.82f)),
                        Math.max(dp(200), (int)(getScreenHeightPx() * 0.76f)), type,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                // Keep the overlay focusable so site forms and the Android keyboard can work.
                overlayWindowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                overlayWindowParams.gravity = Gravity.CENTER;
                overlayWindowManager.addView(root, overlayWindowParams);
                floatingWindowActive = true;
                windowSizeStep = 1;
                windowSizeButton.setText("▣۱");
                // Do not send the Activity to the back: it is the host we return to when the
                // overlay is removed, and moving it back can leave the user on the home screen.
                root.requestLayout();
                toast("پنجره کوچک ۱");
            } catch (Exception e) {
                floatingWindowActive = false;
                try { if (root.getParent() == null) screenHost.addView(root, new FrameLayout.LayoutParams(-1,-1)); } catch (Exception ignored) {}
                toast("ساخت پنجره شناور ناموفق بود؛ مجوز نمایش روی برنامه‌های دیگر را بررسی کن");
            }
            return;
        }
        if (windowSizeStep >= 3) {
            exitFloatingBrowserWindow();
            toast("بازگشت به اندازهٔ عادی");
            return;
        }
        windowSizeStep++;
        float[] widthFactors = {1.0f, 0.82f, 0.64f, 0.46f};
        float[] heightFactors = {1.0f, 0.76f, 0.58f, 0.42f};
        overlayWindowParams.width = Math.max(dp(220), (int)(getScreenWidthPx() * widthFactors[windowSizeStep]));
        overlayWindowParams.height = Math.max(dp(180), (int)(getScreenHeightPx() * heightFactors[windowSizeStep]));
        try {
            overlayWindowManager.updateViewLayout(root, overlayWindowParams);
            root.requestLayout();
            if (windowSizeButton != null) windowSizeButton.setText("▣" + windowSizeStep);
            String[] labels = {"اندازه عادی", "پنجره کوچک ۱", "پنجره کوچک ۲", "پنجره کوچک ۳"};
            toast(labels[windowSizeStep]);
        } catch (Exception e) {
            toast("تغییر اندازهٔ پنجره انجام نشد؛ مجوز شناور را بررسی کن");
        }
    }

    private int getScreenWidthPx() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) getDisplay().getRealMetrics(dm); else ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }
    private int getScreenHeightPx() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) getDisplay().getRealMetrics(dm); else ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private void exitFloatingBrowserWindow() {
        try {
            if (floatingWindowActive && overlayWindowManager != null && root.getParent() != null) overlayWindowManager.removeView(root);
        } catch (Exception ignored) {}
        floatingWindowActive = false; windowSizeStep = 0; overlayWindowParams = null;
        if (windowSizeButton != null) windowSizeButton.setText("▣");
        try {
            screenHost.removeAllViews();
            screenHost.addView(root, new FrameLayout.LayoutParams(-1,-1));
            setContentView(screenHost);
        } catch (Exception e) { toast("بازگرداندن پنجره به برنامه ناموفق بود"); }
    }

    private boolean isGoogleAuthenticationUrl(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        // Do not send all Google pages outside the app; only account/sign-in endpoints.
        return host.equals("accounts.google.com")
                || (host.equals("www.google.com") && (path.startsWith("/accounts/") || path.startsWith("/signin/")))
                || (host.equals("google.com") && (path.startsWith("/accounts/") || path.startsWith("/signin/")));
    }

    private void openUrlInSystemBrowser(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (Exception e) {
            toast("برای ورود امن گوگل، مرورگر دستگاه در دسترس نیست. Chrome یا یک مرورگر وب را نصب/فعال کن.");
        }
    }

    private void configureWeb(WebView target) {
        WebSettings s=target.getSettings();
        s.setJavaScriptEnabled(prefs().getBoolean("javascript_enabled",true));
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadsImagesAutomatically(prefs().getBoolean("photos_enabled",true));
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Keep website input fields at their normal text size; page zoom remains independent.
        s.setTextZoom(100);
        s.setDefaultTextEncodingName("UTF-8");
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        target.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        target.setSaveEnabled(true);
        if (Build.VERSION.SDK_INT >= 26) target.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        applyUserAgent(target);
        CookieManager cm=CookieManager.getInstance(); cm.setAcceptCookie(true); if (Build.VERSION.SDK_INT>=21) cm.setAcceptThirdPartyCookies(target,true);
        target.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri uri = r.getUrl();
                // Google blocks account/OAuth sign-in in embedded WebViews on many flows.
                // Hand only the authentication step to the device browser; normal sites remain in Mrorgar-Q.
                if (isGoogleAuthenticationUrl(uri)) {
                    openUrlInSystemBrowser(uri);
                    return true;
                }
                String scheme = uri.getScheme();
                if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                    openUrlInSystemBrowser(uri);
                    return true;
                }
                return false;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                Uri uri = Uri.parse(url);
                if (isGoogleAuthenticationUrl(uri)) {
                    openUrlInSystemBrowser(uri);
                    return true;
                }
                String scheme = uri.getScheme();
                if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                    openUrlInSystemBrowser(uri);
                    return true;
                }
                return false;
            }
            @Override public void onPageStarted(WebView v,String url,Bitmap icon) { updateTabInfo(target,url); }
            @Override public void onPageFinished(WebView v,String url) { updateTabInfo(target,url); if (url == null || !url.startsWith("https://mrorgar.home/")) addHistoryEntry(url); }
        });
        target.setWebChromeClient(new WebChromeClient() {
            @Override public void onRequestFocus(WebView v) {
                // Some login/OTP pages ask the host WebView to take focus before showing the IME.
                v.setFocusable(true);
                v.setFocusableInTouchMode(true);
                v.requestFocus(View.FOCUS_DOWN);
            }
            @Override public void onReceivedTitle(WebView v,String title) { if (title==null||title.trim().isEmpty()) return; for (BrowserTab t:tabs) if (t.view==target) { t.title=title.length()>20?title.substring(0,20):title; t.selectButton.setText("▣ "+t.title); break; } }
            @Override public void onProgressChanged(WebView v,int progress) { if (target==web) setTitle("Mrorgar-Q • "+progress+"%"); }
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if (uploadCallback!=null) { uploadCallback.onReceiveValue(null); uploadCallback=null; }
                uploadCallback=callback;
                try {
                    Intent intent=null;
                    try { intent=params.createIntent(); } catch (Exception ignored) { }
                    if (intent==null) {
                        intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        String[] accept=params.getAcceptTypes();
                        String type="*/*";
                        if (accept!=null) {
                            for (String a:accept) if (a!=null && !a.trim().isEmpty() && !a.equals("*/*")) { type=a; break; }
                        }
                        intent.setType(type);
                    }
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(intent,PICK_FILE);
                } catch (Exception e) {
                    uploadCallback=null;
                    callback.onReceiveValue(null);
                    toast("انتخاب‌گر فایل باز نشد؛ دوباره از دکمهٔ آپلود خود سایت امتحان کن");
                }
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
        target.getSettings().setUserAgentString(ua); target.getSettings().setUseWideViewPort(true); target.getSettings().setLoadWithOverviewMode(true); target.setInitialScale(desktopMode ? 40 : 0);
    }

    private void toggleDesktopMode() {
        desktopMode=!desktopMode; zoomPercent = desktopMode ? 40 : 100; prefs().edit().putBoolean("desktop_mode",desktopMode).apply(); updateDesktopButton();
        if (web!=null) { String u=web.getUrl(); applyUserAgent(web); if (u!=null&&!u.isEmpty()) web.reload(); }
        toast(desktopMode?"حالت دسکتاپ فعال شد":"حالت موبایل فعال شد");
    }

    private void updateDesktopButton() {
        if (top==null) return;
        for (int i=0;i<top.getChildCount();i++) { View v=top.getChildAt(i); if (v instanceof Button) { Button b=(Button)v; String t=b.getText().toString(); if (t.contains("Desktop")) { b.setText(desktopMode?"▤\nDesktop: ON":"▤\nDesktop"); b.setBackgroundTintList(ColorStateList.valueOf(desktopMode?Color.rgb(46,125,50):Color.rgb(45,46,49))); return; } } }
    }

    private void updateTabInfo(WebView target,String url) {
        if (url==null) return;
        boolean homePage = url.startsWith("https://mrorgar.home/");
        for (BrowserTab t:tabs) if (t.view==target) { t.lastUrl=url; if (homePage) t.title="خانه"; else if (t.title.equals("New tab")||t.title.startsWith("Tab ")||t.title.equals("خانه")) { String host=Uri.parse(url).getHost(); if (host!=null&&!host.isEmpty()) t.title=host.length()>20?host.substring(0,20):host; } t.selectButton.setText("▣ "+t.title); break; }
        if (target==web) { address.setText(homePage?"خانه | Mrorgar-Q":url); updateBookmarkButton(homePage?null:url); }
    }

    private void loadHomePage(WebView target) {
        if (target == null) return;
        String html = "<!doctype html><html lang='fa' dir='rtl'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=3'>" +
            "<title>خانه | Mrorgar-Q</title><style>" +
            "*{box-sizing:border-box}body{margin:0;background:#101820;color:#edf5f5;font-family:Arial,Tahoma,sans-serif}" +
            ".wrap{max-width:1050px;margin:auto;padding:22px 15px 36px}.hero{padding:24px 18px;border-radius:20px;background:linear-gradient(135deg,#075e59,#183c5a);box-shadow:0 8px 28px #0005}" +
            ".brand{font-size:27px;font-weight:800;margin:0 0 8px}.sub{color:#d0e5e6;font-size:14px;margin:0 0 18px}" +
            ".search{display:flex;gap:8px}.search input{min-width:0;flex:1;padding:13px 14px;border:1px solid #ffffff30;border-radius:12px;background:#fff;color:#18232b;font-size:15px;outline:none}" +
            ".search button{border:0;border-radius:12px;padding:0 17px;background:#f2c14e;color:#18232b;font-weight:bold;font-size:14px}" +
            ".section{margin-top:25px}.section h2{font-size:18px;margin:0 0 12px;color:#f2c14e}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:10px}" +
            ".card{display:block;text-decoration:none;color:#edf5f5;background:#1b2a35;border:1px solid #ffffff13;border-radius:14px;padding:14px 12px;min-height:77px;transition:background .15s}" +
            ".card:active{background:#294452}.emoji{font-size:22px;display:block;margin-bottom:7px}.name{font-size:14px;font-weight:bold}.desc{display:block;color:#a9c1c8;font-size:11px;margin-top:5px;direction:rtl}" +
            ".footer{text-align:center;color:#91a9b1;font-size:11px;margin-top:28px}a{outline-offset:3px}" +
            "</style></head><body><main class='wrap'><header class='hero'><h1 class='brand'>خانهٔ Mrorgar-Q</h1>" +
            "<p class='sub'>دسترسی سریع به هوش مصنوعی، پیام‌رسان‌ها، تلویزیون و رادیوی آنلاین، دانشنامه و ابزارها</p>" +
            "<form class='search' onsubmit='event.preventDefault();var q=document.getElementById(\"q\").value.trim();if(q)location.href=\"https://www.google.com/search?q=\"+encodeURIComponent(q)'>" +
            "<input id='q' type='search' placeholder='جستجو در وب یا وارد کردن پرسش…' autocomplete='off'><button type='submit'>جستجو</button></form></header>" +
            "<section class='section'><h2>دسترسی سریع</h2><div class='grid'>" +
            card("🔎","Google","جست‌وجوی وب","https://www.google.com/") +
            "</div></section><section class='section'><h2>هوش مصنوعی</h2><div class='grid'>" +
            card("🤖","ChatGPT","هوش مصنوعی OpenAI","https://chatgpt.com/") +
            card("✨","GapGPT","هوش مصنوعی","https://gapgpt.app/") +
            card("🧠","هوشا","هوش مصنوعی فارسی","https://hoosha.com/") +
            card("♊","Google Gemini","دستیار هوشمند","https://gemini.google.com/") +
            card("🟣","Claude","دستیار هوشمند","https://claude.ai/") +
            card("🔷","Microsoft Copilot","دستیار هوشمند","https://copilot.microsoft.com/") +
            card("🌊","DeepSeek","گفت‌وگو و جست‌وجو","https://chat.deepseek.com/") +
            "</div></section><section class='section'><h2>پیام‌رسان‌های ایرانی</h2><div class='grid'>" +
            card("💬","روبیکا","پیام‌رسان و رسانه","https://rubika.ir/") +
            card("🟠","بله","پیام‌رسان","https://ble.ir/") +
            card("🟦","ایتا","پیام‌رسان","https://eitaa.com/") +
            card("🟢","گپ","پیام‌رسان","https://gap.im/") +
            card("📨","آی‌گپ","پیام‌رسان","https://igap.net/") +
            card("🟣","سروش‌پلاس","پیام‌رسان","https://splus.ir/") +
            "</div></section><section class='section'><h2>پیام‌رسان‌های جهانی</h2><div class='grid'>" +
            card("✈️","Telegram","تلگرام وب","https://web.telegram.org/") +
            card("🟩","WhatsApp","واتساپ وب","https://web.whatsapp.com/") +
            card("🔵","Facebook Messenger","پیام‌رسان","https://www.messenger.com/") +
            card("🟦","Discord","گفت‌وگو و انجمن‌ها","https://discord.com/app") +
            card("🔒","Signal","پیام‌رسان امن","https://signal.org/") +
            card("📷","Instagram","شبکه اجتماعی","https://www.instagram.com/") +
            "</div></section><section class='section'><h2>تلویزیون و ویدئو</h2><div class='grid'>" +
            card("▶️","YouTube","ویدئو و پخش زنده","https://www.youtube.com/") +
            card("🌍","DW","خبر و پخش زنده","https://www.dw.com/") +
            card("📺","France 24","خبر جهانی","https://www.france24.com/") +
            card("📡","Al Jazeera","خبر و برنامه‌ها","https://www.aljazeera.com/") +
            card("🎞️","Plex","تلویزیون اینترنتی","https://www.plex.tv/") +
            card("📺","Pluto TV","تلویزیون آنلاین","https://pluto.tv/") +
            "</div></section><section class='section'><h2>رادیوی جهانی</h2><div class='grid'>" +
            card("🌐","Radio Garden","رادیوهای سراسر جهان","https://radio.garden/") +
            card("🎧","TuneIn","رادیو، خبر و موسیقی","https://tunein.com/") +
            card("📻","BBC Audio","رادیو و پادکست","https://www.bbc.com/audio") +
            card("📰","VOA","صدای آمریکا","https://www.voanews.com/") +
            card("🎵","NPR","رادیو و پادکست","https://www.npr.org/") +
            "</div></section><section class='section'><h2>دانش، آرشیو و ابزارها</h2><div class='grid'>" +
            card("📚","Wikipedia","دانشنامهٔ آزاد","https://www.wikipedia.org/") +
            card("🗄️","Internet Archive","کتاب، رسانه و آرشیو وب","https://archive.org/") +
            card("🕰️","Wayback Machine","نسخه‌های قدیمی سایت‌ها","https://web.archive.org/") +
            card("🐙","GitHub","مخزن کد و پروژه‌ها","https://github.com/") +
            card("🦊","MDN Web Docs","راهنمای توسعه وب","https://developer.mozilla.org/") +
            "</div></section><p class='footer'>برای باز کردن هر سرویس، کارت آن را لمس کنید. دسترسی به بعضی سایت‌ها ممکن است به شبکه یا حساب کاربری وابسته باشد.</p></main></body></html>";
        target.loadDataWithBaseURL("https://mrorgar.home/", html, "text/html", "UTF-8", null);
        for (BrowserTab t : tabs) if (t.view == target) { t.title = "خانه"; t.selectButton.setText("▣ "+t.title); t.lastUrl = "https://mrorgar.home/"; break; }
        if (target == web && address != null) address.setText("خانه | Mrorgar-Q");
    }

    private String card(String icon, String name, String description, String url) {
        return "<a class='card' href='" + url + "'><span class='emoji'>" + icon + "</span><span class='name'>" + name + "</span><span class='desc'>" + description + "</span></a>";
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

    // Opens a site's real file input. Searches ordinary DOM, open shadow roots, and same-origin frames.
    private void pickAnyFile() {
        final WebView current=web;
        if (current==null) { toast("ابتدا یک صفحه را باز کن"); return; }
        final String js = "(function(){try{"+
            "var seen=[],roots=[];"+
            "function addRoot(r){if(!r||seen.indexOf(r)>=0)return;seen.push(r);roots.push(r);var all=[];try{all=r.querySelectorAll('*')}catch(e){};"+
            "for(var i=0;i<all.length;i++){try{if(all[i].shadowRoot)addRoot(all[i].shadowRoot);if(all[i].tagName==='IFRAME'&&all[i].contentDocument)addRoot(all[i].contentDocument)}catch(e){}}}"+
            "addRoot(document);"+
            "function all(sel){var out=[];for(var r=0;r<roots.length;r++){try{var a=roots[r].querySelectorAll(sel);for(var j=0;j<a.length;j++)out.push(a[j])}catch(e){}}return out}"+
            "var inputs=all('input[type=file]');for(var n=0;n<inputs.length;n++){var x=inputs[n];if(!x.disabled){try{x.click();return 'clicked_input'}catch(e){}}}"+
            "var labels=all('label[for]');for(var l=0;l<labels.length;l++){var tx=(labels[l].innerText||labels[l].textContent||'').toLowerCase();if(/file|upload|attach|browse|choose|پیوست|بارگذار|انتخاب فایل|افزودن فایل/.test(tx)){try{labels[l].click();return 'clicked_trigger'}catch(e){}}}"+
            "var els=all('button,[role=button],input[type=button],input[type=submit],[title],[aria-label]');for(var k=0;k<els.length;k++){var e=els[k],t=((e.innerText||e.value||'')+' '+(e.title||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();if(/upload|attach|choose file|select file|browse|add file|پیوست|بارگذاری|انتخاب فایل|افزودن فایل/.test(t)&&!e.disabled){try{e.click();return 'clicked_trigger'}catch(err){}}}"+
            "return 'no_input';}catch(e){return 'error';}})()";
        current.evaluateJavascript(js,result -> {
            if (current!=web) { toast("زبانه عوض شده است؛ دوباره دکمهٔ File را بزن"); return; }
            if (result==null || result.contains("no_input"))
                toast("در این صفحه فرم انتخاب فایل پیدا نشد؛ ابتدا دکمهٔ پیوست/آپلود خود سایت را باز کن");
            else if (result.contains("error"))
                toast("بازکردن فرم فایل ناموفق بود؛ از دکمهٔ آپلود داخل خود سایت امتحان کن");
            else if (result.contains("clicked_trigger"))
                toast("دکمهٔ آپلود سایت فعال شد؛ اگر انتخاب‌گر باز نشد، دکمهٔ فایل داخل خود سایت را لمس کن");
            else if (result.contains("clicked_input"))
                toast("درخواست انتخاب فایل به سایت داده شد");
        });
    }

    // Prefer the server's real Content-Disposition filename, then the URL's actual path.
    // The MIME type is used only to add a missing extension, not to replace an existing one.
    private String resolveDownloadFileName(String url,String disposition,String mimeType) {
        String candidate = null;
        if (disposition != null && !disposition.trim().isEmpty()) {
            String lower = disposition.toLowerCase(java.util.Locale.ROOT);
            int star = lower.indexOf("filename*=");
            if (star >= 0) {
                String value = disposition.substring(star + 10).trim();
                int semi = value.indexOf(';'); if (semi >= 0) value = value.substring(0, semi).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length()-1);
                int marker = value.indexOf("''"); if (marker >= 0) value = value.substring(marker + 2);
                candidate = Uri.decode(value);
            }
            if (candidate == null || candidate.trim().isEmpty()) {
                int at = lower.indexOf("filename=");
                if (at >= 0) {
                    String value = disposition.substring(at + 9).trim();
                    int semi = value.indexOf(';'); if (semi >= 0) value = value.substring(0, semi).trim();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length()-1);
                    candidate = value;
                }
            }
        }
        if (candidate == null || candidate.trim().isEmpty()) {
            try {
                Uri uri = Uri.parse(url);
                String path = uri.getLastPathSegment();
                if (path != null && !path.trim().isEmpty()) candidate = Uri.decode(path);
                if ((candidate == null || candidate.trim().isEmpty() || candidate.equalsIgnoreCase("download") || candidate.equalsIgnoreCase("file")) && uri.getQueryParameterNames() != null) {
                    String[] keys = {"filename", "file", "name", "download"};
                    for (String key : keys) { String value = uri.getQueryParameter(key); if (value != null && !value.trim().isEmpty()) { candidate = Uri.decode(value); break; } }
                }
            } catch (Exception ignored) { }
        }
        if (candidate == null || candidate.trim().isEmpty() || candidate.equals("/") || candidate.equalsIgnoreCase("index.php")) {
            candidate = URLUtil.guessFileName(url, null, mimeType);
        }
        candidate = candidate == null ? "" : candidate.trim().replace('\\','/');
        int slash = candidate.lastIndexOf('/'); if (slash >= 0) candidate = candidate.substring(slash + 1);
        candidate = candidate.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (candidate.isEmpty() || candidate.equals(".") || candidate.equals("..")) candidate = URLUtil.guessFileName(url, disposition, mimeType);
        if (candidate == null || candidate.trim().isEmpty()) {
            String host = Uri.parse(url).getHost(); candidate = (host == null || host.isEmpty() ? "download" : host.replaceAll("[^A-Za-z0-9._-]", "_")) + "-download";
        }
        if (candidate.lastIndexOf('.') <= 0 && mimeType != null && !mimeType.trim().isEmpty()) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.split(";")[0].trim());
            if (ext != null && !ext.isEmpty()) candidate += "." + ext;
        }
        return candidate;
    }

    private void enqueueDownload(String url,String userAgent,String disposition,String mimeType) {
        if (url==null||url.trim().isEmpty()) { toast("آدرس دانلود معتبر نیست"); return; }
        try {
            Uri uri=Uri.parse(url); String scheme=uri.getScheme();
            if (scheme==null||!(scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https"))) { toast("این نوع آدرس برای دانلود پشتیبانی نمی‌شود"); return; }
            String name=resolveDownloadFileName(url,disposition,mimeType);
            DownloadManager.Request r=new DownloadManager.Request(uri); r.setTitle(name); r.setDescription("Mrorgar-Q Downloader"); r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); r.setAllowedOverMetered(true); r.setAllowedOverRoaming(true);
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
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(22),dp(8),dp(22),dp(4));
        Switch js = new Switch(this); js.setText("JavaScript"); js.setTextSize(16); js.setPadding(0,dp(10),0,dp(10)); js.setChecked(prefs().getBoolean("javascript_enabled",true));
        Switch photos = new Switch(this); photos.setText("Photo"); photos.setTextSize(16); photos.setPadding(0,dp(10),0,dp(10)); photos.setChecked(prefs().getBoolean("photos_enabled",true));
        panel.addView(js,new LinearLayout.LayoutParams(-1,-2)); panel.addView(photos,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Setup — تنظیمات واقعی").setMessage("تغییر هر کلید ذخیره می‌شود و روی زبانه‌های مرورگر اعمال می‌شود.").setView(panel).setPositiveButton("بستن",null).create();
        js.setOnCheckedChangeListener((button,checked)->{prefs().edit().putBoolean("javascript_enabled",checked).apply();applyWebPreferences();});
        photos.setOnCheckedChangeListener((button,checked)->{prefs().edit().putBoolean("photos_enabled",checked).apply();applyWebPreferences();});
        dialog.show();
    }

    private void applyWebPreferences() {
        boolean js=prefs().getBoolean("javascript_enabled",true), photos=prefs().getBoolean("photos_enabled",true);
        for(BrowserTab t:tabs){WebSettings settings=t.view.getSettings();settings.setJavaScriptEnabled(js);settings.setLoadsImagesAutomatically(photos);}
        if(web!=null){web.reload();}
        toast("تنظیمات ذخیره و اعمال شد");
    }

    private java.util.List<String> getHistoryEntries() {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString("history_urls", "[]"));
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (!url.isEmpty()) result.add(url);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void addHistoryEntry(String url) {
        if (url == null || url.trim().isEmpty() || "about:blank".equals(url)) return;
        java.util.List<String> entries = getHistoryEntries();
        // Keep the newest visit first and avoid consecutive duplicate entries.
        if (!entries.isEmpty() && url.equals(entries.get(0))) return;
        entries.remove(url);
        entries.add(0, url);
        while (entries.size() > 200) entries.remove(entries.size() - 1);
        JSONArray array = new JSONArray();
        for (String entry : entries) array.put(entry);
        prefs().edit().putString("history_urls", array.toString()).apply();
    }

    private void showHistory() {
        java.util.List<String> items = getHistoryEntries();
        if (items.isEmpty()) { toast("تاریخچه خالی است"); return; }
        String[] labels = items.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("History — تاریخچه؛ جدیدترین‌ها در بالا")
            .setItems(labels, (dialog, which) -> {
                String url = items.get(which);
                if (web != null) { address.setText(url); web.loadUrl(url); }
            })
            .setNeutralButton("پاک‌کردن تاریخچه", (dialog, which) -> {
                prefs().edit().putString("history_urls", "[]").apply();
                toast("تاریخچه پاک شد");
            })
            .setNegativeButton("بستن", null).show();
    }

    private java.util.Set<String> getBookmarks() {
        return new java.util.HashSet<>(prefs().getStringSet("bookmarks",new java.util.HashSet<>()));
    }

    private void updateBookmarkButton(String url) {
        if(bookmarkButton==null)return;
        boolean saved=url!=null&&!url.isEmpty()&&getBookmarks().contains(url);
        bookmarkButton.setText(saved?"★":"☆"); bookmarkButton.setTextColor(saved?Color.rgb(255,170,0):Color.rgb(90,90,90));
        bookmarkButton.setContentDescription(saved?"حذف نشانک":"افزودن نشانک");
    }

    private void toggleBookmark() {
        String url=web==null?null:web.getUrl();
        if(url==null||url.trim().isEmpty()){toast("آدرسی برای نشانک‌گذاری نیست");return;}
        java.util.Set<String> saved=getBookmarks();
        if(saved.contains(url)){saved.remove(url);prefs().edit().putStringSet("bookmarks",saved).apply();toast("نشانک حذف شد");}
        else{saved.add(url);prefs().edit().putStringSet("bookmarks",saved).apply();toast("نشانک ذخیره شد");}
        updateBookmarkButton(url);
    }

    private void showBookmarks() {
        java.util.List<String> items=new java.util.ArrayList<>(getBookmarks());
        if(items.isEmpty()){toast("هنوز نشانکی ذخیره نشده");return;}
        String[] labels=items.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("نشانک‌ها — برای بازکردن لمس کن").setItems(labels,(d,which)->{if(web!=null){web.loadUrl(items.get(which));address.setText(items.get(which));}})
                .setNeutralButton("پاک‌کردن همه",(d,w)->{prefs().edit().putStringSet("bookmarks",new java.util.HashSet<>()).apply();updateBookmarkButton(web==null?null:web.getUrl());toast("همهٔ نشانک‌ها پاک شدند");})
                .setNegativeButton("بستن",null).show();
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
