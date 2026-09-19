# Core extension data 契约

## 适用场景与用途

触发：注册`Plugin`、attach host extension data、实现`Extension.initialize/save`，或演进extension persisted payload。

用途：区分runtime slot、host-local data与stable persisted identity，防止slot落盘、漏initialize、假设所有host自动save/destroy，或用JVM class name做协议ID。

## Owner、真实签名与三种identity

`PluginRegistry`是process-wide startup registry：同implementation class重注册复用slot并替换instance，新class追加；无unregister/discovery。`Plugin.identifier: Int`仅runtime list index。

```kotlin
interface Extension<T> {
    fun initialize(host: T, savedExtensionData: Map<String, SerializableExtensionData>)
    fun save(): Pair<String, SerializableExtensionData>?
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/util/plugin/Extension.kt`。

三种identity必须分离：

| identity | owner/lifetime | persistence |
| --- | --- | --- |
| `Plugin.identifier` slot | process startup | 禁止落盘 |
| `Extension.save().first` stable extension ID | extension owner | rename需migration |
| payload `typeId + schemaVersion + payload` | payload registry | namespaced、unknown fail |

`ExtensionDataContainer`：`getExtensionDataOrNull` missing→null；`requireExtensionData` missing→`IllegalStateException`含plugin class/slot；`registerExtensionData`只attach，不调用initialize；lookup unchecked cast的type safety由owner wrapper保证。

## Host save/lifecycle矩阵

| host | Core writer | initialize/destroy owner |
| --- | --- | --- |
| Train | 保存train extension map | composition attach/initialize；train destroy不统一destroy所有TrainExtension |
| Car/Bogie | 保存extension map | render trait destroy |
| World | 不保存container | Basic/Bukkit sidecar owner |
| Rail | 不保存container | rail owner；`CoreRail.destroy`仅signals |
| Signal | 不持久化 | runtime materializer |

Composition顺序：register slots → create/restore host → attach data → explicit initialize/restore → first wake。missing required data必须fail-fast，不在production accessor隐式new。

## 项目内正确示例

`PluginRegistry.register`使same class reload复用slot：

```kotlin
val existingIndex = _plugins.indexOfFirst { it::class == plugin::class }
if (existingIndex >= 0) {
    plugin.identifier = existingIndex
    _plugins[existingIndex] = plugin
    return
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/util/plugin/PluginRegistry.kt`。这仅稳定单进程host slot，不赋予跨重启identity。

## 错误矩阵

| 错误 | 必须行为 |
| --- | --- |
| host缺required extension | `requireExtensionData`抛错；host不继续启动 |
| duplicate/late plugin registration | same class替换；新host前完成；不对旧host假设backfill |
| unknown payload type/version | registry fail-fast |
| initialize触platform | owner切target scheduler后调用 |
| save返回null | 不写该extension payload |

## 禁止实现

- 保存integer slot或依赖固定0/1；
- attach后忘记`initialize`；
- 把unchecked generic container暴露成第三方type-safe API；
- 声称所有World/Rail/Train/Car extension自动save/destroy；
- 新payload写FQCN或按存档字符串`Class.forName`。

## 必需测试与验证

- `ExtensionDataContainerTest.kt`：slot expansion、missing nullable/required、same class reload、host isolation。
- host integration test：register→attach→initialize顺序，first wake前完成。
- persisted payload：stable ID、unknown type/version fail、fixed fixture、current writer。
- reload test不依赖global registry初始slot。

```bash
./gradlew :Core:test --tests '*ExtensionDataContainerTest' --tests '*WorldPersistenceRoundTripTest'
./gradlew :Basic:test :BukkitPlugin:test
```
