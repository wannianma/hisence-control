# 家庭电视锁 TvGuard M1

`com.family.tvguard` — Hisense VIDAA / Android TV 家长锁屏 + 局域网控制。

## 构建

```bash
export JAVA_HOME=/home/box/jdk   # 或本机 JDK 17+
export ANDROID_HOME=/home/box/Android/Sdk
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Windows（SDK 配好后）:

```powershell
.\gradlew.bat :app:assembleDebug
adb connect 192.168.188.136:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.family.tvguard/.ui.MainActivity
```

## Token

- 首次启动生成 `parentToken`，写入 SharedPreferences
- logcat: `adb logcat -s TvGuard`
- 文件: `/sdcard/Android/data/com.family.tvguard/files/parent_token.txt`
- 锁屏页底部显示 token 前 8 位；完整值见上述位置
- 默认家长 PIN: `000000`（SHA-256 存储）

## HTTP (端口 8787)

- `GET /v1/status`
- `POST /v1/unlock` JSON `{"hours":2}` + `Authorization: Bearer <token>`
- `POST /v1/lock` / `POST /v1/cancel-unlock`
- `GET /admin/` 手机三按钮页

## 海信注意

开机自启为 best-effort，请在「启动管理」白名单本应用。进程被杀后需 sidekick/保活（M1 仅文档说明）。

不替换 CleanYourTV 桌面；不使用 Accessibility。
