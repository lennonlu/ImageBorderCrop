# ImageBorderCrop

Android 图片边框裁剪工具 — 自动检测并裁剪图片四周的纯色黑边或白边。

## 功能

- **自动/手动检测** — 支持自动识别黑边/白边，也可手动指定
- **逐行/列扫描** — 精确检测上下左右四边的纯色边框宽度
- **阈值调节** — 通过滑块控制颜色容差，适应不同场景
- **系统分享入口** — 从其他 App 分享图片进来直接处理
- **格式保持** — 支持 JPG/PNG/WEBP，保存时保持原格式

## 使用方式

1. 点击「选图」从相册选择图片，或从其他 App 分享图片到本应用
2. 选择边框类型（自动/黑边/白边），调节阈值
3. 点击「检测」预览裁剪结果
4. 点击「裁剪保存」导出到 `Pictures/ImageBorderCrop`

## 技术栈

- Kotlin + ViewBinding + Material Design 3
- minSdk 24 / targetSdk 34
- 核心算法 `BorderDetector.kt`：逐行/列像素扫描 + 亮度阈值判定

## 截图

> 暂无截图，请自行构建安装体验。

## 构建

```bash
# Windows
set JAVA_HOME=C:\Program Files\Java\jdk-19
set ANDROID_HOME=D:\Android_tools
.\gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## License

MIT
