# Repository Guidelines

## Project Structure & Module Organization
- `app/` 主 Android 宿主模块；入口 Activity、WebView 配置与资源拦截。
- `breezeh5/` 插件模块，负责本地 H5 包管理、版本更新、资源重写。
- `app/src/main/assets/` 可预置离线种子包（`<projectName>/vX/dict.zip`）。
- 输出 APK：`app/build/outputs/apk/`。常用日志 TAG：`BreezeH5`、`ImageCache`、`H5Entry`。

## Build, Test, and Development Commands
- `./gradlew :app:assembleDebug` 生成调试 APK。
- `./gradlew :app:assembleRelease` 生成发布 APK（需签名配置）。
- `./gradlew :breezeh5:assembleDebug` 单独构建插件 AAR（如需）。
- 运行前确保本地 JDK/Android SDK 配置正确；首次可执行 `./gradlew tasks` 校验环境。

## Coding Style & Naming Conventions
- Java/Kotlin 使用项目默认的 Android Studio 格式化（4 空格缩进），保持 import 有序、去除未用代码。
- 命名：环境相关统一使用 `projectName-env`（如 `your-h5-dev`）；资源目录 `vX/index.html`。
- 日志：核心流程使用统一 TAG（如 `BreezeH5`、`ImageCache`），便于过滤定位。
- 配置常量集中在 `H5Config`/`MainActivity.buildEnvConfig`，避免散落硬编码。

## Testing Guidelines
- 运行调试安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- 断网/弱网测试：确认离线能加载 `appassets.androidplatform.net/<projectName>/vX/index.html`，子资源被重写到本地。
- 升级测试：上传更高版本的 `lastversion` 和 `vX/manifest.json + dict.zip`，观察定时拉取、解压、激活日志。
- 如有 UI 变更，使用设备日志过滤 TAG 验证资源加载与错误处理路径。

## Commit & Pull Request Guidelines
- Commit 信息简洁描述变更（示例：`fix: align asset loader root`，`feat: add env switch api`）。
- PR 描述应包含：变更目的、主要改动点、测试结果（命令与设备/环境）、影响范围（宿主/插件）。
- 如涉及配置或路径调整，注明是否需要手动更新资产种子或 OSS 目录。

## Architecture & Agent Tips
- WebView 通过 WebViewAssetLoader 映射 `appassets.androidplatform.net` 到本地 `files/<projectName>/vX/`；拦截逻辑在 `MainActivity`，本地包管理在 `BreezeH5Manager`。
- 多环境支持：`buildEnvConfig` 构造 H5Config，包含 `baseUrl/fallback/remoteDomains/assetBasePath`；切换环境后需重建 asset loader。
- 日志驱动调试：优先查看 `BreezeH5`（版本/下载）、`ImageCache`（拦截/重写）以定位资源路径问题。***
