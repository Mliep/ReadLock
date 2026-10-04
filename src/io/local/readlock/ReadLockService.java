package io.local.readlock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Map;

/**
 * 专为 Android 16 量身定制的极致省电 & 高稳定性阅读守护服务：
 * 1. 0 轮询休眠设计：白天与达标状态下完全停用 Handler 循环，0 CPU 唤醒，依靠 AlarmManager 精准整点唤醒与无障碍事件驱动。
 * 2. 补救时段智能轮询：仅在处于夜间锁定期间启动 60 秒轮询；一旦读满 30 分钟立即解封并停掉轮询。
 * 3. 跨午夜（00:00~03:00）精准结算：严格考核昨日时长 + 凌晨补救时长，杜绝午夜误锁。
 */
public class ReadLockService extends Service {

    public static final String READ_PACKAGE = "com.tencent.weread";
    public static final String SELF_PACKAGE = "io.local.readlock";
    public static final int TARGET_MINUTES = 30;
    public static final int LOCK_START_MIN = 22 * 60;
    public static final int LOCK_END_MIN = 3 * 60;
    public static final long TICK_MS = 60_000;

    private static final String CHANNEL_ID = "guard";
    private static final int NOTIF_ID = 1;

    private static volatile ReadLockService instance;

    /** 供无障碍服务/小组件读取的运行时状态 */
    private static volatile boolean locked = false;
    private static volatile long minutesCache = -1;
    private static volatile boolean exemptForeground = false;
    private static volatile boolean usagePermissionOk = true;

    private Handler handler;
    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams overlayLp;
    private boolean overlayAdded;
    private TextView overlayTitle, overlayCountdown, overlayProgress;
    private long lastWidgetMinutes = Long.MIN_VALUE;
    private boolean lastWidgetLocked;

    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            try {
                doTick();
            } catch (Throwable t) {
                android.util.Log.w("ReadLock", "tick failed", t);
            }
        }
    };

    private final BroadcastReceiver timeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.util.Log.i("ReadLock", "Timezone or system time changed, refreshing status...");
            doTick();
        }
    };

    // ---------- 生命周期 ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundWithNotification();
        scheduleExactAlarms();

        try {
            IntentFilter tf = new IntentFilter();
            tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            tf.addAction(Intent.ACTION_TIME_CHANGED);
            registerReceiver(timeChangeReceiver, tf, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            android.util.Log.w("ReadLock", "failed to register timeChangeReceiver", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(tickTask);
        handler.post(tickTask);
        scheduleExactAlarms();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(timeChangeReceiver);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        removeOverlay();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    // ---------- 核心逻辑 ----------

    public static void checkNow() {
        ReadLockService s = instance;
        if (s != null && s.handler != null) {
            s.handler.post(() -> s.doTick());
        }
    }

    private void doTick() {
        Calendar now = Calendar.getInstance();
        long minutes = getEffectiveReadingMinutes(now);
        usagePermissionOk = (minutes >= 0);
        minutesCache = minutes;

        boolean shouldLock = usagePermissionOk && shouldLockNow(now, minutes);
        if (shouldLock != locked) {
            locked = shouldLock;
            exemptForeground = false;
            if (locked) {
                addOverlay();
                Toast.makeText(this, "读书锁：今日未达标，手机已锁定", Toast.LENGTH_LONG).show();
            } else {
                removeOverlay();
                Toast.makeText(this, "读书锁：今日阅读已达标，已解锁！", Toast.LENGTH_LONG).show();
            }
        }

        if (locked) {
            updateOverlayTexts();
            // 锁定中且需补救，保持 60 秒轮询以便达标时立即解封
            handler.removeCallbacks(tickTask);
            handler.postDelayed(tickTask, TICK_MS);
        } else {
            // 未锁定（白天或已达标）：停掉轮询，进入 0 耗电休眠状态
            handler.removeCallbacks(tickTask);
        }

        updateNotification(minutes, locked);
        updateWidget(minutes, locked, false);
        MainActivity.updateFromService(minutes, locked);
        scheduleExactAlarms();
    }

    /** 锁定判定（纯函数）：锁定窗口内 且 对应统计时长 < 目标 */
    static boolean shouldLockNow(Calendar now, long minutesOfStatDay) {
        if (!inLockWindow(now)) return false;
        return minutesOfStatDay >= 0 && minutesOfStatDay < TARGET_MINUTES;
    }

    /** 22:00–次日 3:00 为锁定窗口 */
    static boolean inLockWindow(Calendar c) {
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return m >= LOCK_START_MIN || m < LOCK_END_MIN;
    }

    /**
     * 计算当前考核所需的有效阅读时长：
     * 1. 00:00 - 03:00（凌晨锁定窗口）：考核昨天的总阅读时长；若昨天未满 30 分钟，加上今天凌晨补读的时长。
     * 2. 其他时段（白天及 22:00 - 24:00）：考核今天 00:00 至今的时长。
     */
    private long getEffectiveReadingMinutes(Calendar now) {
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (m < LOCK_END_MIN) {
            Calendar yesterdayStart = (Calendar) now.clone();
            yesterdayStart.add(Calendar.DAY_OF_YEAR, -1);
            yesterdayStart.set(Calendar.HOUR_OF_DAY, 0);
            yesterdayStart.set(Calendar.MINUTE, 0);
            yesterdayStart.set(Calendar.SECOND, 0);
            yesterdayStart.set(Calendar.MILLISECOND, 0);

            Calendar yesterdayEnd = (Calendar) yesterdayStart.clone();
            yesterdayEnd.set(Calendar.HOUR_OF_DAY, 23);
            yesterdayEnd.set(Calendar.MINUTE, 59);
            yesterdayEnd.set(Calendar.SECOND, 59);
            yesterdayEnd.set(Calendar.MILLISECOND, 999);

            long yMinutes = readMinutes(yesterdayStart.getTimeInMillis(), yesterdayEnd.getTimeInMillis());
            if (yMinutes < 0) return -1;
            if (yMinutes >= TARGET_MINUTES) {
                return yMinutes; // 昨天已达标，今晚整晚自由
            }

            // 昨天未达标，加上今天凌晨（00:00 到当前）的补读时长
            Calendar todayStart = (Calendar) now.clone();
            todayStart.set(Calendar.HOUR_OF_DAY, 0);
            todayStart.set(Calendar.MINUTE, 0);
            todayStart.set(Calendar.SECOND, 0);
            todayStart.set(Calendar.MILLISECOND, 0);
            long todayEarly = readMinutes(todayStart.getTimeInMillis(), now.getTimeInMillis());
            if (todayEarly < 0) return -1;

            return yMinutes + todayEarly;
        } else {
            Calendar todayStart = (Calendar) now.clone();
            todayStart.set(Calendar.HOUR_OF_DAY, 0);
            todayStart.set(Calendar.MINUTE, 0);
            todayStart.set(Calendar.SECOND, 0);
            todayStart.set(Calendar.MILLISECOND, 0);
            return readMinutes(todayStart.getTimeInMillis(), now.getTimeInMillis());
        }
    }

    /** 统计指定时间区间内微信读书的前台使用分钟数；无权限返回 -1 */
    private long readMinutes(long from, long to) {
        if (to <= from) return 0;
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return -1;

            long aggregatedMs = 0;
            try {
                Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(from, to);
                if (statsMap != null && statsMap.containsKey(READ_PACKAGE)) {
                    UsageStats us = statsMap.get(READ_PACKAGE);
                    if (us != null) {
                        aggregatedMs = us.getTotalTimeInForeground();
                    }
                }
            } catch (Throwable ignored) {
            }

            UsageEvents events = usm.queryEvents(from, to);
            if (events == null) return aggregatedMs / 60000;

            UsageEvents.Event e = new UsageEvents.Event();
            long eventsTotal = 0, lastResume = -1;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (!READ_PACKAGE.equals(e.getPackageName())) continue;
                int type = e.getEventType();
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (lastResume > 0) {
                        eventsTotal += e.getTimeStamp() - lastResume;
                    }
                    lastResume = e.getTimeStamp();
                } else if ((type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED)
                        && lastResume > 0) {
                    eventsTotal += e.getTimeStamp() - lastResume;
                    lastResume = -1;
                }
            }
            if (lastResume > 0) eventsTotal += to - lastResume; // 正在阅读

            long bestMs = Math.max(aggregatedMs, eventsTotal);
            return bestMs / 60000;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------- 覆盖层 ----------

    private void addOverlay() {
        if (overlayAdded || !Settings.canDrawOverlays(this)) return;
        overlay = LayoutInflater.from(this).inflate(R.layout.overlay_lock, null);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlayTitle = overlay.findViewById(R.id.overlay_title);
        overlayCountdown = overlay.findViewById(R.id.overlay_countdown);
        overlayProgress = overlay.findViewById(R.id.overlay_progress);

        Button btnRead = overlay.findViewById(R.id.btn_read);
        Button btnCall = overlay.findViewById(R.id.btn_call);
        Button btnSms = overlay.findViewById(R.id.btn_sms);
        btnRead.setOnClickListener(v -> {
            Intent readIntent = getPackageManager().getLaunchIntentForPackage(READ_PACKAGE);
            if (readIntent == null) {
                readIntent = new Intent(Intent.ACTION_MAIN);
                readIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                readIntent.setComponent(new ComponentName(READ_PACKAGE, "com.tencent.weread.LauncherActivity"));
            }
            launch(readIntent);
        });
        btnCall.setOnClickListener(v -> launch(new Intent(Intent.ACTION_DIAL)));
        btnSms.setOnClickListener(v -> {
            Intent smsIntent = new Intent(Intent.ACTION_MAIN);
            smsIntent.addCategory(Intent.CATEGORY_APP_MESSAGING);
            launch(smsIntent);
        });

        // 全面屏沉浸式覆盖：覆盖刘海屏、打孔屏和手势导航栏
        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        overlayLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            wm.addView(overlay, overlayLp);
            overlayAdded = true;
            applyAllowState();
            updateOverlayTexts();
        } catch (Throwable t) {
            android.util.Log.w("ReadLock", "add overlay failed", t);
        }
    }

    private void removeOverlay() {
        if (overlay != null && overlayAdded) {
            try {
                wm.removeView(overlay);
            } catch (Throwable ignored) {
            }
            overlay = null;
            overlayAdded = false;
            overlayLp = null;
        }
    }

    private void launch(Intent intent) {
        if (intent == null) {
            Toast.makeText(this, "未找到应用", Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开", Toast.LENGTH_SHORT).show();
        }
    }

    /** 由无障碍服务驱动：白名单前台时收起覆盖层，其余时盖回 */
    public static void setExemptForeground(boolean exempt) {
        if (exemptForeground == exempt) return; // 关键防抖！状态无变化直接跳过，杜绝重复刷新引发闪烁
        exemptForeground = exempt;
        ReadLockService s = instance;
        if (s != null && s.handler != null) {
            s.handler.post(() -> s.applyAllowState());
        }
    }

    public static void setOverlayAllowed(boolean allow) {
        setExemptForeground(allow);
    }

    private void applyAllowState() {
        if (overlay == null || overlayLp == null || !overlayAdded) return;
        try {
            if (exemptForeground) {
                // 放行：彻底隐形 (alpha=0) 并开启 NOT_TOUCHABLE 与 NOT_FOCUSABLE，释放底层一切操作
                overlayLp.alpha = 0f;
                overlayLp.flags |= (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                overlay.setVisibility(View.GONE);
            } else {
                // 遮挡：完全不透明 (alpha=1.0) 全屏遮盖并恢复触摸拦截
                overlayLp.alpha = 1.0f;
                overlayLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                overlayLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                overlay.setVisibility(View.VISIBLE);
            }
            wm.updateViewLayout(overlay, overlayLp);
        } catch (Throwable t) {
            android.util.Log.w("ReadLock", "applyAllowState failed", t);
        }
    }

    private void updateOverlayTexts() {
        if (overlay == null) return;
        overlayTitle.setText("夜间锁定中");
        overlayCountdown.setText("距解封还有 " + untilUnlockText());
        long m = minutesCache;
        if (!usagePermissionOk || m < 0) {
            overlayProgress.setText("尚未授予「使用情况访问」权限");
        } else {
            long remain = Math.max(0, TARGET_MINUTES - m);
            overlayProgress.setText("今日阅读 " + m + " / " + TARGET_MINUTES + " 分钟");
            overlayHint(remain);
        }
        applyAllowState();
    }

    private void overlayHint(long remain) {
        TextView hint = overlay.findViewById(R.id.overlay_hint);
        if (remain <= 0) {
            hint.setText("已达标，即将解锁");
        } else {
            hint.setText("打开微信读书再读 " + remain + " 分钟即可解锁");
        }
    }

    static String untilUnlockText() {
        Calendar now = Calendar.getInstance();
        Calendar unlock = (Calendar) now.clone();
        unlock.set(Calendar.HOUR_OF_DAY, LOCK_END_MIN / 60);
        unlock.set(Calendar.MINUTE, LOCK_END_MIN % 60);
        unlock.set(Calendar.SECOND, 0);
        if (!unlock.after(now)) unlock.add(Calendar.DAY_OF_YEAR, 1);
        long ms = unlock.getTimeInMillis() - now.getTimeInMillis();
        long h = ms / 3_600_000, min = (ms % 3_600_000) / 60_000;
        return h > 0 ? h + " 小时 " + min + " 分钟" : min + " 分钟";
    }

    public static boolean isOverlayHidden() {
        return locked && exemptForeground;
    }

    public static boolean isLockedNow() {
        return locked;
    }

    public static long getCachedMinutes() {
        return minutesCache;
    }

    public static boolean isUsagePermissionOk() {
        return usagePermissionOk;
    }

    // ---------- 精准定时与唤醒（防止息屏被 Doze 模式冻结） ----------

    private void scheduleExactAlarms() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;

        Calendar now = Calendar.getInstance();
        long nowMs = now.getTimeInMillis();

        // 寻找下一个关键节点：22:00（锁定开始）、00:00（跨天判定）、03:00（锁定结束）
        Calendar next22 = (Calendar) now.clone();
        next22.set(Calendar.HOUR_OF_DAY, 22);
        next22.set(Calendar.MINUTE, 0);
        next22.set(Calendar.SECOND, 0);
        next22.set(Calendar.MILLISECOND, 0);
        if (next22.getTimeInMillis() <= nowMs) {
            next22.add(Calendar.DAY_OF_YEAR, 1);
        }

        Calendar next0 = (Calendar) now.clone();
        next0.set(Calendar.HOUR_OF_DAY, 0);
        next0.set(Calendar.MINUTE, 0);
        next0.set(Calendar.SECOND, 0);
        next0.set(Calendar.MILLISECOND, 0);
        if (next0.getTimeInMillis() <= nowMs) {
            next0.add(Calendar.DAY_OF_YEAR, 1);
        }

        Calendar next3 = (Calendar) now.clone();
        next3.set(Calendar.HOUR_OF_DAY, 3);
        next3.set(Calendar.MINUTE, 0);
        next3.set(Calendar.SECOND, 0);
        next3.set(Calendar.MILLISECOND, 0);
        if (next3.getTimeInMillis() <= nowMs) {
            next3.add(Calendar.DAY_OF_YEAR, 1);
        }

        long nextTrigger = Math.min(next22.getTimeInMillis(), Math.min(next0.getTimeInMillis(), next3.getTimeInMillis()));

        Intent intent = new Intent(this, ReadLockService.class);
        intent.setAction("io.local.readlock.ACTION_CHECK");
        PendingIntent pi = PendingIntent.getService(this, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
            }
        } catch (Throwable t) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 通知 ----------

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = buildNotification(-1, false);
        try {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Throwable t) {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(long minutes, boolean lockedNow) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        String text;
        if (minutes < 0) {
            text = "请授予「使用情况访问」权限";
        } else if (lockedNow) {
            text = "已锁定 · 距解封 " + untilUnlockText() + " · 读微信读书可解锁";
        } else if (minutes >= TARGET_MINUTES) {
            text = "今日已读 " + minutes + " 分钟 ✓ 今晚自由";
        } else {
            text = "今日阅读 " + minutes + "/" + TARGET_MINUTES + " 分钟 · 22:00 未达标将锁定";
        }
        b.setContentTitle("读书锁").setContentText(text);
        return b.build();
    }

    private void updateNotification(long minutes, boolean lockedNow) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(minutes, lockedNow));
    }

    // ---------- 小组件 ----------

    static void pushWidget(Context ctx, long minutes, boolean lockedNow) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_read);
        String line1, line2;
        if (minutes < 0) {
            line1 = "📖 读书锁";
            line2 = "请先打开应用完成授权";
        } else if (lockedNow) {
            long remain = Math.max(0, TARGET_MINUTES - minutes);
            line1 = "🔒 已锁定 · 距解封 " + untilUnlockText();
            line2 = remain > 0 ? "读微信读书 " + remain + " 分钟即解锁" : "已达标，即将解锁";
        } else if (minutes >= TARGET_MINUTES) {
            line1 = "📖 今日已达标 ✓";
            line2 = "共 " + minutes + " 分钟，今晚自由";
        } else {
            long remain = TARGET_MINUTES - minutes;
            line1 = "📖 今日还需读 " + remain + " 分钟";
            line2 = "22:00 未达标将锁定";
        }
        rv.setTextViewText(R.id.widget_line1, line1);
        rv.setTextViewText(R.id.widget_line2, line2);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_line1, pi);
        rv.setOnClickPendingIntent(R.id.widget_line2, pi);
        mgr.updateAppWidget(new ComponentName(ctx, ReadWidgetProvider.class), rv);
    }

    private void updateWidget(long minutes, boolean lockedNow, boolean force) {
        if (!force && minutes == lastWidgetMinutes && lockedNow == lastWidgetLocked) return;
        lastWidgetMinutes = minutes;
        lastWidgetLocked = lockedNow;
        pushWidget(this, minutes, lockedNow);
    }
}
