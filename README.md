# BreezeH5 插件说明

本插件用于 WebView 加载 H5 的本地缓存加速与增量更新，支持离线首屏、定时拉取最新包、版本兜底，宿主只需极少改动即可接入。适配 `appassets.androidplatform.net` 虚拟域名，按环境区分项目名（如 `your-h5-dev`）。

## 设计原理（简述）
- **离线优先**：入口 URL 固定为 `https://appassets.androidplatform.net/<project>/vX/index.html`，对应本地 `/data/user/0/<pkg>/files/<project>/vX/`。
- **预置+更新**：启动先解压 assets 中最高版本的 `dist.zip` 为本地种子；后台定时拉取 OSS 的 `lastversion`/`manifest.json`/`dist.zip`，自动下载解压、激活最新版本并清理旧版。
- **请求重写**：插件内置 `WebViewClient`，对 `appassets` 域名补齐 `<project>/vX/` 前缀；对配置的远端域名/路由（如支付回调）在使用本地包时重写到本地入口，子资源（js/css/static）也自动映射。
- **兜底与容错**：本地缺失或网络错误时切换到 `fallbackUrl`；缺失的非关键资源（如 `favicon.ico`）返回空响应，避免 ENOENT。

## 引入依赖（JitPack）
```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.jom-io:breeze-android:v1.0.0' }
```
如需升级，替换为最新 tag。

## 核心配置 (H5Config)
- `projectName`: 例如 `your-h5-dev` / `your-h5-pre` / `your-h5-prod`
- `baseUrl`: OSS 基址，如 `https://<your-oss-base>/h5/patch/dev`
- `fallbackUrl`: 环境对应在线 H5
- `remoteDomains`: 需重写到本地的远端域名
- `routePrefixes`: 需重写的路由前缀（如支付回调 hash）
- `assetBasePath`: 资产种子前缀（通常与 projectName 相同）
- 其他：`assetZipName=dist.zip`，`lastVersionPath=lastversion`，`manifestPattern=v%d/manifest.json`
- 定时：`initialCheckDelayMillis=30s`，`min=5min`，`max=60min`，`backoffMultiplier=2`，`enablePeriodicCheck=true`，`useWifiOnly=true`，`keepVersions=5`

## 使用步骤
1) **初始化**
   ```java
   H5Config config = buildEnvConfig(currentEnv);
   BreezeH5Manager.initialize(getApplicationContext(), config,
       (version, url) -> { webView.loadUrl(url); return true; },
       (u, r) -> showNoNetworkPage());
   ```
   - 预置种子：自动扫描 assets `<assetBasePath>/vX/dist.zip` 取最高版本解压并激活。
   - 加载入口：`BreezeH5Manager.loadEntry(webView)`（本地优先，缺失则 fallback）。

2) **WebView 拦截**
   - 默认使用插件内置 `webViewClient`（`attachWebView`），或在宿主 `shouldInterceptRequest/shouldOverrideUrlLoading` 中委托 `BreezeH5Manager` 处理。
   - appassets 请求自动补全 `<project>/vX/`；命中远端域名/路由时（且入口为本地包）自动重写到本地入口，子资源也映射。

3) **生命周期**
   - `onResume` 调用 `BreezeH5Manager.onHostResume()` 重置定时检查。
   - 需要停止时可调用 `BreezeH5Manager.stop()`。

## 工具脚本
- `package-h5.sh`：在项目根执行，一键安装依赖、构建 H5 包并生成 `release/dev/vX` 的离线包；可用环境变量覆盖 `H5_PATCH_BASE_URL`（远端资源基址，默认占位 `https://xxx/xxx`）、`BUILD_DIR`（默认 `dist`）、`OUT_DIR`（默认 `release/dev`）、`KEEP_PREVIOUS`（是否保留上一版，默认 true）。

## 日志与排查
- `BreezeH5`: 入口、本地版本、检查/下载/激活/无更新等。
- `ImageCache`: 拦截请求、路径重写。
- 若见 ENOENT，确认设备目录 `files/<project>/vX/index.html` 是否存在，前缀是否为 `/<project>/`。

## 最新注意事项
- 内置 WebViewClient 负责远端域名映射、本地/远端兜底；宿主可直接 `attachWebView`/`loadEntry`，或在自定义 Client 里委托 `interceptRequest`/`shouldOverrideUrlLoading`。
- appassets 请求会自动补全 `/projectName/vX/` 前缀（含缺失 projectName/版本号的 `/js/`、`/css/`、`/static/` 等），并优先使用本地最佳版本。
- 仅在入口实际使用本地包时才对远端域名重写（如支付回调）；若走 fallback/远程不会重写。
- 宿主如需自定义协议（如 `localimg://`），应在插件 client 之后追加，其他拦截/兜底交由插件。
- 预置离线包：扫描 assets 下 `<assetBasePath>/vX/dist.zip`，取最高版本作为种子解压到 `files/<project>/vX/` 并激活，避免 seedVersion 写死与预置不符。
