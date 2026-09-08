## 1. 这个模块在做什么

**进程模型**（入口 `MiHealthHookModule.java:697` `onPackageReady`）：只在 `com.mi.health` 主进程和 `:device` 工作进程装 hook，其它进程 `:731` 直接 return。

| 进程 | 安装的 hook 组 | 稳态是否持续跑 |
|---|---|---|
| `:device`（BLE 工作进程） | `hookSleepStatus`、`hookMiWearSleepChannel`、`hookLifecycle`、`hookHeartRateSinks`(:3045，25 个目标)、`hookHeartRateStopControls` | **是**，心率包每秒级 |
| 主进程 | `hookSyncUiSignals`(:1144)、`hookPassiveSportHeartRateSinks`(:1128) | 大部分只在运动会话/UI 交互时 |
| 两者 | `hookHeartwithCleartextPolicy`(:750)、`hookLifecycle` | 极低频 |

**持续运行的东西**（release）：

- `:device` 里 ~25 个 hook 常驻。真正解包的只有当前活跃的那一条 raw 路径（`hookWearRawHandler` :7762 / `hookEcoRawHandler` / `hookEcoRemoteDataHandler`，互斥的设备栈）；typed 路径被 `shouldUseTypedHeartRateFallback`(:7756, `TYPED_HEART_RATE_FALLBACK_STALE_MS`=30s) 压住；10 个 `hookHuamiCallback` 拿的是已解好的 int，不解包。
- 每个被接受的心率样本走 `onHeartRate`(:10785)：源锁 → 去重(`DUPLICATE_WINDOW_MS`=900ms) → 写 prefs → 状态广播 → 通知 → 投递到 `UPLOAD_WORKER` 做 8s 批量 CBOR 上传。
- 两个静态单线程 executor（`WORKER` :200 / `UPLOAD_WORKER` :208，daemon），空闲时核心线程常驻但不耗电。
- 定时器：`scheduleHeartRateWatchdog`(:11305) 60s Handler（只在 `lastHr>0` 时自续）、心率 alarm watchdog 5min（:11337）、睡眠状态轮询 25min（`SLEEP_STATUS_POLL_INTERVAL_MS`）、周期同步 alarm。**所有 release alarm 都是 `ELAPSED_REALTIME`/`RTC` 非 wakeup + 大 window**，`setAndAllowWhileIdle` 只在 `DebugBuild.ENABLED` 分支（:11357）。

**按需**：`hookSleepDiagnostics`(:4139) 十组诊断 hook 全部在 `DebugBuild.ENABLED` 下；npatch 兼容 hook 只在检测到 npatch 后装。

---

## 2. 总体评价

**已经做对的（不是客套，是逐条核过的）：**

1. **Release alarm 全部非 wakeup**。`:11363` / `:1307` / `:1415` 全是 `setWindow(ELAPSED_REALTIME|RTC, …, window)`，window 2–15 分钟。Doze 期间会被整体推迟到维护窗口，不会把 AP 叫醒。这是省电做得最好的一处。
2. **R8 `-assumenosideeffects` 真正生效**。`proguard-rules.pro` 剥掉了 `debugLine/diagLine/logLine/debugSleepLine/debugSyncLine/DebugSleepLog.line` 以及 `describe*/format*/short*/hex/maskDid`，**连同调用点的字符串拼接一起消失**。我在每包路径上逐个检查了 `diagRawPacket`(:10676)、`diagPacket`(:10684)、`diagPacketShape`(:10707)：全部 `!VERBOSE_LOGS` / `!DebugBuild.ENABLED` 先返回，且调用点传的是原始对象不是拼好的串。**热路径上没有未门控的字符串拼接**——这是本次审查最常见的坑，这个项目没踩。
3. **进程隔离**：主进程不装 raw BLE 解析 hook（只装运动会话相关的 `LaunchSportModel$*`）。
4. **typed fallback 自抑制**（:7756）：raw 路径一旦成功解码，30s 内 typed 路径整组不解包，避免了同一包被多路重复解析。
5. **packet parser 双检锁缓存**（`resolvePacketParser` :10222），`findByteArrayParser` 的全类扫描一个进程只跑两次。
6. **动态解析器有正/负缓存**（`extractHrFromDynamicPacket` :10424，`heartRateAccessorCache` + `heartRateAccessorMissCache` + `DYNAMIC_HEART_RATE_RETRY_MS`=5min）。**这个机制已经存在，下面 P0-2 的修法就是把它复用到命名反射链上。**
7. 无悬浮窗、无 Activity 生命周期 hook、通知是普通 `notify` 不是前台服务。

**剩下的肥肉集中在两处**：① 每个心率样本触发的 **SharedPreferences 写 + 广播 + 通知重建**（I/O 和 binder，不是 CPU）；② 已接受包的 **未缓存命名反射**（`callMethod` / `getFieldValue` 走 `getDeclaredMethods()` 全类层次扫描）。其余都是微优化。

---

## 3. 优化方案（按收益排序）

| 优先级 | 改动 | 位置 | 预期收益 | 风险 | 工作量 |
|---|---|---|---|---|---|
| **P0-1** | 状态写/广播/通知改纯时间节流，去掉 3bpm 项 | `MiHealthHookModule.java:10913-10925`、`HeartwithStatus.java:57,69,94` | prefs 写 + 广播 + 通知重建从 ~400–1500/h 降到 360/h + 120/h | 低（面板 BPM 刷新变慢） | 小 |
| **P0-2** | 心率提取链的反射结果按 Class 缓存（含负缓存） | `:11588 findField`、`:11602 callMethod`、`:11612 findCompatibleMethod`；调用方 `:10373/:10394/:10358` | 每包省 2–3 次 `getDeclaredMethods()` 层次扫描 + 1 次带栈异常构造，估算 ~15–30 MB/h 垃圾 | 中（缓存 key 必须含参数类型，见下） | 中 |
| **P1-3** | 30s 配置刷新在配置未变时跳过 prefs 落盘 | `HeartwithUploader.java:66-74` + `:193-208` | 省 120 次/h 全量 prefs 重写 | 低 | 小 |
| **P1-4** | `LAST_HR_SEEN_PERSIST_MS` 8s → 20–25s | `MiHealthHookModule.java:107` | 450 → ~150 次/h | 中（必须远小于 `CROSS_PROCESS_HR_RECENT_MS`=60s） | 极小 |
| **P2-5** | 心率 alarm watchdog 加指数退避终止条件 | `:11407-11430` | 无表带时从 12 次/h 强启动尝试降到 ~1 次/h | 中（恢复延迟变长） | 小 |
| **P2-6** | `View` 全局 hook 改惰性安装 + 已捕获短路 | `:744`、`:1150`、`:1169`、`:1215` | 默认配置下完全不去优化 `View.setOnClickListener` | 中（同步按钮回退路径） | 小–中 |
| **P3-7** | npatch 检测顺序调整 + 候选去重 | `:2621`、`:2645`、`:1094` | 每次冷启动每进程省 3–4 次 APK 中央目录解析 | 极低 | 极小 |

---

### 高收益

#### P0-1 状态三件套的节流条件

**现状（已核实）**：`shouldUpdateStatus`(:10913)

```java
if (Math.abs(hr - lastStatusUpdateBpm) >= STATUS_UPDATE_CHANGE_BPM /* 3, :158 */ ||
        elapsed - lastStatusUpdateElapsedMs >= STATUS_UPDATE_MIN_INTERVAL_MS /* 10s, :128 */)
```

是 **OR**，所以 10 秒下限形同虚设：只要跟"上次已发布值"差 ≥3bpm 就立刻放行。放行一次的代价（:10842-10858，在 `WORKER` 上）：

1. `HeartwithStatus.writeLocal` → `HeartwithSettings.PREFS` 的 `.edit().apply()`，整份 XML 重写 + QueuedWork 线程唤醒（`HeartwithStatus.java:57-63`）。
2. `sendRegisteredStatus` → `context.sendBroadcast`，一次进 system_server 的 binder 往返（`:69-78`）。
3. `showHookProcessNotification` → 完整 `Notification.Builder` 重建 + `manager.notify`（`:94-138`）。

**两个额外事实（原始审查漏掉、我独立确认）：**

- `HeartwithStatus.java:94-98` 的二级门槛用的是**完全相同**的 `NOTIFICATION_CHANGE_BPM = 3` / `NOTIFICATION_MIN_INTERVAL_MS = 10_000L`（`HeartwithStatus.java:32,34`），两个静态计数器同步推进，所以它**永远不会拦下任何一次已经通过 `shouldUpdateStatus` 的调用**——是死逻辑。
- 广播绝大多数时候**零接收方**：`setPackage(MODULE_PACKAGE)` + `FLAG_RECEIVER_REGISTERED_ONLY`(`:71,76`)，唯一注册方是 `SettingsActivity`，且在 `onResume` 注册、`onPause` 反注册（`SettingsActivity.java:88-107`）。设置界面没开时，每次广播都是发给空气的 binder 往返。

**改法**：三个消费者解耦，全部改成纯时间节流——通知 30s，`writeLocal`/广播 10s，删掉 bpm 差值项。1 Hz 下 3bpm 的抖动不是用户可感知的信息。同时把 `HeartwithStatus.java:94-98` 的重复判定删掉或改成唯一真相源。

**为什么省电**：worst case 从 3600 次/h 降到 480 次/h；按 ±2bpm 死区的现实估计（400–1500/h）也是 1–3 倍的削减。省的是 flash 写、QueuedWork 线程唤醒和 binder，不是 CPU。

**可能坏什么**：设置面板的实时 BPM 从近连续变成 10–30s 一跳。**不会丢数据**——上传路径是独立算的（`shouldUploadAcceptedHeartRate` :10891），每个被接受的样本都会进 `HeartwithUploader.onHeartRate`，与 `updateNotification` 无关。

> ⚠️ **不要**用 `HeartwithStatus.KEY_VIEWER_ACTIVE_UNTIL_MS` 来做"仅在有人看时才写"。`markViewerActive`(`HeartwithStatus.java:80-86`) 写的是**调用方进程自己的** prefs；hook 代码跑在 `com.mi.health:device`，读不到模块 App 的 prefs，也读不到宿主主进程的。要做这个开关，得走已有的 `ACTION_CONFIG_CHANGED` 广播通道（接收方在 `:2845-2857`）**推**进 hook 进程，而不是每个样本去**拉**。

---

#### P0-2 每包命名反射未缓存

**现状（已核实调用链）**：活跃 raw 路径每个 `type==8` 包 →
`extractHrFromWearRaw`(:10208) → `extractHrFromPacketBytes`(:10326，parser 已缓存) → `extractHrFromPacket`(:10350) →

- `extractHrFromIxs`(:10373)：`callMethod(packet, "u")`
- → `extractHrFromSportDataPayload`(:10394)：`callMethod(fitness, "k0")`、`getFieldValue(sportData, "c")`
- 若未命中：`callMethod(fitness, "p")`、`getFieldValue(sportRealtime, "f")`
- 仍未命中 → `extractHrFromKxs`(:10358)：`callMethod(packet, "r")`、`callMethod(paa, "o")`、`getFieldValue(…, "f")`

每个 `callMethod`(:11602) 都进 `findCompatibleMethod`(:11612)：

```java
while (current != null) {
    Method[] methods = current.getDeclaredMethods();   // 每次都重新分配 Method[] + 每个 Method 对象
    ...
}
throw new NoSuchMethodException(clazz.getName() + "." + name);   // :11632，含 fillInStackTrace
```

`getFieldValue`(:11567) → `findField`(:11588) 同理，每次 `getDeclaredField`。**两者都没有任何缓存**。混淆后的 protobuf-lite 消息类方法数很大（对 jadx dump 抽查过量级），单次 `getDeclaredMethods()` 分配几 KB。

Eco 路径还多一笔：`u()` 存在但 `k0()` 不存在，所以**每个 eco 包都会构造一次带完整栈的 `NoSuchMethodException`**。

**改法（推荐）**：不要做通用的 `Map<Class+name+arity, Method>` 缓存。原因：`findCompatibleMethod` 靠 `parametersMatch()` 按运行时参数类型解析同 arity 重载，而 `callMethod` 在冷路径上是带真实参数用的（如 `:6854 syncData(did, bytes, callback)`、`:6913 getTodayFitnessIds(...)`），只按 arity 做 key 会返回错误重载，`invoke` 抛 `IllegalArgumentException` 被 `catch (Throwable)` 吞掉，**静默搞坏数据同步或睡眠检测**。

正确做法是**复用项目里已有的 `heartRateAccessorCache` 机制**（字段 :245，用法见 `extractHrFromDynamicPacket` :10424）：按 packet `Class` 缓存整条访问链（`u→k0→c` 还是 `u→p→f` 还是 `r→o→f`），命中即直接读，未命中写进已有的 miss 缓存并受 `DYNAMIC_HEART_RATE_RETRY_MS`(5min) 保护。大致形状：

```java
// 伪代码，只示意 key 与生命周期
HeartRateAccessor acc = heartRateAccessorCache.get(packet.getClass());
if (acc != null) return acc.read(packet);   // 零反射
// miss：走一次现有的命名解析，成功则把整条链固化进 cache
```

**为什么省电**：把每包 2–3 次全类层次扫描 + 每 eco 包一次带栈异常，降到零反射。粗估每包 4–9 KB 垃圾（这个字节数是估算，需实测），1 Hz 连续 → 十几到几十 MB/h 的分配压力消失，`:device` 进程的 GC 频率随之下降。

**可能坏什么**：宿主 App 升级换类后缓存过期。按 `Class` 对象（不是类名）做 key 即可自然失效；且静态缓存活在模块 classloader 里，热重载时整体替换，不会 pin 住旧的宿主 Class。

---

#### P1-3 30s 配置刷新的无谓落盘

`onHeartRate` 第一句就是 `refreshRuntimeSettingsIfNeeded(appContext, false)`(:10789)，30s 节流（:2948 硬编码 `30_000L`）到期后调 `applyRuntimeSettings` → `uploader.applySettings`(`HeartwithUploader.java:66`) → `persistRuntimeCache`(`:193`) **无条件写 5 个 key**。

**不需要加 `equals()`**：`refreshRuntimeSettingsIfNeeded` 传的是 `uploader.currentSettings()`(:2952)，也就是 uploader 自己的 `settings` 字段原样传回，30s 这条路径上是**引用相等**的。在 `HeartwithUploader.applySettings` 开头加一句身份判断即可：

```java
if (settingsLoaded && next == settings) { rememberContext(context); return; }
```

**边界（重要）**：这个跳过**只能限制在 `HeartwithUploader` 内部**的 `persistRuntimeCache`/`logSettings`/`configureDelegate`。**不能**跳过 `applyRuntimeSettings`(:2872) 下游的工作——它同时在 `:2933` `scheduleSleepStatusPoll` 和 `:2935` `scheduleMiWearSleepMonitor`，这 30s 重申是睡眠检测的 keep-alive。另外 `configureDelegate()` 里 `delegate.configure()` 有副作用（配置变了且有待上传时触发延迟 flush），跳过它理论上会少一次重试轻推；`submitHeartRate` 自己会排 flush，实际影响应该为零，但这是行为变化，要心里有数。

---

#### P1-4 `LAST_HR_SEEN_PERSIST_MS`

`persistLastHeartRateSeen`(:11206) 每个被接受样本调用一次（:10829），8s 下限 → 450 次/h prefs 写，和 P1-3 写的是**同一个文件** `heartwith_mihealth_runtime`。

**不要按原建议直接提到 60s**：`CROSS_PROCESS_HR_RECENT_MS` 正好是 `60_000L`(:105，`hasRecentHeartRateInLocalPrefs` 里用)。写入粒度等于读取窗口会让 `hasRecentHeartRateInAnyProcess()` 在心率正常流动时抖动，而消费方把 miss 当成"实时心率停了"，触发的动作比一次 prefs 写贵得多：`shouldStartNow`(:8408) 放行重启、`recycleColdStartIfStillStalled`(:8562) 进程回收、`scheduleLegacyKickCheck`(:2267)。

**安全值：20–25s**（仍是 3 倍削减）。若确实想要 60s，必须同一改动里把 `CROSS_PROCESS_HR_RECENT_MS` 提到 120s。

---

### 低收益 / 可选

#### P2-5 心率 alarm watchdog 无终止条件

`handleHeartRateAlarmWatchdog`(:11407) 两个出口都重新武装，且都把 `lastHeartRateWatchdogAlarmElapsedMs = 0L`，绕过 `:11343` 的 2 分钟节流。`cancelHeartRateAlarmWatchdog` 只有两个调用点：`:587`（热重载）和 `:2927`（设置里关 hook）——**BLE 断连、蓝牙关闭、设备变更都不取消**。表带扔抽屉里也会一直循环。

**但必须修正原始判断的严重程度**：release 走的是 `:11363` 的 `setWindow(ELAPSED_REALTIME, …, 2min window)`，**非 wakeup、非 allow-while-idle**。Doze 期间不会把手机叫醒，"整夜 288 次唤醒"是 debug 分支的行为。真实代价只发生在设备本来就醒着的时段。

代价确实比看起来贵：`"watchdog-alarm:no-heart-rate"` 命中 `isForceStartReason`(:8338)，跳过 `shouldStartNow`，每 5 分钟在 `WORKER` 上跑完整的 `startOriginalHuamiBleDevice` + `startOriginalHuamiController` + `ensureLaunchModels` + `startDeviceRealtimeHr`（`:8311-8314`）。只有 aggressive helper 扫描被 `START_HELPER_WATCHDOG_COOLDOWN_MS`=10min 节流。

**改法**：只做指数退避 —— 连续 stale 次数 → 5 → 10 → 20 → 60min 封顶；`:10832` 每次接受心率时把连续计数清零。

**不要做**（原始建议的另外两条）：
- 不要"在断连/设备变更时直接取消，有设备时才重新武装"。这个 watchdog 存在的理由恰恰是**表带仍连着但实时流静默死掉**；把重新武装绑到 hook 推导的连接状态上，等于让最后一道恢复手段依赖同一批不可靠的宿主内部信号，漏一次事件就永久不再采集。
- 不要给 force 路径加最小间隔。`isForceStartReason` 同时覆盖 `stop:`、`device-changed:`、`watchdog:`——`scheduleRealtimeHrResume`(:11278) 对 `device-changed:` 特意用 250ms 延迟就是为了快速恢复，加节流会让每次重连后丢心率。

---

#### P2-6 `View` 全局 hook

`hookSyncUiSignals`(:1144) 在主进程无条件安装（`:744`，没有 `DebugBuild` 门控），hook 了 `android.view.View.setOnClickListener`(:1150) 和 `View.performClick`(:1169)。

**真实代价被原始审查夸大了，我逐条核过**：
- `maybeCaptureSyncButton` 第一句(:1215) 就是 `if (!periodicSyncEnabled && !DebugBuild.ENABLED) return;`，而 `HeartwithSettings.java:78` 是 `getBoolean(KEY_SYNC_ENABLED, false)` —— **周期同步默认关**。默认安装下 `viewText` 根本不会被调用，每次 hook 只有 trampoline + 两次 `instanceof`，**零分配**。
- 即使开了同步：`TextView.getText()` 常规情况返回 `String`，`toString()` 返回 this，无环绕空白时 `trim()` 也返回 this；非 TextView 分支 `getContentDescription()` 通常为 null 返回 `""` 字面量。"每次调用分配 1–2 个 String / 每秒几十 KB 垃圾"**站不住**。
- `View.performClick` 是用户点击频率，冷路径。

**真正的成本是 ART 永久去优化**：`setOnClickListener` 是个两行 setter，正常会被内联到全进程各处；hook 之后整个主进程所有调用点永远走 trampoline，进程生命周期内不可逆。换来的只是一个 `WeakReference`。

**改法（两条，都安全）**：
1. `maybeCaptureSyncButton` 开头加 `if (syncButtonView != null && syncButtonView.get() != null) return;` —— 捕获后彻底短路。当前代码没有这个判断，捕到之后仍然每次跑 `viewText` + `contains`。因为是 `WeakReference`，Activity 销毁被回收后 `.get()` 变 null，自动恢复捕获能力。
2. 把 `hookSyncUiSignals` 的两个 `View` hook 改成惰性安装：只在 `periodicSyncEnabled` 变 true 时装（`:2914` 已有 `wasPeriodicSyncEnabled != periodicSyncEnabled` 的分支）。默认用户完全不付去优化代价。**装了就别卸**，保留重新捕获能力。

**不要做**：不要"捕到后 unhook"或用一次性 volatile 闩锁。`triggerMiHealthSync`(:1862) 在 `:1896-1905` 要求 view 仍 `isAttachedToWindow()`，Activity 换了必须重捕；一次性闩锁会永久废掉这条回退同步路径。另外本文件所有 `hook(...)` 返回值都没保留，也没有 handle 可以 unhook。

---

#### P3-7 npatch 的 ZipFile 扫描

`isNpatchWrapped`(:2620) 把**最贵的** `findNpatchWrapperApk` 放在 `||` 链最前：

```java
if (findNpatchWrapperApk(context) != null || hasNpatchManifestMetadata(context) || hasNpatchRuntimeStack())
```

`findNpatchWrapperApk`(:2645) 构造 4 个候选路径（`sourceDir` / `publicSourceDir` / `getPackageCodePath()` / PackageManager 查询的 `sourceDir`——**没有去重，普通安装下四个是同一个 base.apk**），逐个 `new ZipFile()` 读中央目录。普通 LSPosed 安装下四次全跑完且结果恒为 false。调用点在 `Application.attach` 的 after-hook（`:958`），主进程和 `:device` 各一次，热重载再一次（`:533`）。而且 `:1094` 是 `!isNpatchWrapped(context) || !npatchHooksInstalled.compareAndSet(...)`，**扫描在闩锁之前**。

**改法**（三条都是行为保持的）：
1. 把 `||` 链改成 `hasNpatchRuntimeStack() || hasNpatchManifestMetadata(context) || findNpatchWrapperApk(context) != null`。栈遍历最便宜，`hasNpatchManifestMetadata` 是一次 binder，ZipFile 最后。三个谓词都是纯函数（各自 try/catch 返回 false），唯一的副作用 `npatchWrappedDetected = true` 在调用方 OR 求值之后，顺序无关。返回值只用了 `!= null`，且 `findNpatchWrapperApk` 没有别的调用方。
2. 候选数组去重（`LinkedHashSet`）。
3. `:1094` 先用 `npatchHooksInstalled.get()` 快速跳过已安装状态；只有 `isNpatchWrapped` 确认成功后才执行 `compareAndSet`。不能提前占用闩锁，否则一次临时探测失败会让本进程永久失去重试机会。

**不要做**：不要把结果缓存进 SharedPreferences 按 versionCode 失效。那等于在同一条 `Application.attach` 关键路径上换成一次同步 prefs XML 加载；两个进程还要一致；更糟的是 npatch 重新打包可能不改宿主 versionCode，缓存的 stale `false` 会静默禁掉兼容 hook，在 npatch 部署下直接把 App 弄坏。用进程内 volatile（`npatchWrappedDetected` + 一个 `checkDone` 标志）就够。

---

#### 纯微优化（列出来但**不建议单独动手**）

- `:11355` `alarmManager.cancel(pendingIntent)` 是多余的：requestCode 固定 `0x23014333`，Intent 的 action/package 不变，extras 不参与 `filterEquals`，紧接着的 `setWindow` 本身就会替换。删一行省 ~30 次 binder/h，零行为变化。
- `shouldIgnoreSource`(:10947) 是死代码：它就是 `isDeprecatedHeartRateSource`(:10909) = `source.startsWith("launch-")`，而全仓库（含 debug/release source set）**没有任何 source 字面量以 `"launch-"` 开头**——我 grep 过，只有定义行本身命中。12 个 hook 入口的调用恒为 false，且 `:10796` 在 `onHeartRate` 里又查了一遍。删掉是纯可读性收益，**零性能收益**（nanoseconds，无分配）。
- `:11423` 的 `importantLine("hr alarm watchdog stale…" + lastHr + ", ageMs=" + heartRateAgeForDebug())` 确实未门控（`importantLine` :11894 不在 `-assumenosideeffects` 名单里，是刻意保留的 release 信号通道）。但它上限 288 行/天且只在心率已经死了的状态下触发——那正是用户报 bug 时最需要的那一行。**建议保留**，最多加个每小时一次的限流；不要 `DebugBuild` 门控掉。

---

## 4. 不建议改的地方

| 位置 | 看起来像问题 | 为什么别动 |
|---|---|---|
| 所有 hook 丢弃 `HookHandle`（`:11474` `hookAround` / `:11487` `hookAfter`） | "锁定源之后无法卸载多余 hook" | 存活 hook 体在 release 里就是 trampoline + 一次拆箱 + 一次 int 比较（`:7690-7699` 之类），~6 个 demux，量级 O(100µs)/s，电量上不可测。而卸载会**丢数据**：`resetHeartRateSource` 依赖"下一个开火的 hook"重新锁定；`sourcePriority`(:11014) 的优先级升级也要求更高优先级的 sink 能开火。热重载唯一真正需要卸载的场景已由框架的 `param.getOldHookHandles()`(:445-469) 覆盖。 |
| 关闭 hook 开关时不卸载 hook | "off-switch 不省电" | 不成立。`applyRuntimeSettings:2922-2938` 在 `hookEnabled=false` 时会 `started=false`、`cancelHeartRateAlarmWatchdog`、`++miWearMonitorGeneration` + `cleanupIsolatedMiWearHandler`、`cancelSleepStatusPoll`、主进程 `cancelPeriodicSync`；`onHeartRate:10790` 直接返回。所有 alarm、轮询、CBOR 批次、上传、通知、prefs 写全部停止，只剩 trampoline。 |
| 热重载时不向宿主注销回调（`releaseHotReloadResources` :645-650） | "泄漏了注册进宿主的回调" | 电量影响为零：孤儿 proxy 因 `onHeartRate:10786` 的 `if (!lifecycleActive) return;` 完全惰性；模块本来就刻意让那一条实时 HR 流常开。真正的 type-112 通知 sink（`addDeviceDataHandler` :3274）**已经**在 `cleanupIsolatedMiWearHandler` :3399 里 `removeDeviceDataHandler` 掉了；`getMiWearCallback`(:4089) 只是一次 10s 超时 RPC 的一次性响应回调，不是订阅。残留的只是两个匿名 `InvocationHandler` pin 住旧 module 实例（几百 KB，模块升级时一次性）。**原建议里"强制 `shouldKeepRealtimeHrActive()` 为 false 再调 stop"会在热重载中途真的掐断 BLE 心率流**，逼新一代走冷启动重试甚至 `:8589` 的进程自杀，得不偿失。若真想清理，把那两个 `InvocationHandler` 改成 static 内部类即可。 |
| `hookHeartwithCleartextPolicy`(:750) 里的 prefs 读 + `new URL()`（`configuredHeartwithServerHost` :883） | "每次网络连接都跑" | 频率判断错了。`NetworkSecurityPolicy.isCleartextTrafficPermitted(String)` **只在明文路由上被查**（OkHttp3 只在 `sslSocketFactory == null` 分支调用；平台 `HttpURLConnection` 只有 `HttpHandler` 装 `CleartextURLFilter`，`HttpsHandler` 不装）。小米运动健康自己的 https 流量一次都不会命中。真实调用方基本只有模块自己的上传，且只在用户配了 `http://` 服务器时——8s 批次 → 上限 ~8 次/分钟。缓存这个值的收益是每小时微秒级，而缓存失效风险是真的：`KEY_CACHED_SERVER_URL` 至少有 `HeartwithUploader.java:74 / :152 / :251` 三处不经过 `applyRuntimeSettings` 的写入点，缓存 stale 会让新配的 http 服务器上传被明文策略拒绝——**丢数据**。 |
| 所有 alarm 的 `setWindow` + 大 window | "windows 太大，恢复慢" | 这是本模块省电的核心设计，别为了"更及时"改成 `setExact` 或 `setAndAllowWhileIdle`。 |
| `WORKER` / `UPLOAD_WORKER` 两个常驻单线程 executor | "空闲也占线程" | 两个 daemon 线程，空闲时不调度不耗电，栈内存几十 KB。改成有超时的线程池换来的是每次心率到达重新创建线程的开销。 |

---

## 5. 需要实测确认的

按性价比排序，每条给最省事的测法：

1. **实际 BLE 心率包速率**（决定 P0-1 / P0-2 的收益倍数）。代码只给出间接证据：`DUPLICATE_WINDOW_MS = 900L`(:102) 暗示 ~1 Hz，`HEART_RATE_WATCHDOG_MS`=60s 保活。
   → 装 debug 包，`adb logcat -s HeartwithMiHealth`，`ACCEPTED_LOG_INTERVAL_MS` debug 下是 5s(:103)，数 5 分钟内 `heart_rate bpm=` 行数并对照 `diagRaw` 的 5s 限流；或临时把 `lastRawDiagElapsedMs` 限流改成 0 跑 60 秒。

2. **`shouldUpdateStatus` 的真实放行率**（P0-1 收益的分母）。±2bpm 死区在静息 / 走路 / 运动三种状态下差别很大，我给的 400–1500/h 是估算。
   → 在 `:10842` 的 `if (updateNotification)` 里加一个每分钟打印一次的计数器（debug only），静置 30 分钟 + 走动 10 分钟各测一次。

3. **每包反射的实际分配量**（P0-2 收益的绝对值）。我给的 4–9 KB/包依赖目标类的方法数，是从 jadx dump 推的量级，不是实测。
   → `adb shell dumpsys meminfo com.mi.health:device` 前后对比意义不大；更直接的是 Android Studio Profiler 的 Java allocation tracking 挂到 `:device`，记录 30 秒，看 `java.lang.reflect.Method` / `Method[]` 的分配计数。或者简单粗暴：临时在 `findCompatibleMethod` 入口加静态计数器，60 秒打印一次。

4. **主进程是否真的在稳态下解包**。`hookPassiveSportHeartRateSinks`(:1128) 除了运动会话相关的 `LaunchSportModel$*`，还装了 `hookEcoRawHandler` / `hookEcoRemoteDataHandler` / `hookSportWearDataSinks`——这几个的目标（`EcoDataHandlerWrapper#handleDataInternal` 等）**不是**只在运动时用的类。如果这些类在主进程里也接 BLE 数据，那 P0-2 的收益要乘 2，且"主进程不装解析 hook"这条设计文档声明需要修正。
   → debug 包里给 `onHeartRate` 的 `importantLine` 加上 `processName`（已经有 `processName` 字段），看非运动状态下主进程是否出现心率行。

5. **`writeLocal` 到底有没有读者**。`HeartwithStatus.writeLocal` 从 `:device` 写的是 `com.mi.health` 的 prefs；模块 App 的 `SettingsProvider.java:24` 读的是模块自己包的 prefs，**永远看不到**；宿主内的 `HeartwithSettingsPanel.java:602` 每秒 `readLocal` 一次，但它跑在主进程，SharedPreferences 无 `MODE_MULTI_PROCESS` 时跨进程缓存不一致。如果实测确认面板显示的是主进程自己写的值（运动时）或干脆是陈旧值，那 `:device` 里的 `writeLocal` 可以**直接删掉**，而不只是降频——那会是 P0-1 之外额外的 360+ 次/h prefs 写。
   → 开着宿主内面板，`:device` 有心率时看面板 BPM 是否跟随；同时 `adb shell cat /data/data/com.mi.health/shared_prefs/<PREFS>.xml` 确认文件确实在变。

6. **实际唤醒次数**（验证 alarm 都不叫醒设备的结论）。
   → `adb shell dumpsys alarm | grep -A5 com.mi.health`，看 `ELAPSED` vs `ELAPSED_WAKEUP` 的条目和 `alarms:` 计数；再 `adb shell dumpsys batterystats --charged com.mi.health` 看 wakeup 归属。

7. **热重载后的保留堆**（验证第 4 节里"几百 KB、一次性"的判断）。
   → 更新一次模块 APK，`adb shell am dumpheap com.mi.health:device`，在 MAT 里查 `MiHealthHookModule` 实例数是否 >1 及其 dominator tree。
