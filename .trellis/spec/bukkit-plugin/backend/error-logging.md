# 独立插件的模块错误文件

## 范围与负责模块

本地实现（2026-10-17，部署及验证状态见Stage 2任务记录）。BukkitPlugin拥有共用的纯JVM `bukkit.api.diagnostics.PluginErrorLog`；JavaRailways和SimpleSignals分别拥有自己的实例与`logs/errors.log`。它记录已经上报的WARNING/SEVERE，不是捕获所有异常的全局代理，也不替代现有命令/时刻表/ATO业务日志、Stall报告或服务端控制台。

Core使用JDK JUL日志，不依赖Bukkit、文件路径或writer。Basic/SimpleATO的异常若经宿主现有边界报告，沿该出口记录；正常业务拒绝或已经被处理且未上报的异常不会自动变成文件记录。不修改物理、授权或安全收紧语义，不给诊断文本新增schema版本。

## 一条错误如何进入文件

插件加载时创建writer并给自己的plugin logger安装Handler；宿主另外监听Core/Basic/SimpleATO/SimpleATOBukkit的命名logger。调用logger仍按原路径进入控制台，Handler只捕获时间、级别、实际logger/来源、消息和原Throwable，交给单个后台worker格式化堆栈、追加及轮转。

物理/信号callback直接报告的故障使用`reportFailure`：只入同一队列，worker负责原plugin logger控制台输出和文件追加。worker自身再次经过Handler时不重复入队；即使关闭先撤掉Handler，已接受任务仍会明确追加文件。不能使用另一个未追踪的fire-and-forget异步任务先排队到logger，否则writer关闭可能漏掉它。

## 内部表示、顺序与轮转

- Handler在原调用栈读取JUL延迟推断的`sourceClassName`，不在worker中推断，把writer类误当故障来源。
- 条目保存真实时间、级别、module、logger、source、message和原Throwable引用；堆栈保留cause/suppressed，不只保存message。实际world/train/device/epoch等由故障owner提供，不为补字段查询或扫描世界。
- 先按已注册的模块logger身份/前缀分类，再按可确认的项目source包分类；未知使用所属插件的默认标签，不从人类消息或灯色推测模块。
- 每个实例一个串行worker维护文件顺序，publish/close在短锁内决定准入。文件操作和完整堆栈格式化都在worker。
- 默认阈值5MiB，已有文件达到阈值时在下一次追加前改名为`errors.log.1`，替换原`.1`，再写新文件。单条完整报告不截断，因此阈值不是严格最大文件字节数。

## 公开接口与单位

```kotlin
class PluginErrorLog(
    path: Path,
    pluginLogger: Logger,
    defaultModule: String,
    moduleLoggers: Map<Logger, String> = emptyMap(),
    maximumBytes: Long = 5L * 1024L * 1024L
) : AutoCloseable {
    fun reportFailure(message: String, failure: Throwable)
    override fun close()
}
```

`maximumBytes`为正的字节数；时间为UTC Instant。附属插件复用宿主API且compileOnly，不复制writer或创建第二个服务注册协议。日志是人类可读文本，不用于世界定义恢复，也不自动导出配置、凭据、会话token或完整脚本输入。

## 线程、关闭与失败

物理或信号线程不做文件IO、不等待logger任务完成；普通logger原有控制台Handler行为不由本writer接管。故障报告队列只为实际异常/警告工作，不增加正常tick扫描。

先停止生产者，再关闭writer：撤销本实例Handler并拒绝新入队，shutdown后最多等待5秒排空已接受任务。等待只发生在生命周期global/后台线程，不得放在physics或玩家/region owner。SimpleSignals的模型/编辑清理可能在region完成，所以其最终日志及writer关闭使用异步完成阶段，并纳入公开的清理完成结果。宿主加载途中失败也要报告原异常、关闭已创建writer，不依赖未必发生的正常停用。

写盘失败只向console parent logger报告，不再经过本Handler，不重试同一个文件、不改变原来的运行状态；正常控制台消息保留。关闭超时/中断会明确报告未写任务数量，不能声称已保证所有日志落盘；中断保持线程标志。已关闭的writer不接受迟到记录，生命周期owner负责停止旧生产者。

## 具体情形与验证

事件监听器抛错时，Core通过JUL携带原Throwable报告，仍继续原来的监听器隔离流程；物理联结异常及generic tick故障沿原控制分支记录。delta clamp与overload使用WARNING，保留原限频/计数，不借日志修改调度或停用规则。

信号具体故障和正常不可用的区分、去重由[信号规则owner](../../simple-signals/backend/js-signal-rules.md)负责。不能在通用writer把所有Unavailable/红灯当异常，也不建立全局故障指纹缓存。

必需回归：真实文件追加及原cause/suppressed、模块与控制台保留、后台写入、轮转、关闭排空与撤Handler、`reportFailure`恰好一份console与文件、写失败只报告console且不递归。信号使用实际求值与既有快照验证失败/相同失败/恢复/再次失败，不仅测试手写日志字符串。Core事件、physics与generic tick原有行为回归必须保持。

## 成本、迁移与边界

健康路径不生成文件记录；错误入队为每条常数次状态/分类及消息捕获，worker成本随完整消息与堆栈长度增长，轮转另有文件系统成本。单worker串行排队不承诺磁盘阻塞时的吞吐或零内存成本；本轮不新增任意业务规模上限、定时扫描或日志框架。

无配置、世界schema、旧数据reader或迁移。原业务日志职责不变。错误做法是在物理callback同步写`PluginDiagnosticLog`，或先丢掉Throwable再仅记安全限速0；正确做法是原故障owner在转换状态前保留真实异常，经平台的非阻塞出口报告。
