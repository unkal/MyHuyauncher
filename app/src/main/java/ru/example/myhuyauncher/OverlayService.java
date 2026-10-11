package ru.example.myhuyauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends Service {
    static final String TAG = "MyHuyauncher";
    private static final int BUTTON_PX = 40;
    private static final int BUTTON_MARGIN_PX = 16;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "overlay";
    private static final String EXTRA_REVEAL = "reveal";

    private WindowManager windowManager;
    private ExecutorService loader;
    private Handler mainHandler;
    private View buttonView;
    private View panelView;
    private int windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private int panelToken;
    private int buttonAttempts;
    private final Runnable buttonRetry = this::ensureButton;

    static void start(Context context) {
        launch(context, false);
    }

    static void reveal(Context context) {
        launch(context, true);
    }

    private static void launch(Context context, boolean reveal) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.putExtra(EXTRA_REVEAL, reveal);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "startForegroundService failed", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        loader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "myhuyauncher-apps");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent != null && intent.getBooleanExtra(EXTRA_REVEAL, false)) {
            WidgetState.setHidden(this, false);
            buttonAttempts = 0;
        }
        if (WidgetState.isHidden(this)) {
            Log.i(TAG, "widget stays hidden");
            removePanel();
            removeButton();
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureButton();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        panelToken++;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(buttonRetry);
        }
        removePanel();
        removeButton();
        if (loader != null) {
            loader.shutdownNow();
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_apps)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void ensureButton() {
        if (WidgetState.isHidden(this)) {
            return;
        }
        if (buttonView != null) {
            buttonAttempts = 0;
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            scheduleButtonRetry("overlay permission missing");
            return;
        }

        FabView button = new FabView(this);
        button.setContentDescription(getString(R.string.overlay_button));
        button.setOnClickListener(v -> togglePanel());

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                BUTTON_PX,
                BUTTON_PX,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = BUTTON_MARGIN_PX;
        lp.y = BUTTON_MARGIN_PX;

        if (!addOverlay(button, lp)) {
            scheduleButtonRetry("button overlay was rejected");
            return;
        }
        buttonAttempts = 0;
        buttonView = button;
        Log.i(TAG, "button shown " + BUTTON_PX + "px bottom-end");
    }

    private void scheduleButtonRetry(String reason) {
        buttonAttempts++;
        Log.w(TAG, reason + ", attempt " + buttonAttempts);
        if (buttonAttempts >= 8) {
            stopSelf();
            return;
        }
        mainHandler.removeCallbacks(buttonRetry);
        mainHandler.postDelayed(buttonRetry, 2000);
    }

    private boolean addOverlay(View view, WindowManager.LayoutParams lp) {
        int[] types = new int[] {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.TYPE_PHONE
        };
        for (int type : types) {
            lp.type = type;
            try {
                windowManager.addView(view, lp);
                windowType = type;
                Log.i(TAG, "added overlay type " + type);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "overlay type " + type + " rejected", e);
                if (view.getParent() != null) {
                    try {
                        windowManager.removeView(view);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return false;
    }

    private void togglePanel() {
        if (panelView != null) {
            removePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        if (panelView != null) {
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cardWidth = dm.widthPixels - dp(32);
        int maxCardHeight = (int) (dm.heightPixels * 0.72f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> removePanel());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xF01C1E26);
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView status = new TextView(this);
        status.setText(R.string.loading);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(16), dp(12), dp(16));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        GridLayout userGrid = new GridLayout(this);
        TextView otherButton = buildOtherButton();
        GridLayout otherGrid = new GridLayout(this);
        otherGrid.setVisibility(View.GONE);
        otherButton.setOnClickListener(v -> {
            boolean show = otherGrid.getVisibility() != View.VISIBLE;
            otherGrid.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        content.addView(userGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams otherButtonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int otherMargin = dp(8);
        otherButtonLp.setMargins(otherMargin, otherMargin, otherMargin, otherMargin);
        content.addView(otherButton, otherButtonLp);
        content.addView(otherGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        MaxScroll scroll = new MaxScroll(this, maxCardHeight);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(buildHideButton());

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(card, cardLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(root, lp);
        } catch (RuntimeException e) {
            Log.e(TAG, "panel overlay rejected", e);
            return;
        }
        panelView = root;

        final int token = ++panelToken;
        loader.execute(() -> {
            List<AppItem> apps = queryApps();
            mainHandler.post(() -> {
                if (token != panelToken || panelView == null) {
                    return;
                }
                bindApps(card, status, userGrid, otherButton, otherGrid, apps, cardWidth);
            });
        });
    }

    private TextView buildHideButton() {
        TextView button = buildBarButton(R.string.hide_widget);
        button.setOnClickListener(v -> hideWidget());
        return button;
    }

    private void hideWidget() {
        WidgetState.setHidden(this, true);
        buttonAttempts = 0;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(buttonRetry);
        }
        Log.i(TAG, "widget hidden");
        removePanel();
        removeButton();
        stopSelf();
    }

    private TextView buildBarButton(int textRes) {
        TextView button = new TextView(this);
        button.setText(textRes);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        int pad = dp(14);
        button.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x44FFFFFF);
        bg.setCornerRadius(dp(14));
        button.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = dp(8);
        lp.setMargins(margin, margin, margin, margin);
        button.setLayoutParams(lp);
        return button;
    }

    private TextView buildOtherButton() {
        TextView button = buildBarButton(R.string.other_apps);
        button.setVisibility(View.GONE);
        return button;
    }

    private void bindApps(
            LinearLayout card,
            TextView status,
            GridLayout userGrid,
            TextView otherButton,
            GridLayout otherGrid,
            List<AppItem> apps,
            int cardWidth) {
        List<AppItem> userApps = new ArrayList<>();
        List<AppItem> otherApps = new ArrayList<>();
        for (AppItem item : apps) {
            if (item.userApp) {
                userApps.add(item);
            } else {
                otherApps.add(item);
            }
        }
        if (userApps.isEmpty() && otherApps.isEmpty()) {
            status.setText(R.string.no_apps);
            return;
        }
        card.removeView(status);

        int inner = cardWidth - dp(16);
        int columns = Math.max(3, inner / dp(160));
        int cellWidth = inner / columns;
        int iconPx = Math.min(dp(96), cellWidth - dp(24));
        fillGrid(userGrid, userApps, columns, cellWidth, iconPx);
        fillGrid(otherGrid, otherApps, columns, cellWidth, iconPx);
        otherButton.setVisibility(otherApps.isEmpty() ? View.GONE : View.VISIBLE);
        Log.i(TAG, "panel user " + userApps.size() + " other " + otherApps.size());
    }

    private void fillGrid(GridLayout grid, List<AppItem> apps, int columns, int cellWidth, int iconPx) {
        grid.setColumnCount(columns);
        for (AppItem item : apps) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = dp(6);
            cell.setPadding(pad, pad, pad, pad);
            GradientDrawable tile = new GradientDrawable();
            tile.setColor(0x33FFFFFF);
            tile.setCornerRadius(dp(16));
            cell.setBackground(tile);
            cell.setContentDescription(item.label);
            cell.setOnClickListener(v -> launch(item));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(item.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
            iconLp.gravity = Gravity.CENTER_HORIZONTAL;

            TextView label = new TextView(this);
            label.setText(item.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(4);

            cell.addView(icon, iconLp);
            cell.addView(label, labelLp);

            GridLayout.LayoutParams cellLp = new GridLayout.LayoutParams();
            cellLp.width = cellWidth - dp(8);
            cellLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            cellLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(cell, cellLp);
        }
    }

    private List<AppItem> queryApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(probe, 0);
        List<AppItem> items = new ArrayList<>();
        String self = getPackageName();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || self.equals(info.activityInfo.packageName)) {
                continue;
            }
            if (!info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            String name = label == null || label.length() == 0
                    ? info.activityInfo.packageName
                    : label.toString();
            Drawable icon = info.loadIcon(pm);
            if (icon != null) {
                icon = icon.mutate();
            }
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setComponent(new ComponentName(
                    info.activityInfo.packageName,
                    info.activityInfo.name));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            int flags = info.activityInfo.applicationInfo.flags;
            boolean userApp = (flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            items.add(new AppItem(name, info.activityInfo.packageName, icon, launch, userApp));
        }
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        items.sort((a, b) -> collator.compare(a.label, b.label));
        return items;
    }

    private void launch(AppItem item) {
        Log.i(TAG, "launch " + item.packageName + " / " + item.label);
        removePanel();
        try {
            startActivity(item.launchIntent);
        } catch (RuntimeException e) {
            Log.e(TAG, "launch failed " + item.packageName, e);
        }
    }

    private void removePanel() {
        panelToken++;
        if (panelView == null) {
            return;
        }
        try {
            windowManager.removeView(panelView);
        } catch (RuntimeException ignored) {
        }
        panelView = null;
    }

    private void removeButton() {
        if (buttonView == null) {
            return;
        }
        try {
            windowManager.removeView(buttonView);
        } catch (RuntimeException ignored) {
        }
        buttonView = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AppItem {
        final String label;
        final String packageName;
        final Drawable icon;
        final Intent launchIntent;
        final boolean userApp;

        AppItem(String label, String packageName, Drawable icon, Intent launchIntent, boolean userApp) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
            this.launchIntent = launchIntent;
            this.userApp = userApp;
        }
    }

    private static final class FabView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final float REST_ALPHA = 0.62f;

        FabView(Context context) {
            super(context);
            fill.setColor(Color.WHITE);
            glyph.setColor(0xFF1A1A1A);
            setAlpha(REST_ALPHA);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = w / 5f;
            canvas.drawRoundRect(0, 0, w, h, radius, radius, fill);

            float pad = w * 0.22f;
            float gap = w * 0.08f;
            float size = (w - pad * 2f - gap) / 2f;
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    float left = pad + col * (size + gap);
                    float top = pad + row * (size + gap);
                    canvas.drawRoundRect(left, top, left + size, top + size, 2f, 2f, glyph);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                setAlpha(0.92f);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                setAlpha(REST_ALPHA);
            }
            return super.onTouchEvent(event);
        }
    }

    private static final class MaxScroll extends ScrollView {
        private final int maxHeight;

        MaxScroll(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int cap = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, cap);
        }
    }
}
