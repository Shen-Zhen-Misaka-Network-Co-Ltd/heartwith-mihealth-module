# 小米运动健康 3.59.0 兼容性检查

检查日期：2026-09-06。针对用户提供的 APK，SHA-256：
`3f821b273bbfd02c2f71ddb78a07f7b51cf109d045b58499cbd7d297dd628250`。

## 检查范围与证据边界

- 对照 3.56.0 与 3.59.0 的反编译结果检查心率入口、MiWear type 112 注册/清理、睡眠解析/报告与同步诊断。
- JADX 有 212 处方法反编译错误，不能据此宣称已验证 APK 的所有分支。下列签名和注册行为可直接从已成功反编译的代码核对。
- 静态检查和单元测试不能代替真机 BLE、NPatch、API 102 热重载和整夜状态转换验证。

## 点位变化

| 路径 | 3.59.0 结果 | 处理 |
| --- | --- | --- |
| `DeviceDataHandlerAdapter.handlePacketInternal(String,int,byte[])` | 保留 | 沿用 raw hook |
| `DataHandlerWrapper.handlePacket(String,int,Packet)` | 包类改为 `oyt` | 从参数签名发现包类，再按 `static (byte[]) -> Packet` 查找解析器，不追加版本判断 |
| `EcoDataHandlerWrapper.handleEcoPacket` 等 | 包类改为 `qyt` | 同上 |
| `LaunchSportModel` 心率读取 | wear 为 `u().p().f`，eco 为 `r().o().f` | 当前缓存成员访问器仍匹配 |
| `AllDaySleepParser.schemaParse(FitnessDataId,byte[])` | 保留 | 沿用被动解析入口 |
| `SleepBiz.splitDailyReport(String,String,long,Map)` | 保留 | 沿用小米报告作为完整睡眠的依据 |
| `FitnessDataRecorderImpl.recordSleepSegment` | 三组重载保留 | 保留 debug 诊断 |
| `SleepSegmentUtils.computeSleepSegmentReport` | 参数签名保留 | 仅验证签名；协程方法体未完整反编译 |
| `MiWearCoreClient.dataHandlers` | 3.56 按 did，3.59 按 did+type | 按 adapter 的 `getDid/getType` 匹配，不拼内部 Map key |
| `FitnessDeviceSyncManager.syncWithDevice` | callback 混淆类变为 `qra` | 从稳定方法参数取得 callback 类，不依赖原 `hfa` 类名 |

旧的 `sport_manager/sport_eco_manager` 包和部分 `twu/buu/me` 直连回退点不再与 3.59 的类对应。
这些是历史兼容路径，不应改名后强行匹配新类；新版本继续使用保留的官方 `startDeviceHr`、稳定 packet handler 和 Huami callback 路径。
并不保证未来任意重构都无需适配：成员语义、协议或接口发生变化时仍需验证，不能只按数值像心率就接受。

## 本次修正

1. 只有找到同设备、同为 type 112 的官方 adapter 才通过 client API 添加自己的 handler，并检查它确实包含本 handler。没有匹配 adapter 时继续使用隔离 core-service adapter。
2. 不在旧版 did-only Map 中抢先创建 type 112 项，不修改、清空或替换官方其他类型 handler。
3. 注册与移除保持同一所有权；共享 adapter 在服务已断开时仍移除本地 proxy，避免热重载后旧实例残留。代理补齐 identity `equals/hashCode`。
4. MiWear 包先检查设备 ID、启用状态和当前 proxy 身份，忽略切换设备/卸载后旧回调。订阅和主动读取的频率不增加。
5. 心率解析器成功/失败均复用缓存；删除每个包重复尝试旧混淆类名的逻辑。实际包类型被捕获时仍可失效并重建解析器缓存。
6. debug 同步诊断从方法签名获取混淆 callback 类型，恢复 3.59 的同步诊断覆盖。

## 睡眠流程

- 设备属性事件/主动读取返回后，入睡稳定约 2 分钟上报 `asleep`，不为此周期性强制 direct sync。
- 清醒事件稳定约 1 分钟先上报临时 `awake`，避免网页等待最终报告数小时；报告未因此标记最终完成。
- 清醒稳定约 5 分钟后进入最终报告确认。raw/本地报告用于补全时间和分期，最终报告可以修正临时时间。
- 短暂清醒后重新入睡会取消旧确认，并恢复 `asleep`；睡眠分段仍以小米报告为准。
- 状态属性订阅成功后取消常规睡眠 poll；不可用时保留原低频兜底。不添加 wakeup alarm 或 wakelock。

## 后续全文审查修正

1. 临时 `awake` 发出后、5 分钟最终确认开始前，也阻止旧进行中 raw 把状态改回 `asleep`。设备重新报告 `asleep` 后仍允许恢复，不用设备的滚动 `deviceWake` 推断已经醒来。
2. 有效订阅明确报告正在睡眠时，同时阻止冲突的已完成 raw 和最终报告覆盖当前状态。已经结算过的旧 raw 只丢弃，不再清空新一轮 candidate 或把当前跟踪状态改成清醒。
3. 从已结算清醒进入新睡眠时清理旧 raw 窗口、data id 和临时快照，但保留最终报告去重 key。短暂清醒后恢复同一睡眠时不执行这项清理。
4. Repository 查询前和结果发布前检查生命周期、当前设备和属性状态代次。切换设备/状态后的迟到结果不参与上传；执行器关闭时恢复 pending 标记。
5. 热重载保留临时清醒标记和 tracking day，但不把旧实例中已取消的 Handler 任务误认为仍在运行。拿到设备后重建有限次数的最终报告复查；设备尚未就绪时不抢先锁定 scheduled key。
6. 订阅失败依次退避约 1、5、15、60 分钟，后续保持 60 分钟。退避只限制既有重试入口，不增加定时器、唤醒 alarm 或蓝牙扫描；成功、设备变化和实际重连可以解除退避，debug 手动请求可提前尝试。原低频数据获取兜底保留。
7. NetworkingService 拒绝绑定或订阅超时时释放本次绑定状态。回调校验 connection 身份，旧连接迟到的回调不能清掉新连接的状态或触发旧设备请求。
8. 观察到不同的实际 packet class 时，失效旧解析器缓存；同一个类型继续复用成功或失败结果，不反复寻找解析方法。
9. 上传入口日志改为 `submitted`，明确它表示交给 SDK 排队，不是服务端已经确认成功。传输成功/失败仍以 SDK 日志为准。

这些修正不改变 SDK 调用 API、NPatch 的 Core 选择或官方 handler 的所有权，不增加睡眠中的周期性强制 direct sync。

## 测试与后续真机观察

### 2026-09-07：报告冲突时核实设备属性

- 新的有效清醒报告被当前 `asleep` 属性拦截时，仅利用已经订阅成功的设备、Core 和 SIID/PIID，异步发出一次 `get_properties`。不启动服务发现、重新订阅或 direct sync。
- 此核实入口最多每 5 分钟执行一次，没有周期性定时任务。重复回调、传输失败都不能绕过冷却；冷却结束后需要再次收到冲突报告才会重试。最近一分钟的设备推送优先，不立即发出会被推送优先规则拒绝的读取。
- 已确认的同一报告、早于当前睡眠开始的历史报告不触发核实。执行前再次检查设备、Core、状态代次及模块启用状态，切换设备或热重载后取消旧任务。
- 返回 `asleep` 或读取失败时，继续保留睡眠状态；返回 `awake` 时进入原有 1 分钟临时清醒、5 分钟最终报告确认流程。报告本身不直接覆盖属性状态。
- debug 增加 `report-conflict-read` 事件，随后可串联 `own-read-request`、`miwear-sleep-property kind=read-response` 与清醒确认/上传日志。
- 增加 7 项回归测试，覆盖冲突限频、帧中的正确属性、读取响应接入状态机、失败不误报、历史报告、缺少通道和过期任务取消。

已通过 `:heartwith-mihealth-lsp:testDebugUnitTest`，共 25 项：6 项注册表测试和 19 项状态/生命周期回归测试。
本次已构建 debug；此前 18 项测试对应代码也已构建经过 R8 优化的 release APK，本次冲突核实改动尚未构建 release。注册表测试覆盖 did-only、did+type、多个设备/类型、空表、未知 adapter 以及不创建额外表项；状态测试覆盖旧 raw 不污染新睡眠、临时清醒优先级、热重载状态、解析器缓存、订阅退避、绑定失败恢复、异步结果归属和报告冲突核实。
CI 和 tag 发版构建均先执行上述单元测试，再构建 APK；版本号、签名、tag 和发布日志规则未改动。

检查 release DEX 和 R8 usage：测试类、Robolectric/JUnit、详细睡眠 hook 诊断及相应格式化方法未进入正式产物；低频重要事件日志保留。

额外执行 `lintRelease` 未通过，报告 38 个错误、69 个警告。错误集中在项目 `minSdk=23` 与 modern API 102 使用 `java.lang.reflect.Executable` (API 26) 的下限声明，以及 Android 13 以下广播注册分支的导出标记检查。源码已有 Android 13 分支，不能仅凭该 lint 报告判断新系统一定会注册失败；本轮未通过抬高安装下限、添加 baseline 或屏蔽检查来掩盖这些项。构建成功不等于 lint 全部通过。

真机应检查：

- `miwear-handler-registered mode=client-api/core-service`，随后属性响应及订阅确认。
- `heart-rate packet parser ... class=oyt/qyt` 和心率采集/上传连续性。
- `miwear-property-state`、`provisional-wake`、`upload-submit`、最终报告确认与上传。
- 正常手动同步是否成功，以及断联/重连和热重载后是否只有当前设备 handler 生效。

debug 详细日志继续按天追加到应用的 `heartwith-debug/heartwith-debug-YYYY-MM-DD.log`，保留 7 天，不按小文件容量覆盖当天日志。
本次不改发布版本号、不提交、不部署；安装和运行结果需以实际 ADB 反馈记录。

2026-09-07 真机补充：debug 已安装到 82411d42。08:48:52 在 device PID 29810 内完成 API 102 热重载，08:48:57 恢复 wear-raw 心率及 2/1016 睡眠属性订阅，08:49:07 起连续获得上传成功回执。安装后第一次 debug 状态页写入出现 Unknown URI，08:50:50 再次主动读取约 48ms 返回 awake，状态页已成功更新。当前为清醒状态，真实睡眠中的报告冲突尚待下一轮睡眠验证，不将单元测试等同于整夜实测。
