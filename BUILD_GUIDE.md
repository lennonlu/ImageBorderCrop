# ImageBorderCrop APK 构建指南

> 给其他 Agent / 开发者的完整构建手册

## 一、项目信息

- **项目路径**: `F:\lennon\02-PersonalProject\ImageBorderCrop`
- **包名**: `com.lennon.imagebordercrop`
- **仓库**: https://github.com/lennonlu/ImageBorderCrop
- **GitHub Release**: https://github.com/lennonlu/ImageBorderCrop/releases/tag/v1.0

## 二、构建环境（关键！缺一不可）

### 2.1 JDK

```
路径: C:\Program Files\Java\jdk-19
版本: Java 19.0.2
```

> **⚠️ 坑点**: 系统里还有个 `C:\Program Files\Common Files\Oracle\Java\javapath`，那只是个 launcher 不含完整 JDK。JAVA_HOME 必须指向 `C:\Program Files\Java\jdk-19`，否则 Gradle 构建会失败。

### 2.2 Android SDK

```
路径: D:\Android_tools
  ├── build-tools/34.0.0/        ← build.gradle.kts 中 buildToolsVersion 指向这个（必须用 34.0.0，不要用 36.0.0-rc5）
  ├── platforms/android-34/
  └── platform-tools/            ← adb 在这里
```

> **⚠️ 坑点**: 不要用 `build-tools/36.0.0-rc5`（RC 版本有 bug，会导致 `processReleaseResources` 任务中 `stableIds.txt` 报 "数据无效" 错误）。`buildToolsVersion` 必须和 `compileSdk` 对齐：`compileSdk = 34` → `buildToolsVersion = "34.0.0"`。

> **⚠️ 坑点**: SDK 在非标准位置 `D:\Android_tools`，系统没有 `ANDROID_HOME` 环境变量。构建时必须在命令行手动设置。

### 2.3 Gradle / AGP / Kotlin 版本

```
Gradle: 8.5 (wrapper, 不用全局安装)
AGP: 8.2.2
Kotlin: 1.9.22
compileSdk: 34
targetSdk: 34
minSdk: 24
jvmTarget: 1.8
```

### 2.4 关键配置文件

#### `gradle.properties`（已配置好，不用改）
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
android.overridePathCheck=true          # ← 绕过中文路径检查，必须保留
```

#### `local.properties`（含 SDK 路径 + 签名配置）
```properties
sdk.dir=D:\\Android_tools

# Release signing config
keystore.path=app/keystore/release.jks
keystore.alias=imagebordercrop
keystore.password=lennon2024
keystore.key.password=lennon2024
```

> **注意**: `local.properties` 被 `.gitignore` 排除，不在仓库里。新环境需要手动创建。

#### `.gitignore` 中的关键排除项
```
local.properties
*.jks
*.keystore
app/keystore/
```

## 三、构建命令

### 3.1 Debug 构建

```powershell
# 必须用 PowerShell，Bash 在这台机器上环境变量传递有问题
$env:JAVA_HOME = "C:\Program Files\Java\jdk-19"
$env:ANDROID_HOME = "D:\Android_tools"
Set-Location "F:\lennon\02-PersonalProject\ImageBorderCrop"
.\gradlew.bat assembleDebug
```

**输出**: `app\build\outputs\apk\debug\app-debug.apk`

### 3.2 Release 构建（需 keystore）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-19"
$env:ANDROID_HOME = "D:\Android_tools"
Set-Location "F:\lennon\02-PersonalProject\ImageBorderCrop"
.\gradlew.bat assembleRelease
```

**输出**: `app\build\outputs\apk\release\app-release.apk`

> 签名配置已在 `app/build.gradle.kts` 中通过 `signingConfigs.release` 配置，凭据从 `local.properties` 读取。

### 3.3 如果构建输出太长被截断

```powershell
# 把输出重定向到文件再读
$env:JAVA_HOME = "C:\Program Files\Java\jdk-19"
$env:ANDROID_HOME = "D:\Android_tools"
Set-Location "F:\lennon\02-PersonalProject\ImageBorderCrop"
.\gradlew.bat assembleRelease *>&1 | Out-File -FilePath "build_output.txt" -Encoding utf8
Write-Output "EXIT_CODE: $LASTEXITCODE"
```

然后读 `build_output.txt` 查看结果。

## 四、安装到设备

```bash
# 设备: 小米13, serial: b3f29521
"D:/Android_tools/platform-tools/adb.exe" devices

# 安装
"D:/Android_tools/platform-tools/adb.exe" -s b3f29521 install -r "F:/lennon/02-PersonalProject/ImageBorderCrop/app/build/outputs/apk/release/app-release.apk"
```

## 五、创建 Keystore（仅首次需要）

如果 `app/keystore/release.jks` 不存在：

```bash
"C:/Program Files/Java/jdk-19/bin/keytool" -genkeypair -v \
  -keystore "F:/lennon/02-PersonalProject/ImageBorderCrop/app/keystore/release.jks" \
  -alias imagebordercrop \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass lennon2024 -keypass lennon2024 \
  -dname "CN=ImageBorderCrop, OU=Personal, O=lennon, L=Beijing, ST=Beijing, C=CN"
```

## 六、推送到 GitHub + 创建 Release

```bash
# 1. 配置 git 凭据（通过 gh CLI）
cd "F:/lennon/02-PersonalProject/ImageBorderCrop"
gh auth setup-git

# 2. 提交并推送
git add -A
git commit -m "your commit message"
git push -u origin main

# 3. 创建 GitHub Release 并上传 APK
gh release create v1.0 "app/build/outputs/apk/release/app-release.apk" \
  --title "v1.0 - 图片边框裁剪" \
  --notes "release notes here"
```

> GitHub 认证用 `gh` CLI（`C:\Program Files\GitHub CLI\gh.exe`），已登录账号 `lennonlu`。

## 七、踩过的坑（前车之鉴）

| # | 问题 | 原因 | 解决 |
|---|------|------|------|
| 1 | 中文路径构建失败 | 项目曾在 `02-个人开发` 目录下 | 迁移到 `02-PersonalProject` + `android.overridePathCheck=true` |
| 2 | JAVA_HOME 指向 javapath 导致构建失败 | javapath 只是 launcher | JAVA_HOME 必须设为 `C:\Program Files\Java\jdk-19` |
| 3 | SDK License 未接受 | 非标准 SDK 位置 | 手动写入 `D:\Android_tools\licenses\android-sdk-license` |
| 4 | build-tools 34 缺失 | SDK 不完整 | 手动下载解压到 `D:\Android_tools\build-tools\34.0.0\` |
| 5 | Bash 环境变量传递失败 | Git Bash 与 Windows 环境变量机制不同 | **必须用 PowerShell** 构建 |
| 6 | git push 需要认证但无 TTY | 沙箱环境限制 | `gh auth setup-git` 配置凭据 helper |
| 7 | 构建输出被截断 | PowerShell 管道缓冲 | 重定向到文件再读 |
| 8 | `processReleaseResources` 报 `stableIds.txt: error: failed to open: 数据无效 (13)` | `buildToolsVersion = "36.0.0-rc5"`（RC 版本有 bug） | 改为 `buildToolsVersion = "34.0.0"`（和 compileSdk 对齐），然后 `gradlew clean` 再构建 |
