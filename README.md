# 家庭电视锁 TvGuard M2.18

`com.family.tvguard` — Hisense VIDAA / Android TV 家长锁屏 + 局域网控制 + 英语答题。

当前：`versionCode 20` / `1.0.0-m2.18`

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

## 开机 / 每次待机唤醒（m2.18）

海信 M7332 的电源键是待机（MediaTek IPO），不是冷启动，所以只听 `BOOT_COMPLETED` 不够。m2.17 灭屏后会挂墙钟闹钟，但有两处会把下一次拉起弄丢：跟进闹钟用会停走的 `elapsedRealtime` 做去重，闹钟在亮屏前先响时不再预约下一次；持久化 Job 在自己还没结束时去检查 pending，于是跑完就没有下一发。进程被待机杀掉后，动态注册的 `SCREEN_ON` 也不在了。

m2.18：

- 待机闹钟的去重改用墙钟。正在响的那一次**一定**再挂下一发，不用会停走的 elapsed
- Job 返回之后再预约下一次；亮屏时由闹钟/开机广播直接拉锁屏，不把 `startActivity` 只留给前台服务（海信会丢掉服务里的后台启动）
- 精确闹钟不再走 `setExactAndAllowWhileIdle`，避免心跳把系统的 idle 配额占满、把待机闹钟推迟
- `BOOT_COMPLETED` 单独放一个 intent-filter。运行中若发现开机广播组件被关掉，会重新打开
- 仍然不用会把电视自己点亮的 wakeup 闹钟。解锁窗口、彻底解锁、答题、每日配额不变

海信/VIDAA 仍可能杀掉未白名单进程。若开机后还是能直接用别的应用：

**设置 → 应用 → 启动管理 → 允许「家庭电视锁」自启动和后台运行。**

不要在系统里「强行停止」本应用，否则 Android 会一直忽略开机广播，直到手动再打开一次。

## Device Owner（未启用）

**跳过。** 在家庭 Hisense 上设置 Device Owner 通常需要清机 / `dpm set-device-owner`，会抹掉账号、CleanYourTV 桌面与现有配置，不可逆风险高。本版本用 Usage Access + Boot + KeepAlive + LockActivity 强化，足以满足家长锁需求。

## 海信注意

- 不替换 CleanYourTV 桌面；不使用 Accessibility / Device Owner
- 路由器无线隔离时，手机可能打不开管理页，请用有线同网段的电脑浏览器
