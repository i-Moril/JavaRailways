# 持久化与 Schema 演进

本规则适用于世界 split snapshot、Bukkit sidecar、模型包配置、Wand PDC、WebUI draft/recovery、extension payload 等跨进程或重启保存的数据。完整政策来源是 `ai_tasks/persistence_schema_evolution_rules.md`。

## 决策顺序

1. 先判断是否为可独立读取、写入、迁移或替换的持久化边界；嵌套 DTO 不自动拥有版本。
2. current serializer 能直接、正确、无猜测读取旧数据时，增加带默认值/nullable 字段，不升级版本。
3. 字段重命名、删除、类型/单位/语义变化或无默认值必填字段属于 breaking change；只升级最小独立边界。
4. 旧输入只在 decode boundary 连续单向 upcast 到 current DTO；业务层只处理 current model，writer 只写 current version。
5. 默认开发期不新增旧开发数据 runtime reader；优先一次性、支持 dry-run 且可审计的离线迁移。

```text
读取 bytes/text
  → 识别 boundary schemaVersion/formatVersion
  → 必要时纯函数 Vn → Vn+1
  → 严格 current decode
  → 引用与业务不变量预校验
  → materialize 当前业务对象
```

## 不得混用的概念

- `formatVersion`/`schemaVersion`：独立磁盘 contract。
- `infrastructureRevision`：配对 infrastructure/runtime 文件与比较先后，不是 schema 版本。
- `physicsEpoch`：物理发布时序，不是 schema 版本。
- plugin/model package release：发布版本，不替代数据 schema。
- 能力变体：用稳定 `kind`/`type`（例如 `regular`、`match_half`），不能用 `V2` 表示更强能力。

当前证据：`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt` 分开声明 infrastructure/runtime format；`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/persistence/WorldPersistence.kt` 只选择 `infrastructureRevision` 匹配的文件对；`TunnelBridgeBuilder/src/main/kotlin/work/art1st/javarailways/tunnelbridgebuilder/wand/BlockWandState.kt` 使用固定 `type` discriminator 和严格 JSON。

## 严格 current schema

默认采用等价配置：

```kotlin
Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}
```

`Core/src/main/java/work/art1st/javarailways/core/persistence/WorldSnapshotCodec.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/persistence/BasicInfrastructureSnapshot.kt` 与 `TunnelBridgeBuilder/src/main/kotlin/work/art1st/javarailways/tunnelbridgebuilder/wand/BlockWandState.kt` 都有本地证据。未知 future version、未知 type ID、损坏输入和缺失引用必须 fail-fast；不要为了“兼容”全局打开 `ignoreUnknownKeys=true`。

## Extension identity 规则

新持久化多态 payload 使用稳定、namespaced `typeId + schemaVersion + payload`。`typeId` 不随类名或 package 重构改变，registry 不扫描 classpath，也不根据存档字符串执行 `Class.forName()`。现有 `Core/src/main/java/work/art1st/javarailways/core/persistence/WorldSnapshotCodec.kt` 仍以 JVM class name 反射解码 extension data，`docs-site/docs/architecture/persistence.md` 也明确标记此风险；这是遗留边界，不是新实现范式。只有任务明确要求时才增加 allowlist legacy alias，并写明移除条件。

`Basic/src/main/java/work/art1st/javarailways/basic/api/timetable/TimetableExtension.kt` 的 `typeId + version` 表示 provider 行为/参数 contract。若只是旧参数形状迁移，优先 upcast 到当前 provider，而不是永久运行平行 provider。

## 必测场景

- additive field：旧 fixture 可读、默认值明确、current round-trip 保留新字段、版本不变。
- breaking upgrade：每个受支持旧版有 fixture，每个连续 upcaster 独立测试，future version/断链/损坏输入失败，writer 只写 current。
- 引用数据：materialize 前拒绝 orphan。参考 `Basic/src/test/java/work/art1st/javarailways/basic/persistence/BasicInfrastructureSnapshotTest.kt`。
- split snapshot：基础设施 revision 必须一致，备份恢复不能组合不匹配文件。参考 `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/persistence/WorldSnapshotExtensionDataTest.kt` 与 `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/persistence/WorldPersistence.kt`。
- transport（gzip、文件后缀、atomic move）和 payload schema 分开测试；transport 改变本身不升级 schema。
