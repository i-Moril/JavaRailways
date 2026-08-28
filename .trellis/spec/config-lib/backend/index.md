# ConfigLib Backend 规范

## 适用范围

适用于 `ConfigLib/` 的小型 kotlinx.serialization helper。当前实现只定义 `PersistentBy`、`Loads`、`DelegateSerializer` 与 `registerDependencyEntity`；世界文件版本、Bukkit IO、模型 YAML 和迁移工具不应放进本模块。

## 开发前检查

- [ ] 确认需求是通用 serializer/dependency materialization 能力，而不是某个领域 snapshot DTO。
- [ ] 明确 surrogate 的 current schema、dependency lifetime 和 `save()`/`load()` 方向。
- [ ] 检查 `Core`、`Basic`、`BukkitPlugin` 的调用方，避免改变泛型 variance 或 contextual registration contract。
- [ ] 涉及落盘 shape 时先阅读共享[持久化政策](../../javarailways/shared/persistence.md)。

## 子文档索引

本层规模小，当前不拆 topic 文档；本页是唯一规范来源。跨边界政策链接到共享 persistence，而不在这里复制版本规则。

## 本地模式

```text
logical T implements PersistentBy<S>
  → save() produces serializable surrogate S
  → DelegateSerializer encodes S
  → decode S
  → Loads<T, D>.load(dependency) materializes T
```

- `DelegateSerializer.descriptor` 沿用 surrogate serializer descriptor；不要伪造另一套 wire shape。
- dependency 在 serializer 构造/`SerializersModule` 注册时注入，不从全局 singleton 或反射寻找。
- `registerDependencyEntity` 只注册 contextual serializer；调用 DTO 必须显式使用 `@Contextual` 或等价 serializer contract。
- 不在 helper 中吞掉 deserialize/materialize 异常或 fallback 到空对象；让边界 owner 添加业务上下文。
- schema version 属于使用该 helper 的独立文件/payload owner，不属于 `DelegateSerializer` 自身。

正例见 `ConfigLib/src/main/kotlin/work/art1st/configLib/persistence/Persistence.kt`。反例是把 world lookup、Bukkit service 或 version switch 加入 `DelegateSerializer`。

## 质量检查

- [ ] 运行 `./gradlew :ConfigLib:test`。
- [ ] generic/contextual contract 改动运行 `./gradlew :Core:test :Basic:test :BukkitPlugin:test`。
- [ ] 测试分别覆盖 encode 调用 `save()`、decode 带 dependency 调用 `load()`、contextual module round-trip。
- [ ] 若改变 serialized shape，增加手写 fixture 和 strict failure test，不只做自洽 round-trip。
- [ ] 运行 `git diff --check`。

证据：`ConfigLib/build.gradle.kts`、`ConfigLib/src/main/kotlin/work/art1st/configLib/persistence/Persistence.kt`、`ConfigLib/src/test/kotlin/work/art1st/configLib/persistence/PersistenceTest.kt`。
