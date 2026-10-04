package io.local.readlock;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

public class MainActivity extends Activity {

    private static volatile MainActivity activeInstance;

    private LinearLayout permList;
    private TextView statusCard;
    private TextView footer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;
        setContentView(R.layout.activity_main);

        statusCard = (TextView) findViewById(R.id.status_card);
        permList = (LinearLayout) findViewById(R.id.perm_list);
        footer = (TextView) findViewById(R.id.footer);

        findViewById(R.id.btn_add_widget_hint).setOnClickListener(v ->
                Toast.makeText(this, "长按桌面空白处 → 添加小组件 → 读书锁", Toast.LENGTH_LONG).show());

        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        footer.setText((version.isEmpty() ? "" : "版本 " + version + "\n\n") + getString(R.string.footer));
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
        ensureServiceRunning();
        refreshStatus();
        buildPermRows();
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    private void ensureServiceRunning() {
        startForegroundService(new Intent(this, ReadLockService.class));
    }

    public static void updateFromService(long minutes, boolean locked) {
        MainActivity a = activeInstance;
        if (a != null) {
            a.runOnUiThread(a::refreshStatus);
        }
    }

    private void refreshStatus() {
        if (statusCard == null) return;
        long m = ReadLockService.getCachedMinutes();
        boolean usageOk = ReadLockService.isUsagePermissionOk();
        String line1;
        if (!usageOk || m < 0) {
            line1 = "请先授予「使用情况访问」权限";
        } else if (ReadLockService.isLockedNow()) {
            line1 = "🔒 已锁定 · 距解封 " + ReadLockService.untilUnlockText();
        } else {
            Calendar now = Calendar.getInstance();
            int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            if (ReadLockService.inLockWindow(now)) {
                // 锁定窗口内未锁 = 已达标
                line1 = "✓ 已达标，今晚不锁定";
            } else if (nowMin < ReadLockService.LOCK_START_MIN) {
                if (m >= ReadLockService.TARGET_MINUTES) {
                    line1 = "✓ 今日已达标，今晚自由";
                } else {
                    line1 = "📖 今日还需读 " + (ReadLockService.TARGET_MINUTES - m) + " 分钟";
                }
            } else {
                line1 = "📖 今日还需读 " + Math.max(0, ReadLockService.TARGET_MINUTES - m) + " 分钟";
            }
        }
        String line2 = "今日阅读 " + (m < 0 ? "-" : m) + " / " + ReadLockService.TARGET_MINUTES + " 分钟";
        statusCard.setText(line1 + "\n" + line2);
    }

    // ---------- 权限引导 ----------

    private void buildPermRows() {
        if (permList == null) return;
        permList.removeAllViews();
        addPermRow("使用情况访问（统计读书时长）", usageStatsGranted(),
                v -> {
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (Throwable t) {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    }
                });
        addPermRow("解除受限设置（如遇开关置灰不可用）", isRestrictedSettingsAllowed(),
                v -> {
                    Toast.makeText(this, "进入后点击右上角「⋮」选择「允许受限设置」", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                });
        addPermRow("悬浮窗（锁机全屏遮挡）", Settings.canDrawOverlays(this),
                v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        addPermRow("无障碍服务（通话/读书放行）", isAccessibilityEnabled(),
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addPermRow("通知权限（常驻状态通知）",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED,
                v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1));
        addPermRow("电池优化白名单（防止被杀后台）",
                ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()),
                v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                });
        addPermRow("隐藏桌面图标（小组件可随时打开本应用）", isIconHidden(),
                null, // 该行用 Switch 行为，点击行切换
                true);
    }

    private void addPermRow(String title, boolean granted, View.OnClickListener fixAction) {
        addPermRow(title, granted, fixAction, false);
    }

    private void addPermRow(String title, boolean granted, View.OnClickListener fixAction, boolean invert) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundResource(R.drawable.bg_perm_row);

        TextView tv = new TextView(this);
        tv.setText((granted != invert ? "✓  " : "✗  ") + title);
        tv.setTextSize(14);
        tv.setTextColor(getColor(granted != invert ? R.color.good : R.color.text_secondary));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        if (fixAction != null && !(granted != invert)) {
            Button b = new Button(this);
            b.setText("去开启");
            b.setTextSize(12);
            b.setOnClickListener(fixAction);
            row.addView(b);
        } else if (fixAction == null) {
            row.setOnClickListener(v -> setIconHidden(!isIconHidden()));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        permList.addView(row, lp);
    }

    private boolean usageStatsGranted() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops == null) return false;
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isAccessibilityEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private boolean isRestrictedSettingsAllowed() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (ops != null) {
                int mode = ops.checkOpNoThrow("android:access_restricted_settings",
                        android.os.Process.myUid(), getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private boolean isIconHidden() {
        return getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, "io.local.readlock.LauncherAlias"))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setIconHidden(boolean hidden) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, "io.local.readlock.LauncherAlias"),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
        Toast.makeText(this, hidden ? "图标已隐藏，可从桌面小组件打开本应用" : "图标已显示", Toast.LENGTH_LONG).show();
        buildPermRows();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
