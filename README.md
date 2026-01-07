# BreezeH5 插件说明

本插件用于 WebView 加载 H5 的本地缓存加速与增量更新，支持离线首屏、定时拉取最新包、版本兜底与宿主最小改动接入。适配 `appassets.androidplatform.net` 虚拟域名，项目名可按环境区分（示例：`your-h5-dev`）。

## 功能概览
- 本地种子/已下载包优先加载，离线可用。
- 定时（指数退避）检查远端 OSS 的 `lastversion` 与 `manifest.json`，自动下载/解压/清理旧版本。
- `lastversion` 支持 JSON 或纯文本（如 `{"version":9}` 或 `v9`）。
- 远端域名资源自动重写到本地包（含子资源），支付回调等也可映射。
- Wi‑Fi 条件检查、定时开关、宿主 onResume 重置定时。
- 日志 TAG：`BreezeH5`（主流程）、`ImageCache`（拦截与资源重写）、`H5Entry`（入口 URL）。

## 目录与路径
- 本地根目录：`/data/user/0/<package>/files/<projectName>/vX/`，入口文件 `index.html`。
- 虚拟域名：`https://appassets.androidplatform.net/<projectName>/vX/index.html`。
- 资产种子（可选）：`app/src/main/assets/<projectName>/vX/dict.zip`（首次可离线）。
- OSS 结构示例：`https://<your-oss-base>/h5/patch/<env>/lastversion` 与 `.../vX/manifest.json`、`.../vX/dict.zip`。

## 接入流程
1) **构建 H5Config（每个环境）**
   - `projectName`: 例如 `your-h5-dev` / `your-h5-pre` / `your-h5-prod`
   - `baseUrl`: OSS 基址，如 `https://<your-oss-base>/h5/patch/dev`
   - `fallbackUrl`: 环境对应的在线 H5 域名
   - `remoteDomains`: 需重写到本地的远端域名列表（子资源也会重写）
   - `routePrefixes`: 需要重写的路由前缀（如支付回调 hash 路由）
   - `assetBasePath`: 资产种子前缀（通常与 projectName 相同）
   - 其他：`assetZipName=dict.zip`，`lastVersionFile=lastversion`，`manifestPattern=v%d/manifest.json`
   - 定时：`initialCheckDelayMillis=30s`，`min=5min`，`max=60min`，`backoffMultiplier=2`，`enablePeriodicCheck=true`，`useWifiOnly=true`，`keepVersions=5`。

2) **初始化**
   ```java
   H5Config config = buildEnvConfig(currentEnv); // 内部会保存 currentH5Config
   BreezeH5Manager.initialize(getApplicationContext(), config,
       (version, url) -> { rebuildH5AssetLoader(version); webView.loadUrl(url); return true; },
       (u, r) -> showNoNetworkPage());
   ```
   - `rebuildH5AssetLoader(version)` 以 `filesDir/<projectName>/` 为根，前缀 `/<projectName>/` 注册 WebViewAssetLoader。
   - 首屏使用 `BreezeH5Manager.resolveEntryUrl()`（本地优先，缺失则 fallback）。

3) **WebView 拦截**
   - 在 `shouldInterceptRequest` 内：
     - 命中 `appassets.androidplatform.net`：`assetLoader.shouldInterceptRequest(uri)`（插件内已用前缀 `/<projectName>/`、根 `filesDir/<projectName>/` 注册）。
     - URL 命中 `remoteDomains`：映射到本地 `https://appassets.../<projectName>/v<best>/<path>`（根路径补 `/index.html`），用 `assetLoader` 返回。
     - 支付/回调等 URL 可用 `mapToLocalIfPossible` 或自定义匹配后重写。

4) **宿主生命周期**
   - `onResume` 调用 `BreezeH5Manager.onHostResume()` 重置定时检查。
   - 需要停止时可调用 `BreezeH5Manager.stop()`。

## 更新与清理
- 版本判定：`lastversion` -> `manifest.json`（含 `version/url/hash/size`）。
- 下载解压到 `vX/`，`keepVersions` 保留最新 N 个，自动清理旧版本。
- 若 `onVersionReady` 返回 `true`，立即激活并保存 `activeVersion`。

## 日志排查
- `BreezeH5`: 入口、本地版本、检查/下载/激活/无更新等。
- `ImageCache`: `shouldInterceptRequest` 请求、`WebViewAssetLoader 返回响应`、`远端域名重写到本地`。
- 若见 ENOENT，确认设备目录是否为 `files/<projectName>/vX/index.html`，以及 WebViewAssetLoader 前缀是否带 `/<projectName>/`（插件已默认如此，若宿主自定义 loader 需保持一致）。

## 测试建议
- 断网启动，确认加载 `appassets.../vX/index.html`，子资源也被重写到本地。
- 恢复网络，观察 30s/60s/5min 的定时检查日志，确认 `lastversion` 能解析 JSON。
- 上传新版本（vX+1），等待自动下载，查看 `version X activated immediately` 与本地文件夹生成。

## 最新注意事项
- 插件内置 WebViewClient 负责远端域名映射，本地/远端兜底，宿主可直接调用 `attachWebView`/`loadEntry` 或在自定义 Client 里委托 `interceptRequest`/`shouldOverrideUrlLoading`。
- appassets 请求会自动补全 `/projectName/vX/` 前缀（含缺失 projectName/版本号的 `/js/`、`/css/`、`/static/` 等），并优先使用本地最佳版本。
- 仅在入口实际使用本地包时才对远端域名重写（如支付回调）；若走 fallback/远程不会重写。
- 宿主如需自定义协议处理（如 `localimg://`），应在插件 client 之后追加，其他拦截/兜底交由插件。
- 预置离线包：插件会扫描 assets 下 `<assetBasePath>/vX/dict.zip`，取最高版本作为种子解压到 `files/<project>/vX/` 并激活，避免 seedVersion 写死与预置不符导致首次安装不走离线包。
