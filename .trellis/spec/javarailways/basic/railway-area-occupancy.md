# Basic 监测区占用与边界穿越契约

## 目的与归属

触发：修改`RailwayOccupancyAreaDefinition`、`RailwayAreaOccupancyTracker`、列车当前占用或信号响应式输入。

Basic只提供信号系统普遍需要的物理派生：某个插件定义的轨道区域在epoch结束时有哪些列车，以及列车在本epoch按什么边界进入/离开。区域是否是固定闭塞、保护区域、站台或自定义联锁对象，由定义它的插件决定；Basic不据此授予进路、设置限速或解释信号制式。

## 输入模型

一个定义包含稳定`areaId`、定义revision、绑定的`topologyRevision`、每根轨道各自的canonical AB闭区间和至少一个有向边界。边界由稳定`boundaryId`与`RailPositionSnapshot`表示；position的facing始终从区域外指向区域内。

跨轨道区域没有全局AB。它是多个rail-local interval的集合，列车遍历仍是有序segment列表：每个segment独立携带rail ID、local AB/BA方向和canonical AB起止弧长。连接处边界放在区域一侧的rail上，因此进入该rail occurrence的起点也能被检测。

每个observation提供三类不同证据：

1. `occupiedIntervals`：epoch末保守车体位置，用于最终occupants；
2. `sweptSpans`：整epoch扫过包络，只用于碰撞/准入类查询，不决定最终occupants；
3. head/tail traversal：本epoch有序运动证据，用于产生边界变化。

tail表示保守安全车尾。未来近似实现可延后其位置和`LEFT`，但不得提前越过真实车尾。

## 派生算法与时序

公开`RailwayAreaOccupancyEngine`由一个world-local串行owner调用；工厂防御性复制定义，并由internal tracker实现。
它只接受同world且physics epoch严格连续的observation。定义中的topologyRevision记录计算来源，
不要求所有区域的旧计算版本都等于当前世界版本；局部有效性来自下述事务影响通知。

`create(definitions)`建立独立冷引擎。`prepareReplacement(definitions): RailwayAreaOccupancyEngine`在编辑worker上完整编译另一份几何和索引，旧引擎继续处理已捕获的epoch，调用方成功提交后才切换到候选。不存在就地修改旧引擎的`replaceDefinitions`接口或兼容转发。
候选对area ID、轨道区间和完整有向边界未变、且仍有效的区域接续原物理记录；
definition revision及无关世界拓扑变化不是几何变化，不丢失记录。已失效区域须经重新验证后建立可用的新记录，
不能仅重装原定义就清除停用状态。
未变区域共享其占用/已消费stamp的状态单元，而不是在prepare时复制可变值。因此prepare之后、旧epoch最后产生的ENTER/LEFT也能成为新引擎的前帧事实。
新引擎首次消费仍发布所有区域的完整baseline，但未变区域同时保留该帧真实crossings；新建或几何变化区域只发布当前occupants，不猜测此前经过。
旧、新引擎的consume必须在同一world physics owner上串行，不能并发或对同一记录重复消费一个epoch。准备只访问不可变几何及状态单元引用，不读取可变occupants，不等待physics，也不保留前任引擎链。接口不公开索引、mutable tracker状态或信号策略。

每个正常epoch：

1. 用`occupiedIntervals`计算所有区域的最终occupants；
2. 对每列车的head traversal查找同rail边界，方向等于inward时形成`ENTER`候选；
3. 对安全车尾traversal查找同rail边界，方向与inward相反时形成`LEFT`候选；
4. 按列车运动进度合并候选；同一列车保留物理顺序；
5. 只在该候选真实改变区域成员状态时发布变化，最后以epoch末occupants校正状态。

因此短区域可在同一epoch发布`ENTER(entry)`、`LEFT(exit)`，不需要`PASSED_THROUGH`。车头到达inward边界即算`ENTER`并开始占用；安全车尾恰好停在outward边界仍未清空，下一epoch从该点向外移动才发布`LEFT`。中间rail端点若遍历继续到下一segment，则算作已经穿越。

列车生成、移除或管理员瞬移可能让最终occupants变化但没有连续运动证据。此时发布新occupant snapshot，`crossings`保持空，不伪造ENTER/LEFT。

## 轨道编辑如何局部停用监测

宿主在Core事务通知中调用`invalidateTopology(change): Set<RailObjectRef>`。
tracker在定义准备时建立轨道到area的反向索引，按`affectedRailIds`查找并去重，只标记命中的状态记录。
返回的area身份供信号运行时唤醒所属来源；未命中区域不改变occupants、物理前帧或连续运动记录。
已经按该次编辑后拓扑计算的定义，不会因重放这条已包含的变更再次失效。

下一次更新通过`RailwayAreaOccupancyUpdate.available=false`明确报告几何不可用，保留最后已知occupants，
不伪造LEFT，也不能把这份occupants当作当前完整观测。消费者须按不可用处理，不能理解为空闲。
恢复由插件重验并准备新定义，Basic不猜测区间新边界、不释放claim、不重设道岔。
例如旁线C施工而监测区仅覆盖A/B，后续真实LEFT仍能从原有记录产生，不出现冷启动丢事件。

## 索引与复杂度

定义变更时一次编译两个rail-local索引：

- 边界按canonical AB弧长排序；每个traversal segment二分定位首个候选，再只扫描该segment覆盖的边界；
- 区间按起点排序并维护前缀最大终点；每个train placement interval二分跳过不可能重叠的前缀，再只扫描可能相交项。

设本epoch列车placement interval数为`P`、traversal segment数为`S`、区间命中数为`K`、同一列车的边界候选数为`C`，查询成本约为`O((P+S) log I + K + C log C)`，其中`I`是相关rail上的定义数；最后一项用于合并head与安全车尾的真实运动顺序。没有`所有列车 × 所有区域`全扫描，也不每tick执行插件脚本。

replacement的几何相等比较和新索引编译只在worker执行；同area通过map定位旧几何，比较成本按实际interval/boundary数量计，不额外做区域两两比较。普通consume沿用既有区域归约；只新增每区域常数次状态/stamp访问，不增加路线扫描、事件队列或跨world锁。连续替换共享状态单元而非链接旧引擎，内存不随未消费编辑次数累计历史。

## 失败与禁止行为

- world改变或epoch跳号：拒绝该observation，不推进baseline；相关轨道编辑则仅停用命中的area；
- area ID重复、同area boundary ID或物理位置重复、边界不落在本area interval内：定义编译失败；
- 禁止用`sweptSpans`判断最终occupants；
- 禁止按rail ID合并traversal occurrence或从topology重建经过顺序；
- 禁止在Basic内把area自动转换为claim、闭塞状态、保护区域或限速；
- 禁止为补偿异步处理而增加跨epoch安全事件队列。

## 必需测试

- 多轨道区域中每根rail独立使用AB/BA，完整同epoch穿越产生有序ENTER/LEFT；
- 车头到达inward边界即ENTER；车尾恰停outward边界不LEFT，下一次向外移动才LEFT；
- swept envelope覆盖区域而epoch末placement在外时，occupants为空；
- 瞬移、生成、删除改变occupants但不伪造crossing；
- 定义defensive copy，拒绝world变化与epoch缺口；无关事务推进版本不拒绝观测。
- public engine factory可在不导入Basic internal package的情况下建立baseline；batch/update collection不可修改。
- replacement在prepare之后旧epoch仍推进占用时，下一帧从其最新记录继续；首次完整baseline保留真实LEFT及同帧ENTER+LEFT。
- 新建、删后重建、边界/方向或相关拓扑变化区域重验后重新建立观测；无关拓扑和只改definition revision的相同几何保留连续性。
- 受影响area报告available=false并保留最后已知占用，不产生假的LEFT；无关area保持IDLE或产生真实穿越。
- 连续多次prepare/发布不保留引擎链；未变化epoch继续返回空batch。

## 迁移与禁止的替代方案

当前是开发期公开接口替换，删除无调用方的就地`replaceDefinitions`，不提供旧接口兼容；存档格式不变。
Wrong：把所有replacement当作冷启动而丢掉该帧真实LEFT，或在编辑worker复制可变occupants。
Correct：worker准备不可变几何，未变区域接续同一串行物理记录；新/变更区域建立真正的冷baseline。

```bash
./gradlew :Core:test --tests '*CoreTrainPublicationBoundaryTest'
./gradlew :Basic:test --tests '*RailwayAreaOccupancyTrackerTest' --tests '*RailwayObservationTest'
```
