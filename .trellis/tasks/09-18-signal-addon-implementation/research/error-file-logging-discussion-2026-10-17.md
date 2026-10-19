# 模块错误落盘：讨论稿，不实施

后续状态：用户已于2026-10-17明确要求实施。下面保留的是当时讨论记录，不是当前代码未接线的声明；当前机制归[错误文件owner规范](../../../spec/bukkit-plugin/backend/error-logging.md)，验证、提交和部署状态归任务implement.md。原业务日志不归并，使用人类可读文本；无schema升版或日志框架。

用户本轮只要求探讨日志，不修改日志运行时代码或配置。2026-10-17后续已同意按独立插件分目录、内部module字段区分的方向；日志实现仍未获本轮实施范围，其余细节继续讨论。

## 已核实的现状

- BukkitPlugin的`diagnostics/PluginDiagnosticLog.kt`已有`timetable.log`、`commands.log`、`ato.log`，位于插件`logs/`下，5MiB轮转且只保留一份`.1`；目前锁内同步创建目录/追加/轮转。只覆盖若干命令、时刻表和ATO Stall调用点，不是全模块错误出口。
- 插件logger继续进入Minecraft服务端日志；ATO Stall另有专用详细诊断文件。不能把“没有统一模块错误文件”描述为完全没有日志。
- SimpleSignals求值异常被转换为Unavailable及reason，不会自动经过plugin logger；所以真实异常可能只出现在设备诊断页。正常未ready/停车/尚无baseline也可能属于不可用或等待状态，不能将所有红灯/Unavailable都当作异常写盘。

## 推荐方向

1. 按独立插件各自保存`logs/errors.log`，同一插件内用module字段区分Core、Basic、SimpleATO、Bukkit适配层；SimpleSignals属于独立插件，写自己的文件。不默认每个Gradle模块单独一份日志，避免跨模块同一故障散落多处。
2. 每条包含UTC时间、级别、module、稳定事件类别、消息与原始异常堆栈；有实际上下文才附world/train/device身份及epoch/topology/definition来源，不能为了日志额外扫描世界。技术日志保留身份，普通玩家UI继续隐藏自动UUID。
3. Core/Basic只通过平台无关回调上报，不导入Bukkit或文件writer。平台适配器拥有目录、串行后台写入、轮转与关闭；不能原样把当前同步PluginDiagnosticLog接到物理/信号热路径。是否需要新增公共服务接口留待实现范围确定，不先建日志框架。
4. 故障发生处保留原Throwable：例如JS抛错/输出非法/缺注册观测在转成Unavailable以前上报；正常无进路、等待baseline和规则主动输出0不是代码异常。故障状态未变化时不逐tick重复同一堆栈，复用已有状态变更判断；恢复可记一条简短记录，不需复杂全局指纹/定时扫描器。
5. 复用现有控制台logger和详细Stall报告，文件写失败只向控制台报告，不递归尝试写同一日志；不改变原故障的安全收紧/停止行为。日志要轮转，默认大小/保留份数及是否同时保留原三种业务日志待确认；不记录凭据、会话token或整份敏感配置。

## 下一次真正实施前需要确定

- 已确认：每个独立插件一个错误文件＋module字段；SimpleATO/SimpleATOBukkit当前随宿主使用JavaRailways目录，SimpleSignals使用自己的目录。
- 保留现有业务审计日志、详细Stall报告，只新增统一错误文件，还是随后归并既有文件职责。
- 人类可读文本（推荐先用）或JSON Lines；是否需要机器检索决定格式，暂不新增schema编号、依赖或兼容层。

本轮仅修复观测注册并提供复制诊断，以上建议没有接入或部署。
