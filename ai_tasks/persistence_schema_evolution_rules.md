# 持久化与配置 Schema 演进规则

> 类型：Repository policy
> 适用范围：世界存档、Bukkit sidecar、模型包配置、WebUI 本地恢复数据、extension payload，以及其他跨进程/重启保存的数据
> 默认阶段：项目仍在开发期；除任务明确要求外，不在运行时代码中兼容任意旧开发数据

---

## 1. 核心规则

> **只给独立持久化边界编号；兼容性新增字段不升级版本；破坏性变化才升级；旧输入只在解码边界单向迁移到当前模型；业务代码只处理当前模型。**

版本号用于说明磁盘契约，不用于说明实现新旧、功能强弱或数据先后。

---

## 2. 先区分六种不同概念

| 概念 | 示例 | 是否为 schema version |
|---|---|---:|
| 独立文件/文档 schema | world runtime、RailRenderMeta、manifest | 是 |
| extension payload schema | `typeId + schemaVersion + payload` | 是，由 extension owner 独立管理 |
| 配置中的能力变体 | legacy integer CMD、component CMD | 否；使用稳定 `kind/type` |
| snapshot revision | `infrastructureRevision` | 否；只比较保存先后并配对文件 |
| physics epoch | `physicsEpoch` | 否；只表示物理发布时序 |
| 插件/模型包发布版本 | plugin version、package release | 否；不能替代文档 schema version |

禁止用一个版本字段同时承担上述多个职责。

---

## 3. 什么是“独立持久化边界”

满足以下任一条件才考虑独立 schema version：

- 有独立文件、数据库文档、消息或 local-storage value；
- 能被独立读取、写入、迁移或替换；
- 由独立 extension owner 管理 payload；
- 有独立公开契约和生命周期，例如模型包 `manifest.yml`。

嵌套 DTO 默认不独立编号。例如 `SavedTrain` 是 Core runtime snapshot 的组成部分；给 Train 增加字段时，不应自动创建 `SavedTrainV2`。

如果只有 Core runtime 内层 JSON 发生破坏性变化，只升级 Core runtime schema；外层 split file 的字段未变时，不连带升级 outer file。版本必须在最小独立边界上演进，避免级联 V2/V3。

---

## 4. 何时不升级版本

新 reader 能在不转换旧 JSON 的情况下正确读取旧数据时，保持当前 schema version。

典型兼容性变化：

- 增加有明确默认值的字段；
- 增加 nullable 字段并默认 `null`；
- 增加派生字段，但缺失时可以无歧义重建；
- 放宽输入约束；
- 修改内部实现而不改变序列化形状或字段语义。

Kotlin 示例：

```kotlin
@Serializable
data class SavedTrainV1(
    // existing fields
    val derailed: Boolean = false,
    val derailReason: String? = null
)
```

旧 JSON 缺少字段时由默认值补齐，因此不创建 `SavedTrainV2`，也不升级 runtime schema。

注意：这只保证 **backward compatibility**，即新 reader 可读旧数据。项目默认不保证旧插件读取新 writer 输出的 forward compatibility；需要滚动部署或降级读取时必须另行明确要求。

---

## 5. 何时升级版本

旧 JSON 必须先转换才能无歧义成为当前模型时，属于破坏性变化，应升级所属独立边界的 schema version。

典型破坏性变化：

- 字段重命名或删除；
- 字段类型、单位或业务含义改变；
- 一个字段拆成多个，或多个字段合并；
- list/map/union 的结构改变；
- 根文件布局改变；
- 新增没有可靠默认值的必填字段；
- 严格 reader 删除旧文件仍包含的字段；
- discriminator、type identity 或引用语义改变。

判断问题只有一个：

> 当前 serializer 是否能直接、正确、无猜测地读取旧数据？

如果答案是否定的，升级最小独立边界；如果答案是肯定的，不升级。

---

## 6. 能力变体不能冒充 schema version

两种并存能力使用稳定 discriminator，不使用 `V1/V2` 表示“旧 API/新 API”或“弱功能/强功能”。

不推荐：

```text
ItemStackConfigV1
ItemStackConfigV2
```

推荐：

```text
LegacyCustomModelDataConfig
ComponentCustomModelDataConfig
```

磁盘使用稳定 `kind/type` 区分。如果其中某一种 payload 自身以后发生破坏性变化，再在该 payload 边界增加 `schemaVersion`。

类名后缀 `Vn` 只用于确实代表已发布磁盘 schema 的类型，不用于一般实现类、策略或功能变体。

---

## 7. 标准读取与迁移结构

### 7.1 业务层只接受当前模型

统一流程：

```text
读取 bytes/text
  → 识别独立边界的 schemaVersion
  → 必要时执行 Vn → Vn+1 的纯 upcaster
  → 严格解码为 Current DTO
  → 预校验引用与业务不变量
  → materialize 业务对象
```

禁止在业务层散布：

```kotlin
when (version) {
    1 -> businessLogicV1(...)
    2 -> businessLogicV2(...)
}
```

### 7.2 writer 永远只写当前版本

只允许：

```text
read supported old versions
write current version
```

不长期保留 `writeV1()/writeV2()/writeV3()`。默认不实现 down migration；降级依靠升级前恢复点或显式离线工具。

### 7.3 破坏性迁移使用连续纯 upcaster

```kotlin
val migrations = mapOf(
    1 to ::upgradeV1ToV2,
    2 to ::upgradeV2ToV3
)
```

每个函数只负责一个版本增量，输入输出为 JSON tree 或专用 migration DTO，并满足：

- 纯函数，无 world/plugin/IO 副作用；
- 不 materialize 业务对象；
- 不静默丢失未知业务数据；
- 缺少连续迁移步骤时 fail-fast；
- 遇到高于当前版本的文件时 fail-fast。

旧版本类型不得扩散到 Core/Basic/Bukkit 业务 API。简单迁移优先操作 `JsonElement`；只有复杂迁移确实需要类型安全时，才在 migration package 内保留旧 DTO。

---

## 8. 如何限制兼容代码堆积

### 8.1 当前开发期默认策略

依照仓库 `AGENTS.md`：

- 默认不新增旧开发数据 runtime reader；
- 开发服数据使用一次性、可审计、可 dry-run 的迁移工具；
- 只有用户、任务合同或已发布兼容政策明确要求时，才保留 runtime compatibility path。

### 8.2 正式发布后的支持窗口

每个独立边界必须明确属于以下一种：

| 数据类别 | 推荐支持政策 |
|---|---|
| 长期世界存档 | 对明确支持的发布版本做 backward-transitive 单向升级 |
| 人工维护的模型/配置 | runtime 通常只接受当前版；提供独立转换工具或最多一个明确过渡版本 |
| cache、派生文件、WebUI crash recovery | 可丢弃重建，或最多支持一个版本；不要建立永久迁移链 |
| extension payload | extension owner 声明支持版本并提供自己的 upcaster；Core 只负责路由和 fail-fast |

支持窗口结束后，在同一次变更中删除：

- 对应 runtime migration；
- legacy alias/reader；
- 已失效 compatibility tests；
- 文档中的支持声明。

历史 fixture 可移入 migration-tool 测试或归档，但不得继续暗示 runtime 支持。

### 8.3 禁止平行 loader

避免长期保留完整的 `LoaderV1/LoaderV2/LoaderV3`。推荐只保留：

- 一个 current serializer/validator/materializer；
- 每个被支持破坏性版本的一段小型 upcaster；
- 每个受支持输入版本的一份最小固定 fixture。

---

## 9. Extension payload 规则

持久化多态 extension 使用稳定 identity，不使用 JVM FQCN：

```json
{
  "typeId": "javarailways:car_render_state",
  "schemaVersion": 1,
  "payload": {}
}
```

registry entry 至少包含：

```text
typeId
currentSchemaVersion
serializer/current codec
supported Vn → Vn+1 upcasters
explicit legacy aliases（仅在任务明确要求时）
```

规则：

- `typeId` 一旦发布不得随 Kotlin 类名、包名或插件重构改变；
- 新增带默认值的 payload 字段不升级 payload version；
- payload 破坏性变化只升级该 `typeId` 的 schema version，不升级整个 world snapshot；
- 未知 type ID/version 明确失败，不反射、不忽略；
- legacy FQCN 只能通过显式 allowlist alias，且必须有移除条件；
- registry 不扫描 classpath，不根据存档字符串执行 `Class.forName()`。

Timetable provider 的 `typeId + version` 表示 provider 行为/参数契约。只有行为或参数语义需要并存时才注册多个版本；如果只是旧参数形状迁移，优先 upcast 到当前 provider，避免永久运行多套 provider。

---

## 10. 严格校验与未知字段

默认使用严格 current schema：

```kotlin
Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}
```

意义：

- 拼写错误和未来未知字段不会被静默丢弃；
- 新增默认字段可读取旧文件；
- 当前 writer 会输出明确、完整的当前形状。

若某边界明确要求旧 reader 读取新数据，必须单独设计 forward/full compatibility；不得为了方便全局开启 `ignoreUnknownKeys=true`。

migration 解码可以先读取 JSON tree 和版本字段，但迁移到 current 后仍必须走严格 decode 和业务预校验。

---

## 11. 传输编码不等于 schema

以下变化通常不升级 payload schema：

- raw JSON → gzip JSON；
- 文件名或压缩后缀变化；
- atomic write/temp/backup 策略变化；
- pretty-print 开关变化。

这些属于 transport/storage encoding。reader 可以先识别编码，再把相同 JSON payload 交给 schema decoder。若 transport 与 payload 同时破坏性变化，应分别测试，不能共用一个含糊版本字段。

---

## 12. 测试与审查门槛

### 兼容性新增字段

- 固定旧 JSON fixture 可由 current reader 读取；
- 缺失字段得到明确默认值；
- current round-trip 保留新字段；
- 不创建新 schema version。

### 破坏性升级

- 每个受支持旧版本有最小固定 fixture；
- 每个 `Vn → Vn+1` upcaster 有独立测试；
- fixture 可以升级到 current model；
- current writer 只写 current version；
- 未知未来版本、缺失迁移步骤和损坏输入明确失败；
- migration dry-run 不写源文件；
- 正式写入前保留不可被本次轮转覆盖的恢复点。

### Review checklist

1. 这是独立持久化边界，还是嵌套 DTO/能力变体？
2. 旧数据能否被 current serializer 直接无歧义读取？
3. 如果升级，是否只升级了最小边界？
4. 是否只有 current model 进入业务层？
5. 是否只写 current version？
6. runtime compatibility 是否被明确要求？支持和移除条件是什么？
7. 是否可以用一次性开发数据迁移替代永久 reader？
8. 是否有固定 fixture，而不是只做新 writer/new reader round-trip？

---

## 13. 当前仓库示例

| 当前事项 | 处理规则 |
|---|---|
| `SavedTrainV1` 增加 `derailed=false`、`derailReason=null` | 兼容性字段扩展；不创建 Train/runtime V2 |
| manifest 从宽松结构改为严格字段/type 契约 | 破坏性公开配置变化；V2 合理 |
| RailRenderMeta 删除已落盘 `segments/pointArcs` | 对严格 reader 是破坏性变化；若要求读现有 V2，则升级该 sidecar 边界并 upcast；否则开发期离线迁移 |
| `ItemStackConfigV1/V2` 表示两种 CMD 能力 | 改用能力名称和 discriminator，不视为 schema 代际 |
| extension 从 FQCN 改为 stable type ID | identity 破坏性变化；仅按明确要求保留 FQCN allowlist alias |
| raw JSON 增加 gzip | transport 变化；不升级 JSON payload schema |
| `infrastructureRevision` 单调化 | revision 规则修复；不是 schema 升级 |

---

## 14. 一句话决策

```text
Additive change → defaults, no version bump.
Breaking change → bump the smallest persisted boundary and upcast once.
Variant capability → stable kind/type, not V2.
Old input → migration boundary only; business code stays current-only.
Compatibility not explicitly required → migrate development data, do not accumulate readers.
```
