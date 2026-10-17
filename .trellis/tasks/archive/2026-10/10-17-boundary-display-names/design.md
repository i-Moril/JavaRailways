# 边界命名设计

## 边界与所有权
SimpleSignals 持有名称，Core/Basic 的轨道、区间资源与路径定义不增加命名职责。单任务按存储→事务→现场编辑→展示分批完成；各层共享同一物理边界名称合同，拆子任务会增加过渡结构，因此不再建父子任务。

名称是独立于边界几何的元数据。当前世界定义增加 `boundaryNames` 空默认集合，条目为已作者放置的位置 `RailCutPosition` 与普通文本名称。使用确定顺序和不可变快照；一个原始锚点最多一个条目，名称允许重复。普通闭塞 start/end 从有序路径推导，保护区域正式边界、effective draft 和 pending 切口均提供候选锚点。

不增加一个覆盖所有旧对象的新边界 ID 系统，不把名字塞进 ProtectionBoundaryCut/Definition 的 data class equality；避免仅改名改变 placedEntrances、regionRevision、route table revision 与几何可用性。正式边界及进路的现有 ID 始终是绑定身份。

## 物理边界名称解析
- 同轨道同弧长按现有 Curve.TOLERANCE 合同判同；不同轨道仅复用 sameBlockBoundary/ordinaryContinuation/二端 LoopAnchor 判同。空间相近、平交、道岔可行驶分支不能因此合并。
- 抽取共享的物理边界判同/分组辅助，现场叠加和命名使用同一规则，不复制一套近邻规则。
- 名称条目保存在真实作者锚点上，不能只保存两个已连端点按 UUID 最小值生成的临时 canonical 锚点。后者可能属于非作者端点，断开连接后不应导致名称丢失。
- 一次重命名在捕获的 publication+topology 上找到目标组，统一设置该组所有存活作者锚点；清空则删除该组所有名称条目。组内没有名字则显示未命名，只有一个不同名字则共同显示。
- 新增区间/草稿修复把共享组已有唯一名字传给新增作者锚点；切口正式化保持位置和名称。原始位置保留多份名字使日后真实连接分开后两侧仍保留名字。
- 拓扑编辑把原本不同名字的两组连接时，不按 UUID 或列表顺序挑选名字、不静默覆盖；统一提示名称冲突，由玩家对合并后的边界明确改名/清名来解决。该冲突仅影响标签，不影响信号或行车。
- 几何删除/替换事务中只清理能确定已无存活作者引用的位置；无效轨道或未完成修复不能被当作明确删除。删除最后一个引用后不能让后来重建的边界继承孤儿名字。

## 存档与事务
独立边界仍为 SimpleSignals world sidecar，schemaVersion 保持 1。新增字段默认 emptyList，旧输入不含字段时自然按空名称读入；严格 reader 和 current writer 保持原策略。不写 runtime 版本分支、不做不必要的离线迁移。保存/restore、manager world copy、patch candidate 重建、definition drafts/import/export 和 unrelated patch 都必须传递名称。

`SimpleSignalsDefinitionPatch` 增加可区分“不修改”和“替换为空”的名称字段。命名 worker 准备新元数据，调用 applyDefinitionPatch 的现有定义 revision、runtime/topology、active guard。仅 world definitionRevision 增加，region/block/table/device revision、ID、path 不变。

prepareDefinitionPatch 目前先 applyPatch 后 captureTopology，实施时需先捕获并验证本次 topology 或在 capture 后执行候选 metadata normalization；后台 prepareDefinitions 拓扑重验只派生名字视图，不悄悄写盘或修改源元数据。SimpleSignalsDefinitionDrafts 的 patch 生成和 summary 也必须识别 names-only 编辑。

名称旧/新锚点轨道加入 SimpleSignalsDefinitionEditImpact；纯名称 patch 也能满足非空 rail impact 并沿用 acquireEditPermit、staged save、source/publication 原子提交。不要以伪造几何变化满足许可，不加入无关全世界依赖，也不新建 metadata 专用提交/回滚旁路。仍可能被既有轨道编辑许可阻止；本批不绕过运营锁。

分组、标签和归并在 worker/candidate prepare 低频路径执行，不新增 physics tick 扫描。按轨道分组避免每个边界遍历全世界；拓扑 root 更新使相关展示缓存失效。

## 命名交互
区间工具设置增加“命名边界”，进入玩家一次性等待选点状态。消费下一次普通边界点击（左/右点击均只选点，不创建/删除），未命中边界给提示并保留待选状态；再次打开设置/切工具/切模式/清理/退出则取消。工具原有默认点击不改变。

命中共享边界即打开文本页，展示当前位置、当前名称或冲突来源名字，提供保存与取消。完成或取消清理 pending/session；输入验证错误保留对话以便修正，不产生写入。文本最多 64 Unicode 码点，前后空白去除，空白代表清名；拒绝换行与控制字符，用 Component.text 原样呈现。

回调核对玩家、世界、权限、工具状态、session/token、exact publication/topology 和单次提交标记；重复点击不能多次保存；失效时提示重新选点，不将旧操作施加到后来出现的同位置边界。

## 展示
共享 formatter 从捕获定义和拓扑解析名称与位置。现场标签、Signal Wand 保护入口/表示器绑定摘要和选择、Route Preview 入口/出口及删除选择、RouteIndicatorWandOptions 映射列表使用同一名字。持久化 PDC 只保留 ID，不缓存过期名字；必要时状态展示从当前快照查名。

进路行保留 routeId 为 action identity，标签“入口 → 出口：已选外观”；重复名称附现场坐标，未命名显示本地化 fallback+坐标；相同两端多路径保留独立行及序号。名称不能推导显示数字。名称辨识不等于完整路径预览，本批不新增高亮系统。

## 风险与验证
- 共享边界 identity 与布局 canonical 不完全等价：测试连接断开、普通连接和交叉、Turnout/LoopAnchor。
- 全文档重建漏字段可能丢名字：覆盖复制、其他 patch、draft 导入、保存恢复。
- 一次纯名称提交虽走完整 publication，必须验证几何、route/device revisions 和映射逐项不变。
- 真实 Paper 输入与客户端 TextDisplay 仍需后续游戏验收；离线测试不替代。
