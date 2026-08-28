# 持久化与测试

## Snapshot 持久化边界

Basic 将 infrastructure/runtime 与 Core 对应边界分开：

- `BasicInfrastructureSnapshotV1` 保存 Destination/WayPoint/Line/revision/rail metadata；
- `BasicRuntimeSnapshotV1` 保存 timetable、dispatcher、rolling stock 等 runtime 数据。

二者的 `infrastructureRevision`/`physicsEpoch` 必须与同批 Core snapshot 对齐；这些字段不是 schema version。Codec 使用 `encodeDefaults = true`、`ignoreUnknownKeys = false`，只接受 current format。

capture/restore 必须在 materialize 前完成引用预校验：missing rail、duplicate metadata、missing station 等均 fail-fast。Destination 是 canonical 运营对象，WayPoint 的派生/legacy projection 不能导致重复落盘。证据：`Basic/src/main/java/work/art1st/javarailways/basic/persistence/BasicInfrastructureSnapshot.kt`、`Basic/src/test/java/work/art1st/javarailways/basic/persistence/BasicInfrastructureSnapshotTest.kt`。

新增字段时：

- 有明确默认值且 current reader 可直接读旧 JSON：保持版本；
- rename/type/meaning/required shape 变化：升级最小 Basic boundary，并按明确支持窗口提供连续 upcaster；
- 不因 Core boundary 变化连带升级 Basic，除非 Basic 文件自身 contract breaking。

## Test fixtures 与 isolation

`Basic` 启用 `java-test-fixtures` 并消费 Core fixtures。`TestBasicWorldDataRegistry` 等 test-only registry 在每个测试后 unregister，避免 world-local state 泄漏。构造 world/rail/train 时优先复用 Core fixture，不复制实现细节。

测试应覆盖：

- curve：template classification、端点/切线/roll、精确半径、parallel、vertical profile、worker guard；
- route/safety：direction restriction、weighted shortest path、turnout allocation、overspeed grace/epoch；
- domain API：Line/Station/Depot validation、atomic revision/allocation、stable ordering；
- timetable：period/step/action ordering、route and berth resolution、provider missing/error、dispatcher recovery/simulation；
- persistence：fixed malformed/old fixture、unknown/version rejection、orphan reference、capture/materialize round-trip。

禁止仅通过测试中的同一 helper 同时生成 expected 与 actual；关键 serialized token/字段应有手写 fixture 或明确字符串断言。

## 验证命令

```bash
./gradlew :Basic:test
./gradlew :Basic:dokkaGeneratePublicationHtml
```

当 contract 被 Bukkit/WebUI 消费时，再运行：

```bash
./gradlew :BukkitPlugin:test :WebUI:test
npm --prefix WebUI/frontend test
```
