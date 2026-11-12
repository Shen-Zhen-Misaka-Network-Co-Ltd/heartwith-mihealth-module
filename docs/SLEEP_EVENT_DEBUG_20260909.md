# 官方睡眠事件归属修复与夜间观察

本轮仅构建和安装 debug，不验证 NPatch、不发版。

## 修复

- OpenAppModule hook 仅安装在 device 进程。回调先检查生命周期、模块开关和进程。
- 从来源模块 getAddress() 读取地址，与当前设备 getDeviceInfo().getMac() 忽略大小写比较；缺少地址/DID、地址不同或与当前 MiWear DID 不符时丢弃，不更新状态。
- 被动捕获一次状态事件不代表已经订阅。它仍接入既有状态确认流程，但不单独停用低频 fallback；只有 type 112 订阅确认仍能停用常规 poll。
- 设备切换、属性重置、Core 清理、关闭模块、热重载清除官方事件标记。清除时使旧状态确认代次失效，防止迟到任务上传。
- 不新增轮询、蓝牙扫描、强制同步或唤醒锁，不改变原有稳定确认时长。

## 日志

继续按天追加 heartwith-debug-YYYY-MM-DD.log，保留 7 天，逐行刷新。路径优先使用运动健康外部 files/heartwith-debug，失败则回退私有 files/heartwith-debug。

- official-sleep-state：接收的 DID、状态码、状态、是否有 type 112 订阅确认。
- official-sleep-rejected：非法数据或设备归属无法确认，不参与上传。
- official-sleep-reset：清理原因与旧 DID。
- 后续沿用 miwear-property-state、miwear-transition、upload-submit、repository 和 SDK 上传结果，区分接收事件、状态确认、提交排队与网络成功。

7 项新增测试覆盖启用/进程/生命周期、地址与 DID 归属、非法包、重复事件、不误停 fallback、设备变化代次失效、Core 和设备断联清理。测试不代表真机已经收到睡眠变化；明日需将事件日志和实际睡眠时间对照。
