# Wand 配置界面的有效条目与值快照

## 状态、职责与入口

`CURRENT`（2026-10-19，迁移自 CR-041）。BukkitPlugin 的`ConfigUIRegistry`拥有分组/项注册、玩家会话、构建事件、可见性求值和`BuiltGroup`；Bukkit `DialogRenderer`只把结果翻译成原生输入、按钮与回复，不重新决定哪些项有效。此批不改配置持久化、不新增锁/线程系统或响应式框架。

公开入口仍为`buildGroupForPlayer(groupId, playerId, context): BuiltGroup`，无新API类型。`getEffectiveItems(groupId)`只使用全局值进行无玩家构建事件的可见性查询。未知分组返回原有空结果，不创建注册项。

## 模块归属

公开配置类型位于 `bukkit.api.wand.config`，`WandConfigItem` 与 `WandConfigItemImpl` 分别描述配置项及其受支持实现；注册中心位于 `bukkit.ui.config`。这套功能只支持 Wand 编辑界面，不属于 Core/Basic 的平台无关铁路能力。Basic 不保留旧包别名或对 BukkitPlugin 的依赖。

RailWand 和 RailObjectWand 的目的地编辑器由 BukkitPlugin 使用同一套配置支持；SimpleATOBukkit 不反向引用这些类型。配置字段 ID、分组 ID 和保存语义保持不变。

## 一次构建如何完成

```text
取得分组与玩家会话 -> 触发本次ConfigGroupBuildEvent收集已注册项的增补/隐藏意图
  -> 捕获注册项及额外项的值 -> 用同一快照递归判断分组与字段可见性
  -> 唯一且排序后的effectiveItems + 有效子组元数据 + hiddenItemIds
  -> Bukkit直接构建/读取这些输入；修改触发键后显式重新构建
```

分组不可见或被事件隐藏时，其全部后代项均不进入有效列表；项自己的`visibleWhen`和`hideItem`仍生效。事件增加的直接项和子组必须经过相同筛选，不能通过额外组绕过隐藏规则。以key ID确定字段身份，重复有效候选只保留首次，排序沿用key.order；不借此增加替换/冲突仲裁系统。`effectiveSubGroups`是标题/导航信息，不是第二份输入来源。

例如root包含child，child包含A/B：隐藏child必须同时移除A/B；只隐藏A则只显示B一次。Bukkit不能将`effectiveItems`与原始`child.items`拼接，否则会重复B并让A复活。隐藏字段也不能被伪造回复写入本次会话。

## 值捕获与刷新

快照以key ID存本次捕获值和存在性，玩家session值优先，再回退注册项的当前值（其初始值沿用key.defaultValue）。监听器收集额外树后再捕获，之后同一次构建的所有可见性判断读取同一份值；`get/contains/getOrDefault`不能在调用时重读live registry/session。

创建后修改全局值、玩家session、reset或新增注册项，不改变旧快照；下一次显式构建才能取得新状态。未知key的get为null，contains为false，getOrDefault使用调用者给定默认值。快照固定映射和当时的值引用，不对任意泛型T反射深拷贝；调用方提供可变payload时不能原地修改它并期待历史快照隔离。

初始Dialog输入从built.snapshot读取，同一构建不因session随后变化而混用新值。玩家提交仍写现有session，显式重建用于重新评估依赖；不另建镜像配置store。

## 类型与注册合同

`ConfigKey`的默认值必须匹配展示用`valueType`并满足约束。实际值类型独立于控件类型，由受支持的非空默认值确定；枚举使用其声明类，不能把不同枚举都视为同一种`Enum`。NUMBER与NUMBER_TEXT均保存`Double`，可以使用不同控件展示相同值类型。同一ID的实际类型冲突在注册、读取、写入及会话本地值访问时明确抛错，不返回null或默认值掩盖编程错误。未注册key的读取仍返回null。

值写入由`WandConfigItemImpl`拥有；构造时也必须校验initialValue的类型和约束。注册中心拒绝其他`WandConfigItem`实现。单项、批量、完整分组树和子组扩展必须在修改正式索引/分组结构前完成支持实现与类型校验；结算pending贡献时也先检查完整待接纳集合，失败不得留下半注册状态。批量项注册一次性校验整批，之后内部安装已验证项；单项入口先校验再复用同一安装路径。该流程依赖调用方串行安排注册与结构变更，不在逐项安装时重复整表校验。

`registerToGroup`对已存在但`allowExtensions=false`的目标立即抛错；目标尚不存在时贡献只进入pending，目标注册后统一结算。目标最终不允许扩展时丢弃并记录警告的既有策略不在本轮修改范围。

## 构建事件与保存归属

所有可编辑配置项必须事先在注册中心注册。构建事件只负责组合已注册项及分组、控制展示与隐藏，不创建临时配置存储或临时保存回调。事件引入未注册项或同ID不同实际类型项时，在构建阶段明确失败；不能等玩家编辑并确认后才报告无法保存。复杂的独立编辑/保存流程由插件自行拥有，不扩展此通用配置系统来承接。

## 会话提交

`PlayerConfigSession.commit(): ConfigCommitResult`仍逐key提交dirty值，不新增多key原子事务。写入失败的key保留本地值与dirty，并进入`failures`；写入成功即进入`committedKeyIds`并清除dirty。写入后的通知异常进入独立的`notificationFailures`，不能谎报为未写入，也不能因dirty保留而自动重复通知副作用。两种失败任一存在时`succeeded=false`。

直接`ConfigUI.setValue`的通知失败明确以已写入后的异常传播。Dialog确认和枚举快捷操作分别提示“未写入”和“已写入但通知失败”；发生任一种失败时不继续确认完成回调。通知失败不回滚已提交配置，也不保证通知已产生的外部副作用可撤销。

## 线程与生命周期边界

注册与分组/监听器结构变更必须由调用方串行，不与构建并发；同一玩家session的修改/构建由其调用方串行。Bukkit玩家操作仍遵守player owner。并发Map只保护映射访问，不使ConfigGroup列表、WandConfigItem值和监听器整体线程安全。

快照只保证捕获后的读取稳定，不新增“多key全局原子事务”保证；需要这种一致性的调用方须协调写入与捕获。不据此声称Folia跨玩家global commit已验证，也不为未复现的并发场景加入全局锁。会话移除/重建沿用原生命周期，快照不反向持有live会话用于查询。

## 成本、失败与验证

构建时复制K个捕获值，递归处理G个分组/N个候选项并按有效字段排序，基本成本O(K+G+N+N log N)，暂存O(K+G+N)；不增加tick扫描。结果只在构建完成后返回，原有约束/回调异常仍交原调用边界处理，不静默放宽可见性或写默认值。

必须验证隐藏整棵子树、单项visibleWhen/hideItem、事件增补、重复字段、稳定排序；快照在registry/session/reset/注册变化后值与contains保持旧状态；玩家间会话隔离；真实renderer输入与捕获不重复/复活隐藏项，伪造隐藏字段不写会话，初始化值使用构建快照。平台替身只证明本地consumer逻辑，不代替真实Paper/Folia界面验收。

不变更世界/PDC/配置schema或增加旧开发reader。正确做法是配置 registry 生成一份有效列表、DialogRenderer 单次消费；错误做法是平台为“补子组”绕回原始items，或将读取live值的对象称为构建快照。实施及验证证据见[执行记录](../../../tasks/10-09-stage2-code-review/implement.md#cr-041配置ui收口2026-10-15用户单独批准)。

`BuiltGroup.triggerKeyIds`从本次构建的完整候选树（原始分组+事件新增项/分组）收集`dependsOn`，包括当前被隐藏、可能因输入变化而重新显示的项；`hiddenItemIds`仍只用于展示与输入校验。

## 运营目标编辑适配

BukkitPlugin 的 `bukkit.tool.RailObjectDestinationEditor` 拥有 RailObjectWand 中 Destination、Station berth 和 Depot point 的
表单字段、按角色显示、会话初值、输入解析及保存/删除适配；宿主直接构造编辑器，并把其 `configUI` 交给现有 DialogRenderer。
它不是新的插件服务或第二套 UI 系统，也不创建 Bukkit 生命周期。通用 DialogRenderer、选点、每玩家编辑/待创建身份、
PDC 编解码、rail-scoped edit permit、玩家消息和标记实体仍由宿主拥有。

正常编辑先由宿主解析选中的 WayPoint 与 canonical Destination，再调用 `seedSession` 填入配置会话；
通用 DialogRenderer 使用编辑器注册的 `railObject.waypoint.edit` 组与宿主 locale resolver 展示字段。
提交时 `readEdit` 把会话值解析为 `OperationalDestination`，宿主取得确切轨道编辑许可后才调用
`save(world, wayPoint, updated, pending)`；成功后宿主刷新标记和消息。删除同样在许可内调用 `remove`。

字段可见性由既有 ConfigKey 的 role 依赖决定，不另建 UI 状态机。Station/Depot 的 canonical ID 与业务投影由
[SimpleATO Destination service](../../javarailways/simpleato/destinations.md)负责；编辑适配器保留对话框未显示的角色字段，
不分别写 Station/Depot store。已有 canonical 对象使用 service.replace，新对象使用 service.register；
standalone WayPoint 转换前移除旧 anchor，注册失败恢复原 anchor 并向宿主抛出错误。
删除 canonical 对象使用 service.remove；只有 standalone anchor 才直接从 Basic WayPointStore 删除。

会话初值和解析不改变世界；非法字段/角色构造失败不进入 mutation。save/remove 必须在宿主串行模拟上下文和编辑许可内执行，
不能从 UI 展示线程直接调用。编辑器不持有 live world、player、entity 或新增异步任务，也没有额外注册/卸载流程；
会话继续由现有配置 registry 管理。字段数固定，表单读取/可见性为固定规模；标签解析按输入长度线性处理，
投影更新与失败回滚复用 service，不扫描轨道或新增 tick 工作。

既有 Destination 映射测试验证未显示字段与方向保留；动态 UI 测试直接使用生产注册/seed，验证三种角色和 trigger key，
不在测试里复制一套 ConfigGroup。真实 service 回归验证 standalone 转换及注册失败恢复。
迁移只调整源码归属，PDC 状态、配置组/key token、数据格式和版本不变；SimpleATOBukkit 不保留第二份编辑实现，也不反向依赖 BukkitPlugin。
