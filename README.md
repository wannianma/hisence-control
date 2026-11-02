# 家庭电视锁 TvGuard M2.17

`com.family.tvguard` — Hisense VIDAA / Android TV 家长锁屏 + 局域网控制 + 英语答题。

当前：`versionCode 19` / `1.0.0-m2.17`

## 构建

```bash
export JAVA_HOME=/home/box/jdk   # 或本机 JDK 17+
export ANDROID_HOME=/home/box/Android/Sdk
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Windows（Android Studio JBR 21 + SDK）:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
adb connect 192.168.188.136:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.family.tvguard/.ui.MainActivity
```

Gradle 已恢复 `systemProp.java.net.useSystemProxies=true`；本机无代理时走 `settings.gradle.kts` 里的阿里云镜像。若代理干扰构建，可临时加 `-DsystemProp.java.net.useSystemProxies=false`。

## Token / PIN

- 首次启动生成 `parentToken`，写入 SharedPreferences（升级 `-r` 保留）
- logcat: `adb logcat -s TvGuard`
- 文件: `/sdcard/Android/data/com.family.tvguard/files/parent_token.txt`
- 默认家长 PIN: `000000`（SHA-256 存储）

## HTTP (端口 8787)

- `GET /v1/status` — 含 `usageAccessGranted`、`topDetectionMode`、`guardServiceRunning`、`deviceOwner=false`
- `POST /v1/unlock` JSON `{"hours":2}` + `Authorization: Bearer <token>`
- `POST /v1/lock` / `POST /v1/cancel-unlock` / `POST /v1/force-quiz`
- `GET /admin/` 手机管理页

## 使用情况访问（Usage Access）

稳定前台检测建议开启 `PACKAGE_USAGE_STATS`：

### 电视上操作

1. 打开「家庭电视锁」→ 点 **开启使用情况访问权限**
2. 或遥控器：`设置` → `应用` / `应用管理` → `特殊应用访问` / `特殊应用权限` → **使用情况访问权限** / **有权查看使用情况的应用** → 找到 **家庭电视锁** → 打开
3. 部分 VIDAA 路径：`设置` → `安全和限制` → `使用情况访问`

### ADB（调试机）

```powershell
adb shell appops set com.family.tvguard GET_USAGE_STATS allow
adb shell appops get com.family.tvguard GET_USAGE_STATS
```

未授权时自动回退 `getRunningTasks` / 前台进程启发式，**不会**因此无法锁屏。

## 开机 / 每次待机唤醒（m2.17）

海信 M7332 的电源键是待机（MediaTek IPO），不是冷启动。m2.16 第一次开关能拦截，第二次会失败：elapsed 时钟在 IPO 里可能停住，心跳闹钟不会到期；进程若被冻住，`LockActivity` 仍显示“在前台”，服务就不再拉锁屏。

本版每次灭屏 / IPO 关机都会重新做这几件事（第 1 次、第 2 次、第 N 次相同）：

- 清掉“锁屏已在前台”的内存标记
- 挂一个**墙钟 RTC、不唤醒屏幕**的闹钟。待机时墙钟继续走，下次用户打开电视时闹钟已经过期，系统会马上拉起守护。不把关着的电视自己打开
- 另留一个不精确 RTC 闹钟，避免精确闹钟在第二次唤醒时被系统空闲配额推迟
- 动态监听 `SCREEN_OFF` / `SCREEN_ON` / 屏保结束；清单里额外收 `ACTION_SHUTDOWN_IPO`
- 亮屏沿（灭 → 亮）强制拉 `LockActivity`，不受开机 8 秒宽限影响
- 持久化 JobScheduler 仍在，作为重启后的后备
- 解锁窗口、彻底解锁、答题、每日配额不变。使用情况访问没开也会启动守护

海信/VIDAA 仍可能杀掉未白名单进程。若开机后还是能直接用别的应用：

**设置 → 应用 → 启动管理 → 允许「家庭电视锁」自启动和后台运行。**

不要在系统里「强行停止」本应用，否则 Android 会一直忽略开机广播，直到手动再打开一次。

## Device Owner（未启用）

**跳过。** 在家庭 Hisense 上设置 Device Owner 通常需要清机 / `dpm set-device-owner`，会抹掉账号、CleanYourTV 桌面与现有配置，不可逆风险高。本版本用 Usage Access + Boot + KeepAlive + LockActivity 强化，足以满足家长锁需求。

## 海信注意

- 不替换 CleanYourTV 桌面；不使用 Accessibility / Device Owner
- 路由器无线隔离时，手机可能打不开管理页，请用有线同网段的电脑浏览器
