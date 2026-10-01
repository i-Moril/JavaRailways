# 通用模型呈现与轨道几何查询

## 1. 状态、范围、触发条件与 owner

状态：`TARGET / API DRAFT`，尚未实现，签名供下一批审查，不是已发布 API。
`BukkitPlugin` 拥有中立部件构造、backend、目标线程调度与 visual cleanup；调用插件拥有模型包、
业务状态、摆放位置和状态到模型场景的映射。SimpleATO/SimpleATOBukkit 不参与这条呈现路径。

CURRENT：`RailTargetService` 只有玩家射线选轨；宿主注册七个 service，没有模型呈现 service。
`ModelTreeBuilder` 只有车体/转向架公开入口，但内部部件树与 `CompositeModel` 已支持局部变换、
物品模型、动画场景和 backend visibility。此目标提取普通模型入口，不重写列车渲染。

不包含资源申请、信号灯色/速度、进路判定、列车座椅或转向架装配；不公开整个 `ModelManager`、
`CompositeModel`、mutable world 或调度器实现类。

## 2. 模块工作方式

调用插件在低频加载/编辑时解析模型包与轨道位置，得到不可变部件定义和世界变换，创建逻辑 handle。
业务状态变化后只提交最新可见状态；宿主在 location/entity owner 上创建或更新 backend visual。
调用方无需等待实体出现，物理/信号计算也不等待 Bukkit。

列车继续使用车体/转向架专用入口；它们与新增普通入口复用同一个内部部件构造过程。
普通入口不伪造 `CarDefinition`、`Car` 或驾驶座。backend 仍沿用现有 NATIVE/ENTITYLIB 选择。

## 3. 内部机制与关键算法

- 按 world UUID / rail UUID 定位既有 rail，再返回其不可变 canonical AB 曲线；不扫描空间索引，
  不创建另一条曲线，不由 `TopologyState` 猜测 XYZ。
- 调用方用已知 AB 弧长执行 `curve.getPointAt(s)`。沿用 Core 的弧长定位及插值；BA 改变朝向，
  不把存储位置改成 `length - s`。模型是否保持竖直或跟随轨道法线由调用插件配置决定。
- 部件定义在创建 handle 前解析引用并检查循环。宿主把中立定义适配到既有部件树；列车专用
  SEAT/controlEnd、bogie offset/wheel 装配留在原入口，不进入通用合同。
- 每个 handle 只留一个正在调度/执行的任务和一个最新待处理值。提交替换 pending，任务完成时
  再处理当时最新值；实际 transform/scenes 未变就不调用实体更新。关闭后旧任务不得复活实体。
- 保持 children-before-root 显示、root-before-children 隐藏与幂等销毁。

## 4. 接口、数据结构与单位

以下是**概念签名草图**；类型的最终字段/包名在实现前审查：

```java
// 扩展既有 RailTargetService，不新增 world manager service。
Optional<PrimeCurve> findCurve(UUID worldId, UUID railId);

interface ModelPresentationService {
    ModelHandle create(Plugin owner, UUID worldId, String objectId,
                       ModelDefinition definition);
}
interface ModelHandle extends AutoCloseable {
    void submit(ModelPresentation presentation);
    void close();
}
record ModelPresentation(long revision, ModelTransform transform,
                         Map<String, String> animationScenes) {}
```

- `findCurve`：rail 缺失返回 empty；world 未加载/未注册抛 `IllegalStateException`，与既有 world
  capability 一致。旧 curve 可继续读取，但不证明同 UUID 的新 world 仍有该 rail。
  lookup 必须使用线程安全的已发布 lookup 或既有安全读取入口；不能直接跨线程访问 mutable rail map，
  也不能为取得结果阻塞 simulation 等待 Bukkit。具体复用入口在实现前核对。
- `objectId`：调用插件内部稳定对象名，用于诊断；不能用它绕过 handle 的关闭/替换生命周期。
- `ModelDefinition`：解析完成的不可变部件树、资源引用和动画定义，不是磁盘路径、YAML 文本或
  活的 Bukkit `ItemStack`。具体中立值类型尚待实现前冻结；不得复制出第二套列车配置系统。
- `ModelTransform`：世界位置（米）与旋转（单位四元数），按值不可变。部件缩放沿用定义中的
  local scale；本草图不承诺 root scale，因为现有 `CompositeModel.updateState` 没有传播它。
- `animationScenes`：`animationName -> sceneName`，复用已有场景机制，不新增全局场景解析器。
  每次提交是完整选择值，不是 patch；必须明确恢复未选择动画的基础状态，不能留下上个 variant
  的场景。映射作用域和同名动画规则需与现有实现核对后冻结。
- `revision`：handle 内递增的临时显示序号，不是 physics epoch、拓扑 revision 或存档版本。
- `create` 只分配逻辑 handle；第一份有效提交才调度创建实体。`submit` 不表示画面已经完成。

## 5. 不变量、生命周期、线程与版本

输入全部为 immutable value；禁止把可变 vector、map 或 Bukkit 配置对象交给异步任务后继续修改。
create/submit/close 的逻辑操作不读取 Bukkit entity/world mutable state，不阻塞等待 owner scheduler。
实际生成、移动、场景切换和删除均在目标 location/entity owner 上执行；世界引用由宿主解析。

`close` 幂等并丢弃 pending。owner plugin disable、world unload、宿主 shutdown 均关闭所属 handles；
同 UUID reload 是新 world instance，旧 handle 不能使用。跨 region 更新必须按现有 owner-aware
dispatch 处理，不以“主线程”替代 Folia ownership。

这里不新增插件禁用协议，不释放信号插件的资源 claim。资源 owner gate 保持既有合同。
呈现失败只影响画面并报告 owner/object 诊断，不能改变授权或从 safety callback 抛回错误。
本批没有持久化变化，也没有 runtime 兼容路径。

## 6. 校验与错误矩阵

- 缺 rail：查询 empty，调用插件拒绝该摆放；未加载 world：明确失败，不绑定其他 world。
- 非 finite 坐标、非单位有效旋转、非法定义/动画引用：拒绝提交/创建，不生成半套实体。
- 已关闭 handle 或旧显示 revision：忽略，不创建或回放历史状态。
- owner retired、world unload 或 backend 创建失败：清理已创建部分，记录可定位诊断；信号安全输出不变。
- 场景/资源键未配置：调用插件在低频验证时拒绝；不自动使用 STONE 或虚构灯色。
  服务器只能检查引用格式与本地模型定义，不能保证客户端已安装资源包。

## 7. 性能、复杂度与退化行为

rail ID 查找目标为 `O(1)`；弧长采样沿用 Core `O(log N)` 定位加常数插值（N 为该曲线采样点数）。
两者都只用于加载/摆放/相关编辑，不按 tick 遍历所有模型重新算位置。

解析/校验与构造为 `O(P + A)`，P 是展开后的部件数，A 是动画定义大小，属于低频路径；文件读取、
解析和重型展开不得在物理或 region owner 上完成。单次可见更新按受影响模型规模处理；scene map
比较为 `O(S)`，并非无条件 `O(1)`。队列占用为每 handle 常数份状态，无历史回放队列。
动画若启用，仍有既有动画运行成本；latest-only 不消除动画计算成本。拥塞允许画面落后，不允许物理等待。

## 8. Good / Base / Bad

- Good：同模型连续提交 Y、YG、G，执行前只保留 G；安全状态仍完整处理自己的更新。
- Base：第一次有效提交创建 multipart 模型；只改速度而 scenes/transform 相同，不更新实体。
- Bad：关闭后旧任务完成或 world UUID reload；不能创建旧画面，也不能影响新 handle。

## 9. 必需测试

- Java consumer 编译草图的最终公开 API；独立插件不依赖宿主实现包、不复制宿主 classes。
- lookup missing/unloaded、AB/BA 朝向及曲线删除后 retained read；不调用 nearest-point/pathfinding。
- 通用 multipart/局部变换/资源键/动画场景与既有列车构造回归；未选择场景不会残留旧 variant。
- 单 handle 的 latest-only、相同可见值 no-op、close/disable/unload/同 UUID reload 及部分创建失败清理。
- NATIVE/ENTITYLIB、目标 owner 与显示顺序回归；真实 Folia/resource-pack 画面另做 smoke，不能用 fake
  scheduler 通过代替实际可见性证明。

## 10. 迁移、未决事项与 Wrong / Correct

已决定方向：复用通用部件/backend，保留列车专用入口；信号语义与模型包仍归 SimpleSignals。
待审查：中立 part/animation 值类型、场景作用域/复位语义、普通构造的具体入口和显示插值参数。
未冻结信号包 YAML/JS 格式、轨道倾斜策略或物理摆放存档字段；这些由信号 owner 下一批提出。

- Wrong：把信号包伪装成车体，要求 addon 调用 `BukkitPlugin.INSTANCE.modelManager`。
- Correct：公开中立 handle/定义，内部复用部件构造；车体/转向架/驾驶座逻辑继续在列车入口。

源码依据：`ModelTreeBuilder.kt`、`render/model/CompositeModel.kt`、`PartAnimationConfig.kt`、
`api/rail/RailTargetService.java`、`api/internal/JavaRailwaysServiceRegistration.kt`。
