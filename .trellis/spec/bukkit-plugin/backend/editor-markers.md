# 铁路编辑标记的共用视觉规范

## 范围和当前状态

统一轨道、车站、闭塞和保护区域工具的显示含义，不统一业务定义。
已有 RailWand 使用锚点 ItemDisplay、轨道信息 TextDisplay 和粒子；道岔工具和通路预览使用玩家粒子。
这些工具尚未全部迁移到统一显示实现。本批接入保护区域多通路警告；完整 TextDisplay 方向与区间显示仍待后续实现。

## 一次编辑如何显示

所属模块判断对象状态，准备点位、方向、区间及提示；Bukkit 适配把准备好的显示发给编辑玩家。
例如一个入口到出口有两条合法通路，SimpleSignals 自动保存最短路，并在这两个边界显示多通路警告。
警告不能表示列车已经获准进入，也不能改变进路表或轨道连接。

## 基本表达

- 点标记说明位置，可用于轨道锚点、运营停车点、闭塞分界点和保护区域边界。
- 方向标记沿轨道切线指示方向；不能用 AB/BA 枚举文字代替玩家看到的实际方向。
- 区间高亮说明覆盖范围，路径预览还显示行进方向；同色不表示属于同一业务对象。

选中、警告和不可用须与业务状态区分。警告用显眼标识并说明原因，不能只染红让玩家误认停车信号。
通路入口/出口颜色与警告可以并存；多通路警告位于有关出入口，不散布到内部道岔。
速度颜色不兼任选中、多通路或未连通。具体速度配色、资源包图案及完整 TextDisplay 风格尚未定稿。

## Ownership、线程与生命周期

### 已确认的公开能力方向（TARGET，2026-10-15）

用户同意BukkitPlugin向附属插件提供可复用的Wand交互与Rail可视化能力。按实际消费者逐步开放：工具识别/持物生命周期、交互回调，以及轨道点/方向/范围高亮、临时文字和相关玩家可见性。具体接口签名及既有工具迁移范围尚未冻结，不能将本方向描述为已实现的统一API。
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

目的仅是让附属插件显示玩家专用的临时文字并可靠清理。BukkitPlugin提供服务实现，首个计划消费者是当前SimpleSignals及以后SimpleSignalsBukkit，不要求先实施领域模块拆分；本API批次尚未接入边界显示。首批不公开Wand内部模式、通用脚本交互系统或完整RailWand控制权，轨道高亮继续复用已有粒子准备，后续有真实需求时再提取公共接口。

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
record EditorTextLabel(String id, double x, double y, double z, Component text) {}
```

`create`在viewer所属线程捕获当前世界实例，分配操作对象而不生成实体；身份为实际owner插件实例、viewer玩家实例、世界实例和非空objectId，拒绝同时活动的重复身份。不接收轨道/闭塞/信号ID，不对label的id赋予业务含义。坐标为该世界中的绝对米制位置；Component为不可变Adventure文字，调用方提交完整列表，服务复制后持有。标签id非空且本次列表内唯一，坐标有限；非法输入在替换待显示状态前拒绝。显示序号非负且在handle内递增，与schema/epoch无关；旧序号和关闭后提交不恢复显示。

正常流程是玩家owner创建handle，调用方worker准备纯标签值，submit交给宿主后台调度，再在标签位置/实体owner上创建或更新非持久化TextDisplay，最后在玩家owner上仅向viewer显示。显示实体默认对其他玩家不可见，使用固定朝向玩家的文字样式；首批不加尚无需求的字体/动画/点击交互配置。完整空列表清除本handle的所有文字；后续列表按稳定label id与上次实际显示状态比较，保留未变标签，删除缺项，只更新变化部分。只改文字时更新原实体；位置改变均删除旧实体后在新目标位置创建，不使用可能跨Folia区域的同步传送。

内部复用现有`PlatformDispatcher`、`LatestAffinityTaskDispatcher`及RailWand中默认隐藏/仅对指定玩家显示的方式，不公开其私有对象。每handle只保存一份最新待显示列表及一个调度工作，不能堆积历史画面；实体操作不在状态锁内执行。这里要求复用实际机制，不要求为提取通用基类而重构整个模型服务。

调用方负责离手/权限或编辑上下文失效时close；服务兜住玩家退出/换世界、世界卸载及owner停用。关闭立即撤销显示意图，未开始或迟到的创建必须检查同一handle与世界实例并清理，不重新显示。实体调度使用仍启用的JavaRailways平台owner；附属插件停用后仍可执行清理，而不是借用外部插件对象自行排任务。close不阻塞；closeOwner合并该owner全部尚未完成的清理（含此前单独关闭的handle），失败保留可观察的异常结果，不能因移出活动表而误报成功。先完成附属显示清理再停用主插件，沿用现有模型服务的顺序，不新建停用管理器。

未加载/失效世界、离线viewer、已停用owner或宿主均拒绝创建；调度、生成或显示失败清理本次实体并报告所属对象，清理失败由完成结果异常报告，不影响铁路授权。提交的列表比较及内存为O(N)，N为本次标签数；真实实体操作随变化标签数增长，首次创建仍为O(N)，closeOwner低频遍历未完成handle。服务不扫描世界轨道、列车或全体玩家，不加入周期性业务重算或额外标签数量上限。

必需验收：独立Java消费者只通过公开API编译；同一玩家以外不可见；标签增删改/空列表/相同内容无操作；暂停创建或显隐后关闭不复活；旧序号拒绝；换世界/同UUID世界重载不复用旧handle；附属停用后清理继续完成，主插件提前停用或删除失败不能伪称成功；既有普通模型服务行为不变。模拟调度测试与真实Paper/Folia、客户端验收分别记录。无持久化变化或版本升级。
源码入口为`api/editor/*.java`、`api/internal/DefaultEditorTextService.kt`及`EditorTextLifecycleListener.kt`；BukkitPlugin创建服务并在停用时关闭，统一registration helper发布服务。`DefaultEditorTextServiceTest`使用受控dispatcher和记录实体验证串行操作及实际spawn/show暂停；沿用`BukkitServiceTestSupport`构造平台事件，不更改Bukkit生命周期标志。Java消费示例只编译公开接口，不声称实际启用插件或客户端已验证。
例如边界A/B仍存在时只改A文字，B不重建；关闭后旧计算再次submit不能恢复任一文字。应提交准备好的完整标签值，不应暴露TextDisplay让调用方跨线程修改或将进路校验放入通用文字服务。接口评审和实现均属于当前Stage 2的独立小批次，不启动后续SimpleSignals平台拆分任务。

## 验证与未实施部分

检查位置/方向、警告仅命中相关出入口、数量预算、玩家隔离和关闭后迟到结果不显示。
真实客户端的辨识度、遮挡及 Folia 跨区显示须实测，不能用平台替身宣称完成。
车站、停车点、全轨道方向、连续区间横杠及资源包样式的统一迁移不在本批。
