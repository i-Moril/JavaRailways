# 路径点只使用 ID（2026-10-31）

## 用户授权与边界

用户明确要求从路径点 schema 删除显示名及所有相关逻辑，允许必要的一次性服务器存档修正。不得添加针对被删字段的 runtime 兼容、别名、默认补值、特殊拒绝代码或专用拒绝测试。沿用未结束的 stop-program-hooks 任务，不新建、不归档；本轮没有新增提交或部署指令。

当前问题是 WayPoint/OperationalDestination 身份变更保留独立 displayName，而网页将该值与 ID 拼接，暴露 etchujima#2 等过期字符串。修正由领域模型、当前持久化 DTO、bridge metadata 和网页消费端共同完成，不能只隐藏文案。

范围：Basic WayPoint/store/snapshot；SimpleATO OperationalDestination/snapshot 及全部调用；Bukkit 创建编辑/标记/模拟描述/metadata；WebUI 类型、候选、搜索和当前 fixture。车站、车辆段、线路、车辆、程序及动作类型自身的显示名称仍有独立语义，不在本次删除范围。路径点候选与筛选只使用实际 ID，不保留所属对象名称拼接的替代显示名。

持久化边界为 Basic/SimpleATO 当前基础设施嵌套快照；项目与用户明确为开发格式，没有新增发布/冻结兼容基线，保持当前版本。生产 decoder 保留既有通用行为；删除字段不增加任何专门处理旧字段的分支。服务器旧数据只通过独立离线工具处理。

## 实施与所有权

1. 领域 worker：Basic、SimpleATO、Core 中实际使用路径点的源码及测试，删字段和构造参数，更新当前 fixture。
2. Bukkit worker：BukkitPlugin、SimpleATOBukkit、SimpleSignals、BukkitPluginDev、TunnelBridgeBuilder 内路径点 consumer/测试，删 metadata 字段和名称残留逻辑。
3. WebUI worker：WebUI 前后端类型、候选、搜索、测试和样例；ID-only 候选仍支持手输、已知 ID 筛选、保序及既有输入/history行为。
4. 主会话：规范/任务记录、离线存档工具与真实存档 dry-run、跨模块整合及集中 Gradle 检查。避免并发 Gradle 和无关 heap dump。

## 验收

- 路径点与运营目标的 runtime/current saved model、构造参数、HTTP/TS 类型均无独立显示名。
- 直接候选始终显示和提交同一实际 ID，无旧名称、所属对象名称拼接或别名查询。
- 真实 current snapshot 保存恢复、目标镜像、创建改名和模拟方向/唯一放置行为保持正确。
- 车站/车库/线路自身名称保留；版本常量不变；无新的兼容/拒绝路径。
- 离线清理仅删除准确路径点对象字段，保留所有其他数据/顺序/版本/revision/epoch；可 dry-run、独立恢复点、当前 codec 验证。
- 完整 frontend test/build 和 affected JVM tests/构建通过；真实浏览器验证筛选/选择实际 ID。

服务器在线旧插件仍需要旧模型时，不直接修改其活动存档以免破坏恢复或被下次保存覆盖；先提供候选、审计和配套切换方案，未获本轮部署指令不自动重启替换插件。
