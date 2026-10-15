# 配置UI的有效条目与值快照

## 状态、职责与入口

`CURRENT`（2026-10-15，CR-041）。Basic的`ConfigUIRegistry`拥有分组/项注册、玩家会话、构建事件、可见性求值和`BuiltGroup`；Bukkit `DialogRenderer`只把结果翻译成原生输入、按钮与回复，不重新决定哪些项有效。此批不改配置持久化、不新增锁/线程系统或响应式框架。

公开入口仍为`buildGroupForPlayer(groupId, playerId, context): BuiltGroup`，无新API类型。`getEffectiveItems(groupId)`只使用全局值进行无玩家构建事件的可见性查询。未知分组返回原有空结果，不创建注册项。

## 一次构建如何完成

```text
取得分组与玩家会话 -> 触发本次ConfigGroupBuildEvent收集增补/隐藏意图
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

## 线程与生命周期边界

注册与分组/监听器结构变更必须由调用方串行，不与构建并发；同一玩家session的修改/构建由其调用方串行。Bukkit玩家操作仍遵守player owner。并发Map只保护映射访问，不使ConfigGroup列表、ConfigItem值和监听器整体线程安全。

快照只保证捕获后的读取稳定，不新增“多key全局原子事务”保证；需要这种一致性的调用方须协调写入与捕获。不据此声称Folia跨玩家global commit已验证，也不为未复现的并发场景加入全局锁。会话移除/重建沿用原生命周期，快照不反向持有live会话用于查询。

## 成本、失败与验证

构建时复制K个捕获值，递归处理G个分组/N个候选项并按有效字段排序，基本成本O(K+G+N+N log N)，暂存O(K+G+N)；不增加tick扫描。结果只在构建完成后返回，原有约束/回调异常仍交原调用边界处理，不静默放宽可见性或写默认值。

必须验证隐藏整棵子树、单项visibleWhen/hideItem、事件增补、重复字段、稳定排序；快照在registry/session/reset/注册变化后值与contains保持旧状态；玩家间会话隔离；真实renderer输入与捕获不重复/复活隐藏项，伪造隐藏字段不写会话，初始化值使用构建快照。平台替身只证明本地consumer逻辑，不代替真实Paper/Folia界面验收。

不变更世界/PDC/配置schema或增加旧开发reader。正确做法是Basic生成一份有效列表、平台单次消费；错误做法是平台为“补子组”绕回原始items，或将读取live值的对象称为构建快照。实施及验证证据见[执行记录](../../../tasks/10-09-stage2-code-review/implement.md#cr-041配置ui收口2026-10-15用户单独批准)。

已知边界：`BuiltGroup.triggerKeyIds`当前只从原始注册group收集`dependsOn`，事件新增字段的依赖不在其中；本合同不声称其变更会自动重建，缺口单列审查台账，不能以隐藏字段修复掩盖。
