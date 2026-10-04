# 读书锁（ReadLock）

自律工具：每日微信读书满 30 分钟换当晚手机自由。专为 Android 16 定制。

## 规则（内置定死）

- **目标**：每日微信读书（前台使用）≥ 30 分钟
- **检查**：每天 **22:00**，达标 → 当晚自由；未达标 → 锁定全屏
- **解封**：次日 **凌晨 3:00** 自动解锁
- **补救**：锁机期间「去微信读书」按钮始终可用，**读满 30 分钟立即自动解锁**（补救窗口为 22:00–24:00；凌晨读书计入新的一天）
- **应急**：电话、短信天然可用；锁机界面提供「应急通话」（拨号盘）与「短信」入口；**微信来电时自动放行通话界面**（聊天、朋友圈仍锁死）；来电（普通电话）界面也自动放行

## 权限清单（装好后按 app 内引导开启）

| 权限 | 作用 | 开法 |
|---|---|---|
| 使用情况访问 | 统计微信读书时长 | app 内按钮直达单应用开启页 |
| 解除受限设置 | 解锁系统置灰的敏感权限 | app 内按钮直达详情页右上角「⋮」 |
| 悬浮窗 | 锁机全屏遮挡 | app 内按钮 |
| 无障碍服务 | 通话/读书界面智能放行 | 系统设置里开启 |
| 通知权限 | 常驻状态通知 | app 内按钮 |
| 电池优化白名单 | 防止后台被杀 | app 内按钮 |

另请手动：**设置 → 应用设置 → 应用管理 → 读书锁 → 自启动**，最近任务里给本应用**加锁**。

## 常见排障与核心根因（v0.1.1 修复复盘）

### 1. 为什么系统无障碍列表找不到「读书锁」？
- **根本原因**：`AndroidManifest.xml` 中无障碍服务 `<service>` 缺少了核心的 Intent Filter：
  ```xml
  <intent-filter>
      <action android:name="android.accessibilityservice.AccessibilityService" />
  </intent-filter>
  ```
- **影响**：Android 系统的包管理器（PMS）依据此 Action 识别无障碍组件。缺少它时，系统直接认为该服务不是无障碍服务，因此列表中永远无法检索出本应用。
- **已修复**：v0.1.1 已补全此 Filter，系统已成功识别并收录。

### 2. 为什么「使用情况访问」开关是灰色的，提示被系统给禁了？
- **根本原因**：**Android 13+ 受限设置（Restricted Settings）机制**。
  系统为了安全，对所有通过非官方应用商店安装的侧载 APK，默认开启了受限保护（`ACCESS_RESTRICTED_SETTINGS` 为 `ignore`），直接锁定并置灰了**无障碍服务**与**使用情况访问（Usage Access）**开关，点击时会提示“出于安全考虑，此设置目前不可用”。
- **解法（手机端手动）**：
  在手机上打开：**系统设置 → 应用设置 → 应用管理 → 读书锁 → 点击右上角「⋮」（三个点） → 允许受限设置**，验证锁屏密码后即可正常开启开关。
- **解法（电脑 adb 快速命令）**：
  ```bash
  adb shell appops set io.local.readlock ACCESS_RESTRICTED_SETTINGS allow
  adb shell appops set io.local.readlock GET_USAGE_STATS allow
  ```
- **已优化**：v0.1.1 在主界面权限列表中直接增加了「解除受限设置」一键直达入口，并优化了使用情况访问的一键直跳。

## adb 一键授权（插电脑执行，一次即可）

```bash
ADB=/c/Users/25193/android-build-tools/platform-tools/adb.exe
$ADB install -r ReadLock.apk
$ADB shell appops set io.local.readlock ACCESS_RESTRICTED_SETTINGS allow   # 解除侧载限制（关键）
$ADB shell appops set io.local.readlock GET_USAGE_STATS allow              # 使用情况访问
$ADB shell appops set io.local.readlock SYSTEM_ALERT_WINDOW allow          # 悬浮窗
$ADB shell pm grant io.local.readlock android.permission.POST_NOTIFICATIONS
$ADB shell dumpsys deviceidle whitelist +io.local.readlock                 # 电池白名单
# 解除受限后重启一次手机，无障碍列表才会重新扫描出本服务
$ADB reboot
```

重启后：手机设置 → 更多设置 → 无障碍 → 已下载的应用 → 开启「读书锁（锁机放行）」。

## 小组件

桌面长按空白处 → 添加小组件 → 读书锁。显示"今日还需读 X 分钟 / 已达标 / 已锁定·倒计时"，点击打开本应用。**隐藏图标后小组件是唯一的入口**，请先加小组件再隐藏图标。

## 隐藏图标

主界面权限列表最底一行，点按即可隐藏/显示。隐藏后：桌面无图标，但常驻通知、小组件、无障碍服务都不受影响。

## 技术要点（对应源码）

| 文件 | 职责 |
|---|---|
| `ReadLockService.java` | 专为 Android 16 优化：白天 0 轮询完全休眠，AlarmManager 精准整点唤醒，仅夜间锁机补救时按需轮询；UsageStats 纯函数判定，覆盖层智能显隐 + 全面屏沉浸遮挡 + 小组件按需推送 |
| `LockAccessibilityService.java` | 仅监听低频的窗口切换事件，且彻底关闭 `canRetrieveWindowContent`（零节点解析开销）；前台命中白名单（来电/拨号/短信/微信读书/微信音视频通话及群聊/自身）即时放行，退回其它应用立即盖回；退出微信读书时事件驱动触发一次读数更新 |
| `MainActivity.java` | 状态卡 + 权限引导 + 隐藏图标开关（activity-alias 方案） |
| `ReadWidgetProvider.java` | 桌面小组件，由服务按需更新（已配置 `exported="true"` 支持 Android 16 桌面广播） |
| `BootReceiver.java` | 开机/重装后自动恢复守护（支持 `LOCKED_BOOT_COMPLETED` 直接启动） |

**Android 16 极致省电设计**：
- **白天 0 轮询**：03:00 到 22:00 期间 Handler 循环完全停止，CPU 深度睡眠，整天不唤醒；
- **事件驱动更新**：仅当用户退出微信读书时，由无障碍服务驱动单次刷新读数；平时零开销；
- **精准闹钟唤醒**：22:00、00:00、03:00 使用 `AlarmManager.setExactAndAllowWhileIdle` 准点唤醒，免疫系统 Doze 息屏休眠冻结；
- **达标即休眠**：晚上只要读满 30 分钟，立即解封并停掉所有轮询，当晚整晚自由且零耗电；
- **无障碍极简配置**：关闭节点检索，不联网、不占用后台常驻内存。

## 已知边界

- 锁机遮挡为覆盖层方案，极端手段（安全模式启动、卸载应用）仍可绕过——自律工具，防手痒不防坚决
- 微信"语音/视频通话"通过界面特征放行；若微信大版本改动通话界面类名，需要同步调整 `LockAccessibilityService` 的匹配规则
- 测试：临时把 `ReadLockService.TARGET_MINUTES` 改小、`LOCK_START_MIN` 改近即可验证全流程，测完改回重编译
