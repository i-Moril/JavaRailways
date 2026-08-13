# Repository agent rules

当前代码库是开发中的插件。默认不要在运行时代码中兼容任意旧开发数据；这通常属于过度设计。开发服中存在的数据应使用可审计、可 dry-run、保留恢复点的一次性迁移。只有用户、任务合同或已发布兼容政策明确要求时，才新增或保留 runtime compatibility path。

涉及世界存档、配置文件、extension payload、WebUI 本地恢复数据或其他持久化格式时，必须遵守 [`ai_tasks/persistence_schema_evolution_rules.md`](ai_tasks/persistence_schema_evolution_rules.md)：

- 只给独立持久化边界编号；revision/epoch 不是 schema version。
- 增加有明确默认值的兼容字段不升级版本。
- 字段改名/删除、类型或语义改变、结构重排等破坏性变化，才升级最小独立边界。
- 旧输入只在 decode/migration 边界单向 upcast；业务层只处理 current model，writer 只写 current version。
- 并存能力使用稳定 `kind/type`，不要用 `V1/V2` 表示实现新旧或功能变体。
- extension 使用稳定 namespaced type ID；禁止根据存档字符串反射加载类。
- 新增兼容路径前必须写明支持窗口和移除条件，并用固定 fixture 验证；不要只做 current round-trip。
