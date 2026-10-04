package io.local.readlock;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 锁机期间的放行判定与系统防绕过卫士：
 * 1. 白名单放行（来电/拨号/短信/微信读书/微信通话界面）→ 彻底收起覆盖层，用户自由阅读/通话；
 * 2. 封死通知栏与控制中心（com.android.systemui / miui.systemui.plugin 等）→ 下拉秒级弹回收起；
 * 3. 严厉封死系统设置与权限管理（Settings / PermissionController / 小米安全中心）→ 秒级踢回桌面，禁止关闭悬浮窗权限；
 * 4. 纯事件驱动，无需主动轮询。
 */
public class LockAccessibilityService extends AccessibilityService {

    private String lastForegroundPkg = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkgCs = event.getPackageName();
        CharSequence clsCs = event.getClassName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        String cls = clsCs == null ? "" : clsCs.toString();
        if (pkg.length() == 0) return;

        // 如果未处于夜间锁定状态：
        if (!ReadLockService.isLockedNow()) {
            // 平时完全零轮询。仅当用户退出微信读书时，触发一次读数更新（刷新通知与小组件）
            if (ReadLockService.READ_PACKAGE.equals(lastForegroundPkg) && !ReadLockService.READ_PACKAGE.equals(pkg)) {
                ReadLockService.checkNow();
            }
            lastForegroundPkg = pkg;
            return;
        }

        // ---------- 以下为夜间锁定状态下的核心控制 ----------

        // 1. 忽略锁机应用自己（覆盖层自身 / 主界面）：
        if (ReadLockService.SELF_PACKAGE.equals(pkg)) {
            return;
        }

        // 2. 封杀 SystemUI（状态栏、控制中心、通知栏、手势插件等）：下拉秒弹回，但不干扰当前已放行的前台状态
        if (isSystemUi(pkg)) {
            try {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            } catch (Throwable ignored) {
            }
            return;
        }

        // 3. 严厉封死系统设置与权限管理（防关悬浮窗权限、防清除数据、防强行停止）：
        if (isBlockedSettings(pkg, cls)) {
            try {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            } catch (Throwable ignored) {
            }
            android.widget.Toast.makeText(getApplicationContext(),
                    "🔒 读书锁：夜间锁定期间禁止修改权限与设置", android.widget.Toast.LENGTH_SHORT).show();
            ReadLockService.setExemptForeground(false);
            return;
        }

        // 4. 判定是否属于放行白名单（微信读书、来电、拨号、短信、微信音视频通话）
        boolean exempt = isExempt(pkg, cls);
        ReadLockService.setExemptForeground(exempt);

        // 如果刚从微信读书切出，立即触发一次检查以判断是否已读满解锁
        if (ReadLockService.READ_PACKAGE.equals(lastForegroundPkg) && !ReadLockService.READ_PACKAGE.equals(pkg)) {
            ReadLockService.checkNow();
        }

        lastForegroundPkg = pkg;
    }

    private static boolean isSystemUi(String pkg) {
        return "com.android.systemui".equals(pkg)
                || "com.miui.systemui".equals(pkg)
                || "miui.systemui.plugin".equals(pkg)
                || "com.miui.notification".equals(pkg);
    }

    private static boolean isBlockedSettings(String pkg, String cls) {
        if (pkg.length() == 0) return false;
        switch (pkg) {
            case "com.android.settings":
            case "com.android.permissioncontroller":
            case "com.google.android.permissioncontroller":
            case "com.miui.securitycenter":
            case "com.miui.securityadd":
            case "com.miui.securitycore":
            case "com.miui.securitymanager":
            case "com.lbe.security.miui":
            case "com.xiaomi.misettings":
            case "com.android.packageinstaller":
            case "com.google.android.packageinstaller":
                return true;
            default:
                break;
        }
        String lowCls = cls.toLowerCase();
        return lowCls.contains("permission")
                || lowCls.contains("appinfo")
                || lowCls.contains("installedappdetails")
                || lowCls.contains("specialaccess");
    }

    private static boolean isExempt(String pkg, String cls) {
        if (pkg.length() == 0) return false;
        switch (pkg) {
            case "com.android.incallui":     // 系统来电界面
            case "com.android.server.telecom":
            case "com.android.phone":        // 电话核心服务
            case "com.android.contacts":     // 小米拨号盘（联系人一体）
            case "com.android.dialer":
            case "com.google.android.dialer":
            case "com.android.mms":          // 短信
            case "com.google.android.apps.messaging": // 谷歌信息
            case "com.tencent.weread":       // 微信读书（锁机期间始终可用）
                return true;
            default:
                break;
        }
        // 微信：放行音视频通话（单人 voip/videocall 及群聊通话 multitalk），聊天、朋友圈等仍然锁死
        if ("com.tencent.mm".equals(pkg)) {
            String low = cls.toLowerCase();
            return low.contains("voip") || low.contains("videocall") || low.contains("multitalk");
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        android.widget.Toast.makeText(getApplicationContext(),
                "读书锁守护已启动", android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onInterrupt() {
    }
}
