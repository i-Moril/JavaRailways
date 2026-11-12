# 铁路编辑标记的共用视觉规范

## 范围和当前状态

统一轨道、车站、闭塞和保护区域工具的显示含义，不统一业务定义。
已有 RailWand 使用锚点 ItemDisplay、轨道信息 TextDisplay 和粒子；道岔工具和通路预览使用玩家粒子。
这些工具尚未全部迁移到统一显示实现。SimpleSignals的闭塞/保护区域工具及Signal Wand共用TextDisplay分界点、区间中心细线和仅分界处的说明文字，不画区间箭头；RailObjectWand专门表达运行方向和到达方向，继续使用同一字体的箭头图案。实体由公开EditorTextService管理；独立通路预览、多通路警告、其他RailWand/车站标记不在此次几何迁移内。

## 一次编辑如何显示

所属模块判断对象状态，准备点位、方向、区间及提示；Bukkit 适配把准备好的显示发给编辑玩家。
例如一个入口到出口有两条合法通路，SimpleSignals 自动保存最短路，并在这两个边界显示多通路警告。
警告不能表示列车已经获准进入，也不能改变进路表或轨道连接。

## 基本表达

- 点标记说明位置，可用于轨道锚点、运营停车点、闭塞分界点和保护区域边界。
- 方向标记沿轨道切线指示方向；不能用 AB/BA 枚举文字代替玩家看到的实际方向。
- 区间高亮用连续中心细线说明覆盖范围，不放箭头或内部重复文字；独立路径预览仍可显示行进方向。同色不表示属于同一业务对象。

选中、警告和不可用须与业务状态区分。警告用显眼标识并说明原因，不能只染红让玩家误认停车信号。
通路入口/出口颜色与警告可以并存；多通路警告位于有关出入口，不散布到内部道岔。
速度颜色不兼任选中、多通路或未连通。普通建造说明不显示轨道/闭塞自动UUID，包括截短UUID；真实作者名称可以保留，内部稳定身份、存档和管理员诊断不变。说明文字与几何标记是不同用途：说明面向玩家，几何符号必须沿轨道方向摆放，不能仅用面向玩家的箭头字符表达真实朝向。

## Ownership、线程与生命周期

### 已确认的公开能力方向（TARGET，2026-10-15）

用户同意BukkitPlugin向附属插件提供可复用的Wand交互与Rail可视化能力。按实际消费者逐步开放：工具识别/持物生命周期、交互回调，以及轨道点/方向/范围高亮、临时文字和相关玩家可见性。具体接口签名及既有工具迁移范围尚未冻结，不能将本方向描述为已实现的统一API。
已实现的最小输入能力为[公开Wand按F切模式helper](interaction-render-persistence.md#共用wand按f切换模式current)，仅路由当前主手的换手事件，不表示完整Wand框架已完成。
例如SimpleSignals的Bukkit适配层提交边界文字、点位和方向，BukkitPlugin负责实体/玩家owner调度与可观察的关闭清理；保护区域含义、选择规则、候选校验及事务由信号系统决定。公开能力不接管信号状态、资源申请或ATO策略，平台无关的SimpleSignals不直接依赖这些Bukkit API。
实现时优先提取既有工具/显示机制，不公开RailWand内部对象或引入统一铁路业务注册表；先满足当前边界叠加所需的最小文字与清理能力，再根据真实复用补充Wand及其他可视化接口。验证须覆盖玩家隔离、owner线程、离手/卸载/停用清理与关闭后迟到结果，不能以一次创建成功替代生命周期验收。

Core/Basic 不保存符号、颜色或 Bukkit 实体；各业务模块保留自己的定义及校验结果。
共用渲染能力需要实际多个使用者时再抽取，不预建统一铁路对象注册表或另一个状态总线。
图搜索、曲线采样与标记准备在 worker；实体创建修改按 location/entity owner 调度，
玩家显示和消息按玩家 owner 执行，不能让物理 tick 阻塞等待显示。

临时显示仅面向相关编辑玩家。换工具、离线、换世界、世界卸载及插件停用须停止显示并清理订阅/实体。
迟到结果不能恢复关闭的显示。刷新使用已准备值与明确数量预算，不每次遍历整个路网。
业务输入不变时不重算路径；显示周期刷新不等于业务重算。

## 首批临时文字 API

状态：CURRENT，2026-10-15实现并通过独立复核；离线门禁与未验范围归Stage 2任务记录，实际Paper/Folia/客户端另验。

目的仅是让工具显示玩家专用的临时文字/图案并可靠清理。BukkitPlugin提供服务实现，SimpleSignals的边界/区间编辑叠加及宿主RailObjectWand方向显示复用本能力，不要求先实施领域模块拆分；以后信号适配可由SimpleSignalsBukkit承接。具体几何缓存和持物生命周期归[信号工具合同](../../simple-signals/backend/section-authoring-wands.md#持工具的边界与区间编辑叠加current)。不公开Wand内部模式、通用脚本交互系统或完整RailWand控制权，不接管区间、进路或运营方向的业务含义。

已实现的公开类型位于`bukkit.api.editor`，服务由宿主统一注册到Bukkit ServicesManager：

```java
interface EditorTextService {
    EditorTextHandle create(Plugin owner, Player viewer, String objectId);
    CompletionStage<Void> closeOwner(Plugin owner);
}
interface EditorTextHandle extends AutoCloseable {
    void submit(long revision, List<EditorTextLabel> labels);
    void close();
}
record EditorTextLabel(String id, double x, double y, double z,
                       Component text, EditorTextAppearance appearance) {}
record EditorTextAppearance(ModelPose transform, double scaleX, double scaleY, Mode mode) {
    enum Mode { READABLE_TEXT, FIXED_GLYPH }
}
```

`create`在viewer所属线程捕获当前世界实例，分配操作对象而不生成实体；身份为实际owner插件实例、viewer玩家实例、世界实例和非空objectId，拒绝同时活动的重复身份。不接收轨道/闭塞/信号ID，不对label的id赋予业务含义。坐标为该世界中的绝对米制位置；Component为不可变Adventure文字，调用方提交完整列表，服务复制后持有。标签id非空且本次列表内唯一，坐标有限；非法输入在替换待显示状态前拒绝。显示序号非负且在handle内递增，与schema/epoch无关；旧序号和关闭后提交不恢复显示。

正常流程是玩家owner创建handle，调用方worker准备纯标签值，submit交给宿主后台调度，再在标签位置/实体owner上创建或更新非持久化TextDisplay，最后在玩家owner上仅向viewer显示。显示实体默认对其他玩家不可见。保留五参数EditorTextLabel构造器，默认appearance为READABLE：面向玩家、有文字阴影和默认背景。FIXED_GLYPH使用固定朝向、透明背景、无阴影和15/15亮度，局部姿态与缩放不修改label的世界锚点。appearance使用已有不可变ModelPose，平面缩放必须有限且为正；不保存Bukkit实体、可变JOML向量或业务对象。

完整空列表清除本handle的所有文字；后续列表按稳定label id与上次实际显示状态比较，保留未变标签，删除缺项，只更新变化部分。只改文字/外观时在原实体owner更新；从图案切回普通文字时必须完整恢复背景、亮度、朝向和单位变换。位置改变均删除旧实体后在新目标位置创建，不使用可能跨Folia区域的同步传送。相同值不重建或重复修改实体。

内部复用现有`PlatformDispatcher`、`LatestAffinityTaskDispatcher`及RailWand中默认隐藏/仅对指定玩家显示的方式，不公开其私有对象。每handle只保存一份最新待显示列表及一个调度工作，不能堆积历史画面；实体操作不在状态锁内执行。这里要求复用实际机制，不要求为提取通用基类而重构整个模型服务。

调用方负责离手/权限或编辑上下文失效时close；服务兜住玩家退出/换世界、世界卸载及owner停用。关闭立即撤销显示意图，未开始或迟到的创建必须检查同一handle与世界实例并清理，不重新显示。实体调度使用仍启用的JavaRailways平台owner；附属插件停用后仍可执行清理，而不是借用外部插件对象自行排任务。close不阻塞；closeOwner合并该owner全部尚未完成的清理（含此前单独关闭的handle），失败保留可观察的异常结果，不能因移出活动表而误报成功。先完成附属显示清理再停用主插件，沿用现有模型服务的顺序，不新建停用管理器。整服不可逆停机时，非持久化`TextDisplay`与普通模型一样走[terminal retirement](model-presentation-api.md#整服停机的-terminal-retirementcurrent)：撤销handle/调度状态并完成原future，不跨region调用实体删除；热卸载、停机前已完成失败和非整服scheduler拒绝仍保持异常。terminal判断必须在cleanup future的唯一claim点重检，不能只在service close入口检查。

未加载/失效世界、离线viewer、已停用owner或宿主均拒绝创建；调度、生成或显示失败清理本次实体并报告所属对象，清理失败由完成结果异常报告，不影响铁路授权。提交的列表比较及内存为O(N)，N为本次标签数；真实实体操作随变化标签数增长，首次创建仍为O(N)，closeOwner低频遍历未完成handle。服务不扫描世界轨道、列车或全体玩家，不加入周期性业务重算或额外标签数量上限。

必需验收：独立Java消费者只通过公开API编译；同一玩家以外不可见；标签增删改/空列表/相同内容无操作；暂停创建或显隐后关闭不复活；旧序号拒绝；换世界/同UUID世界重载不复用旧handle；附属停用后清理继续完成，热卸载中主插件提前停用或删除失败不能伪称成功；整服stop时已接受spawn/reconcile及预检→claim竞态均terminally retire且不调度实体删除。既有普通模型服务行为不变。模拟调度测试与真实Paper/Folia、客户端验收分别记录。无持久化变化或版本升级。
源码入口为`api/editor/*.java`、`api/internal/DefaultEditorTextService.kt`及`EditorTextLifecycleListener.kt`；BukkitPlugin创建服务并在停用时关闭，统一registration helper发布服务。`DefaultEditorTextServiceTest`使用受控dispatcher和记录实体验证串行操作及实际spawn/show暂停；沿用`BukkitServiceTestSupport`构造平台事件，不更改Bukkit生命周期标志。Java消费示例只编译公开接口，不声称实际启用插件或客户端已验证。
例如边界A/B仍存在时只改A文字，B不重建；关闭后旧计算再次submit不能恢复任一文字。应提交准备好的完整标签值，不应暴露TextDisplay让调用方跨线程修改或将进路校验放入通用文字服务。接口评审和实现均属于当前Stage 2的独立小批次，不启动后续SimpleSignals平台拆分任务。

## 验证与未实施部分

检查位置/方向、警告仅命中相关出入口、数量预算、玩家隔离和关闭后迟到结果不显示。
真实客户端的辨识度、遮挡及 Folia 跨区显示须实测，不能用平台替身宣称完成。
普通车站/停车点ItemDisplay、全RailWand几何、独立进路预览和警告不在此次迁移范围。

## 共用轨道平面图案与客户端资源

`EditorMarkerLabels.at(id, glyph, position, tangent, normal, widthMeters, lengthMeters, color)`是纯值准备入口，三类图案为`EditorMarkerGlyph.DIRECTION/BOUNDARY/COVERAGE`。只读取Vector3dc并复制，不保留输入。沿轨切线归一化为局部+Y，对法线做正交化得到局部+Z，横向为切线×法线（局部+X），再将基底转换为单位四元数。图案沿法线抬高0.08米以免与轨面重合；宽度跨轨、长度沿轨，均为米。非有限、零或平行的方向输入拒绝，不猜测有效朝向；共享helper不查询轨道或Bukkit世界，单个标记准备成本O(1)。具体字形像素居中由helper处理，业务模块不得复制字体常量/四元数或各自猜像素补偿。

当前字形为8字体像素高、ascent 7、advance 9；26.1.2实际TextDisplay客户端矩阵使其中心落在局部(+0.5,+5)像素，需沿横向补偿`-width/16`、沿切线补偿`-5*length/8`。尺寸换算采用每字体像素0.025米，不能仅凭位图中心猜偏移。矩阵组合回归同时验证世界中心和宽/长边；客户端字体布局变化时须先核对目标renderer，不能调整业务边界或选轨容差来抵消显示偏差。

字体`javarailways:editor_markers`的U+E100/E101/E102分别为方向箭头、双横边界和实心覆盖矩形，Component负责染色。实心矩形可拉伸为首尾相接的细线段；旧双侧虚线已移除，方向与边界字形不变。客户端资源源码及其Node纹理生成脚本由独立的JavaRailwaysResources仓库维护，不覆写minecraft:default字体或现有物品/模型。26.1.2/84.0客户端资源包由资源仓库维护；本次需客户端替换旧编辑资源包，不能只更新服务端。元数据是Minecraft资源格式而非世界schema，不修改服务器持久化。缺少客户端包不会自动退回粒子，也不能宣称该客户端能正确显示私有字形；安装/合并说明由示例README负责。

RailObjectWand在worker准备运行方向和到达方向图案，按轨道实例、不可变曲线、方向及目的地参数缓存；玩家移动只筛选缓存，不导致重复采样。每玩家一个显示会话，切模式/离手/换世界或关闭时令旧会话失效，迟到计算不能向新会话提交；原WayPoint标记不迁移。准备及筛选随候选轨道/标记数量增长，不增加物理tick工作或轨道业务缓存。

必需测试：正反向、坡道/倾斜与输入向量不变；同位置改姿态复用实体、回到普通文字恢复全部样式；重复提交无操作及关闭后迟到创建清理；RailObject方向缓存不因移动失效，轨道/方向/目的地修改会更新。Java消费者同时编译旧五参数标签和新图案入口。真实客户端的字体原点、尺寸、遮挡与Folia跨区显示须另验，不能由数值测试代替。
