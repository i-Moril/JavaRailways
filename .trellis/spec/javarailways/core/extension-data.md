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
| Car/Bogie | 保存extension map | host显式初始化并聚合extension destroy完成结果 |
| World | 不保存container | Basic/Bukkit sidecar owner |
| Rail | 不保存container | rail owner；`CoreRail.destroy`聚合signals及render extension清理 |
| Signal | 不持久化 | runtime materializer |

Composition顺序：register slots → create/restore host → attach data → explicit initialize/restore → first wake。missing required data必须fail-fast，不在production accessor隐式new。

## 已注册payload的持久化边界（CURRENT）

Core的`SavedTrainV1.extension`、`CarConfigV1.extension`和`SavedBogieV1.extension`显式绑定同一个`ExtensionDataMapSerializer`。
它保留map中的extension owner key，只把每个值写成`{typeId,schemaVersion,payload}`；不使用默认多态类名、不影响curve/config的其他discriminator。
`SerializableExtensionData`仍是payload基类，Core不依赖Bukkit具体DTO。

`ExtensionDataTypeRegistry`的公开启动注册方法签名为，Core持久化map使用其`global`实例：

```kotlin
fun <T : SerializableExtensionData> register(
    typeId: String, schemaVersion: Int, payloadClass: KClass<T>, serializer: KSerializer<T>
)
```

`T : SerializableExtensionData`。调用方显式提供生成的serializer，typeId为小写namespaced ID、version为正整数。
注册表用ID与exact class两份映射；注册在同一monitor内构造新的不可变映射组合，再通过一个volatile引用发布。
读写payload各捕获一个条目，不扫描classpath或执行反射；完全相同的重复注册幂等，同一ID/class的不同映射或版本冲突拒绝。
它是启动配置，不提供运行时热发现、unregister或测试专用global clear；隔离测试可创建自己的registry。

正常保存按值的exact class查serializer，先编码payload再组装envelope；恢复先严格检查envelope只有三个字段，验证已注册typeId及exact schemaVersion，才调用payload decoder。
未知类写入、未知ID/版本读取、字段缺失或多余均失败，不忽略、不自动创建extension。payload本身继续遵守调用方strict Json。
例如未知schemaVersion即使包含看似合法payload，也不能调用其业务decoder；正常嵌套train/car/bogie map都必须经过该边界。

Bukkit的`BukkitExtensionDataSerializers.register()`在onEnable恢复world前显式注册
`javarailways:car_render_state`与`javarailways:train_render_state`，当前均为1，codec测试复用该入口。
本次不升级Core infrastructure2/runtime1或其他边界，不保留FQCN reader；Train render body已有version字段暂保留，不因此扩大清理范围。
每项payload查询为map查找，编码成本随payload长度增长；注册时复制当前少量注册项，仅发生于启动，不进入physics tick。

旧开发数据只由独立`scripts/migrate_extension_envelopes.py`的明确allowlist转换，默认dry-run，显式输出到全新目录并报告路径、hash和计数。
未知旧类型拒绝；该脚本不等于完整旧世界格式/曲线迁移，也不打入JAR。
必需测试是固定current envelope、嵌套三类map、未知类/ID/版本、冲突注册、strict字段，以及根版本或payload版本错误时decoder未调用；离线旧fixture与真实空记录样本分别报告。
不要重新引入`Class.forName(savedType)`来“兼容”；正确做法是启动注册current类型，旧文件在离线边界转换。

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
