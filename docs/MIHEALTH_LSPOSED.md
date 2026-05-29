# Heartwith Mi Health LSPosed Client

`clients/heartwith-mihealth-lsp` 是 Heartwith 的分支采集端。它不直接连接 BLE，而是作为 LSPosed / libxposed API 101 模块注入 `com.mi.health`，从小米运动健康的实时心率链路读取心率，再沿用 Heartwith 服务端上传协议。

## 数据来源

模块只在 `com.mi.health:device` 进程安装心率解析 hook，避免主进程和 device 进程重复解析和上传。当前命中点包括：

- `DeviceDataHandlerAdapter.handlePacketInternal(...)` 的 `wear-raw` 心率数据。
- `LaunchSportModel.setHeartHr(int)`。
- `LaunchViewBean.setHeartRate(String)`。
- 部分 Huami `onHeartRateChanged(int)` 回调。
- 老 Huami 设备链路：
  - `twu.e(byte[])` / `twu.i(byte[])` 原始实时心率包，心率位于 `data[1]`。
  - `me.i1(...)` / `buu.b(...)` 对应的原始 Huami 实时测量启动入口。
  - `auu`、`HuamiDevice$y` 等老回调类的 `onHeartRateChanged(int)`。

模块会在小米运动健康进程 attach 后尝试调用原有 `startDeviceHr(...)` / `registerDeviceHr()` 链路；如果捕获到老 Huami 设备对象，也会复用小米健康已有对象调用原始实时测量入口。它不会扫描 BLE，也不会直接连接手环。

## 上传协议

上传保持和 Android BLE 客户端一致：

- `POST /api/v1/collector/sessions` 创建采集会话。
- `POST /api/v1/hr/batches` 使用 `Content-Type: application/cbor` 批量上传。

LSPosed 分支的 `client_platform` 为 `android-lsposed`，`device_model` 为 `Xiaomi Health Hook`。批量窗口最多 `8s`，离线最多缓存最近 `5min`。

因为上传发生在小米运动健康进程内，`http://` 明文地址不会依赖模块 App 的网络安全配置；模块对 HTTP 使用轻量 HTTP/1.1 POST，对 HTTPS 使用系统 `HttpURLConnection`。

## 配置同步、状态显示和通知

模块 App 提供一个接近 Heartwith Android 端的深色卡片配置页：

- 顶部显示最近一次采集到的心率、来源和更新时间。
- 可配置服务器地址、显示名称和 Hook 上传开关。
- Android 13 及以上会请求通知权限。
- 配置 App 打开或保存时，会通过显式广播把配置同步给小米健康 hook 进程。
- 小米健康 hook 进程会把配置持久化到自己的运行时缓存，后续小米健康自启动或后台恢复时不需要再次打开配置 App。
- 小米健康进程 hook 到心率后，会通过显式广播写回模块 App 状态，并显示常驻状态通知。

通知只反映“已采集到的当前心率”，不受服务器是否可用影响；上传失败只会影响服务端大厅，不会清空本地心率显示。

## 保活策略

这个模块自身不需要，也不应该再做一套前台服务保活：

- hook 代码运行在小米运动健康进程内。
- 小米运动健康进程存在时，模块才能接收心率并上传。
- 小米运动健康被系统杀死时，模块也会停止。
- 如果需要后台持续采集，应给小米运动健康开启自启动、后台无限制、省电策略白名单等权限。

也就是说，LSPosed 版本只需要小米运动健康保活；Heartwith LSP 模块 App 本身只提供配置页和最近状态展示。

首次收到有效心率后会锁定该来源，后续其它 hook 路径只做快速返回，不再解析心率包；如果 2 分钟没有新心率，会允许重新选择来源。

为了降低后台开销：

- 不再向小米健康 UI 注入悬浮心率点。
- 不 hook `Activity.onResume/onPause`，只 hook `Application.attach` 和心率数据链路。
- 主进程只接收配置广播，不安装心率解析 hook。
- `startDeviceHr(...)` 只要有一个 helper 调用成功就停止继续尝试，避免重复打开实时心率链路。
- 上传采用最多 `8s` 批量窗口，失败后退避重试，日志按分钟节流。

## 构建

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew :heartwith-mihealth-lsp:assembleDebug
```

APK 输出：

```text
clients/heartwith-mihealth-lsp/build/outputs/apk/debug/heartwith-mihealth-lsp-debug.apk
```

安装后在模块配置页设置服务器地址和显示名称，在 LSPosed 中启用模块并将作用域设为 `com.mi.health`，然后重启小米运动健康。
