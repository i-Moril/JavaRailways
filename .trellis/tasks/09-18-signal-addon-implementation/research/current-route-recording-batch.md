# 当前通路录制批次

## 已批准行为与最小缺口

当前工具只能预览保护区域实际通路，不能保存固定进路。用户批准继续录制环节。
本批在 `/simplesignals wand route` 已有选择上接入 `/simplesignals route record`，
保存同一完整路径，不重新选路、扳动道岔或申请资源。
首次录制建立区域固定进路表，随后追加；自动 ID，相同完整路径无变化；默认进路保持原样。
具体流程、冻结时点、失败保留、成本和测试归[录制契约](../../../spec/simple-signals/backend/current-route-recording.md)。

## Owner 和预计文件

- SimpleSignals authoring：小型录制适配，消费已有只读预览结果并生成已有表 upsert patch。
- RoutePreviewWandHandler/插件命令接线：显式 record 命令、复用原有玩家会话及取消规则。
- 相应纯/真实 manager 与玩家调度测试；仅确有需要时修改现有 manager 测试 fixture，不复制新的事务。
- Main 负责 spec、任务及 README、提交与 journal；实现/check agent 不提交，不修改这些文档。

不修改 Core/Basic/ATO/Bukkit 宿主 API、sidecar形状、旧接口兼容、进路删除/替换、默认进路UI、信号机工具、测距或完整可视化。
若原事务无法表达追加或实际编辑保护，先报告缺口，不扩展通用框架。

## 验证边界

生产消费真实预览片段并走真实 manager/store/edit permit；验证保存及重新安装后表内规划。
测试使真实 Basic 编辑保护拒绝相关修改，旧定义/source/file保持不变；无资源 claim/turnout switch 副作用。
先聚焦，再独立复核和完整 SimpleSignals check；同一时刻一个 Gradle runner。
没有 live Folia 时明确限制，不做规模性能声明。

## 完成证据

2026-10-05：新增15项测试（manager9、工具6），聚焦107项/4套通过；最终SimpleSignals40套/407项全部实际执行通过，零失败/错误/跳过。
JAR隔离实际检查；最终编译/Java consumer复用已完成编译缓存。完整日志 `/tmp/javarailways-route-recording-check.log`。
真实manager/store/preview backend/planner证明表内选路与重载、重复无变化、追加/default保持和冻结语义；真实Basic registry证明static/live blocker。
实现只新增一个录制适配并接原handler/插件/命令描述；旧预览测试显式提供保存函数，不新增可选产品模式。
无跨宿主层、API或存档形状变化；没有真实Folia/客户端或负载测试。
