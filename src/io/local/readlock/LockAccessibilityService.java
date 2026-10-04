package io.local.readlock;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 锁机期间的放行判定：只监听「窗口切换」这一种低频事件。
 * 前台命中白名单（来电/拨号/短信/微信读书/微信通话界面/自身）→ 收起覆盖层；
 * 其余任何界面 → 立即盖回。
 * 纯事件驱动，无需主动轮询。
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

        // 1. 如果处于锁定状态，做即时放行/遮挡判定
        if (ReadLockService.isLockedNow()) {
            boolean exempt = isExempt(pkg, cls);
            ReadLockService.setOverlayAllowed(exempt);

            // 如果刚从微信读书切出，立即触发一次检查以判断是否已读满解锁
            if (ReadLockService.READ_PACKAGE.equals(lastForegroundPkg) && !ReadLockService.READ_PACKAGE.equals(pkg)) {
                ReadLockService.checkNow();
            }
        } else {
            // 2. 白天或非锁定状态（超省电模式）：
            // 平时完全零轮询。仅当用户退出微信读书时，触发一次读数更新（刷新通知与小组件）
            if (ReadLockService.READ_PACKAGE.equals(lastForegroundPkg) && !ReadLockService.READ_PACKAGE.equals(pkg)) {
                ReadLockService.checkNow();
            }
        }

        lastForegroundPkg = pkg;
    }

    private static boolean isExempt(String pkg, String cls) {
        if (pkg.length() == 0) return false;
        switch (pkg) {
            case "io.local.readlock":        // 自己（锁机界面/主界面）
            case "com.android.systemui":     // 锁屏/通知栏/音量等系统界面
            case "com.android.incallui":     // 来电界面
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
