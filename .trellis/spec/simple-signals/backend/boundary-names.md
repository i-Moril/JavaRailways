# 边界名称

## 1. 状态与范围

CURRENT，SimpleSignals 负责。玩家可给保护区域正式/修复边界、待完成切口和固定闭塞端点命名，允许重名；真实共用的物理边界使用相同名称。本地实现及离线验证已完成，不把本规范当成已完成游戏内验收。

名称仅供作者辨识，不是边界、进路或设备的身份，也不参与限速、信号放行、选路或路径匹配。

## 2. 一次命名操作

玩家持区间工具 Shift+右键打开设置，选择“命名边界”，然后点击已有边界。后台从捕获的世界定义和拓扑解析共享边界；实体线程显示当前名称及文本输入。保存后后台准备名称 patch，经现有 `applyDefinitionPatch` 提交，原子保存并发布；现场标签和进路菜单从新快照读取名称。完成或取消后结束一次命名。

未进入命名时，创建/删除/F切模式手势不变；命名中点击轨道内部不得创建或删除边界。

## 3. 名称保存与物理归组

名称在同一个世界 sidecar 的 `boundaryNames` 字段保存，与几何对象分开。每项记录实际作者放置的 `railId`、`arcLengthInAbMeters` 和 `name`；世界文档提供 worldId。内部 `BoundaryName(position: RailCutPosition, name: String)` 不改变保护边界或固定闭塞的几何 equality。集合不可变，原始位置唯一，名称不要求唯一。

按同轨道切口或真实普通端点连接归组，复用 `sameBlockBoundary` 的普通连接/二端 LoopAnchor 规则。轨道交叉、空间重合、道岔分支和当前选岔方向不能成为共享名称依据。点击真实普通连接的另一端，若目标位置属于已存在边界的同一物理组，可选择该组真实作者锚点；不跨轨道按空间接近吸附。点击吸附距离与物理判同容差是两回事，不能把吸附半径当作名字合并范围。

名称记录保存在每个存活的作者原始锚点上，不只保存在两条轨道之间按 UUID 选择的派生代表点。这样断开连接后，两侧仍保留原来共用的名称。正式切口来自 region.effectiveCuts、pendingProtectionBoundaries，以及普通闭塞首末 traversal 的端点；修复草稿存在时，它是当前已放置切口的权威，旧正式边界不额外充当存活引用。

新别名继承当前共享组的唯一非空名称；多个不同名称不选取任意胜者。拓扑合并出现冲突时，全组显示本地化“名称冲突”，保留原记录，玩家显式改名或清名解决。拓扑重验只派生显示，不能悄悄改盘。

## 4. 输入与事务接口

`normalizeBoundaryName(input: String): String` 去除两端空白，最多 64 Unicode 码点；全空白得到空串，代表清除。拒绝 ISO 控制字符和 Unicode 行/段分隔符。客户端输入框长度不能替代后台 Unicode 校验；显示使用 `Component.text`，不解释 MiniMessage、颜色或命令。

世界定义 `boundaryNames` 默认空集合；patch 中 null 表示不修改、空集合表示显式清空。writer 只输出当前字段形状。名称 patch、普通区间 patch 和 definitions draft 导入使用同一个 authoring transaction，不能从文本回调直接写盘。

`SectionWandBackend.naming(capture, point: RailCutPosition): SectionBoundaryNaming?` 返回带原始 capture 的选择、初始文本和位置说明；`rename(selection, name: String, active: () -> Boolean): SectionWandOutcome` 在 worker 上重新核对 capture 并提交。`WandDialogs.boundaryName(player, description, initial, submitted: (String?) -> Unit)` 用 null 表示取消；空串是明确清名。

## 5. 生命周期与原子性

仅 world definitionRevision 随名称改变；区域、闭塞、进路表和设备 revision/ID、边界方向、路径及表示器映射都保持原值。所有重建世界文档、复制、draft import/export 和无关 patch 必须传递名称。

区间正式化和修复在相同位置保留名称；共享边界删除一侧保留另一侧引用，删除最后引用后清理孤儿名称。无效曲线或暂不可用拓扑不等于作者删除，不得静默丢名。

名称变化的旧、新锚点轨道加入编辑影响，满足现有非空影响门槛；不伪造区域变化，也不传播到无关几何。仍复用轨道编辑许可，不引入运行中强制绕过。保存沿用 staged save 与 source/publication 原子提交。

交互回调核对玩家、世界、权限、工具、session/token、精确定义/拓扑快照，并保持单次提交。切工具、换模式、离线、跨世界或快照失效取消旧意图。

## 6. 校验与失败

- 名称超长/含控制字符：拒绝输入，不提交；重复名称合法。
- 点击无边界：给提示，不发生创建/删除；可继续选择或取消。
- 候选边界距离并列且不属同组：拒绝猜选。
- 过期定义、拓扑或会话：提示重选，不提交到后来新建的同位置边界。
- 许可拒绝或保存失败：保留旧名称、旧磁盘和旧 publication。
- 名称冲突：统一冲突标签，允许显式解决，不因此拒绝行车 publication。

## 7. 性能与退化

分组和名称查找在捕获快照的 authoring/overlay 准备中完成；按轨道组织候选，以排序代表点与二分匹配控制同轨道查询成本，不能每个标签重新遍历世界或每 tick 扫描全部名称。Signal Wand capture 不在实体线程建全世界名称索引，首次 worker 名称查询才按捕获快照准备并缓存。RoutePreviewSelection 在一次选择中按精确 definitions/topology 复用名称索引和曲线缓存，定义或拓扑更换后重建；删除进路的页面把 boundaryId→边界查找表准备一次，不能对每条进路的入口和出口分别重建世界名称分组或全世界边界表。边界叠加的一次 prepare 在名称与几何之间共用每轨道曲线缓存，额外名称标签不能增加相同轨道的宿主曲线读取次数。结构连接不依赖当前道岔选中状态。失效几何保留作者记录并使用已有不可用提示；缺少曲线时不能伪造位置。

## 8. 现场与进路列表

例如“一站台西端 → 西侧咽喉”是进路标签，底层回调仍持精确 routeId。重复名称或未命名时附位置，未命名使用本地化 fallback；相同两端有多条路径时保留独立行和序号。不能把列表序号或名称当作进路显示数字。

边界叠加、保护入口/表示器绑定摘要、Route Preview 两端选择和表示器映射共用名称解析。`RouteIndicatorWandOptions.routeLabels: Map<String, WandText>` 按 routeId 携带两端标签；`SignalWandBackend.boundaryLabel(capture, boundaryId)` 与 `RoutePreviewBackend.boundaryLabel(selection, boundary)` 分别从自己的捕获快照解析。PDC 存 ID，不存可能过期的显示名称。定位辅助仍不能替代完整路径辨识，本功能不增加新路径高亮系统。

## 9. 必需测试

- 缺字段固定旧 JSON 读为空，当前 Unicode/重名 roundtrip，strict 无效字段及 draft/copy 保留。
- 同轨道/AA/AB/BB 普通连接共享，LoopAnchor，共用不同类型边界；交叉/断开/道岔不误合并，连接拆分保留，异名合并冲突可解决。
- 待完成→闭塞/区域、草稿修复保名；删除一侧保名、最后引用删名；后续重建不继承孤儿名。
- 纯改名保持全部几何、ID、区域/表/设备 revision 与映射；影响轨道仅包含实际变动锚点。
- 许可拒绝、保存失败、定义/拓扑竞态、重复回调无错误提交。
- 命名设置、命中/误点、取消、清名、空白与 64 码点边界，中文/英文；旧手势回归。
- 现场及进路标签用同名源，重名回调仍选中正确 routeId。真实客户端输入和模型/文字画面单独验收。

## 10. 版本与应避免的实现

在当前 SimpleSignals sidecar schemaVersion=1 增加默认空集合，不升版、不新增 runtime 开发兼容分支，也不需要一次性变换缺字段数据。依据[版本政策](../../javarailways/shared/versioning.md)。

不能把 displayName 加入几何 equality，造成改名后入口失效；应把展示元数据与几何分开。不能仅保存排序后的端点代表，否则断开连接后可能丢名；应记录作者原始锚点。不能按 UUID 排序静默覆盖合并后的不同名字；应展示冲突并等待显式改名。

本批不修改表示器主信号放行条件、不部署；名称编辑仍受当前轨道许可约束。
