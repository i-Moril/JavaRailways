# SimpleATOBukkit Backend 规范

## 状态与范围

`SimpleATOBukkit`是`CURRENT/TRANSITIONAL`的独立Gradle源码模块，负责平台无关`SimpleATO`
与Paper/Folia主插件之间的适配。它作为library被`BukkitPlugin`依赖并合入JavaRailways主插件
`shadowJar`，不是第二个可安装插件：不得包含`plugin.yml`、继承`JavaPlugin`、声明JavaRailways
plugin dependency，或通过Bukkit `ServicesManager`反向发现同一主插件。

具体信号制式仍由独立附属插件拥有。`SimpleATOBukkit`不得拥有`SimpleSignals`的固定闭塞、
保护区域、联锁规则、aspect/model vocabulary、renderer或可选JS/YAML语义。通用车辆渲染、
Folia调度helper和面向所有附属插件的平台facade也不属于本模块；前两者留在`BukkitPlugin`，
后者只有在出现明确的跨插件需求后才能作为`BukkitPlugin`公开API单独设计。

## 工作方式

正常启动链只有一个Bukkit生命周期：

```text
BukkitPlugin.onEnable
  -> SimpleATOBukkitModule.install()
  -> 创建SimpleATOPlugin内部扩展身份
  -> 注册到Core PluginRegistry
  -> BukkitPlugin继续创建各world的SimpleATOWorldData
```

`SimpleATOPlugin`只是Core扩展槽身份，不是Bukkit插件。`PluginRegistry`在同一JVM生命周期重启时
按实现类复用slot并替换旧实例，因此adapter每次安装创建当前实例，然后把完全相同的实例同时
发布给`SimpleATOPlugin.INSTANCE`和registry。任何Core world创建都必须发生在此步骤之后。

当前模块还拥有SimpleATO的ATO stall与Depot reset诊断文件适配。它们分别消费SimpleATO拥有的
`AtoStallReport`与`DepotResetReport`，把报告写入主插件提供的数据目录。world composition、通用车辆
渲染、最终启动/停止顺序、RailWand和手动驾驶仍由`BukkitPlugin`拥有。

## 运营目标编辑适配

当前 `tool/RailObjectDestinationEditor` 拥有 RailObjectWand 中 Destination、Station berth 和 Depot point 的
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
迁移只调整源码归属，PDC 状态、配置组/key token、数据格式和版本不变；不得保留宿主中的第二份编辑实现或反向依赖 BukkitPlugin。

## 依赖与打包不变量

- `SimpleATOBukkit`可依赖`SimpleATO`以及实际使用的Core/Basic/Paper能力；运营编辑器使用Basic已有的通用UI、WayPoint与world data，不另造模型或store。不得为了预留未来功能加入未使用的
  platform dependency，也不得依赖`BukkitPlugin`，否则形成
  `BukkitPlugin -> SimpleATOBukkit -> BukkitPlugin`循环。
- `BukkitPlugin`使用`implementation(project(":SimpleATOBukkit"))`，最终主插件JAR必须包含
  `SimpleATOBukkitModule.class`。
- 模块不应用Bukkit resource-factory插件，也不提供`plugin.yml`；它只是主插件的library dependency。
- 不上传或部署`SimpleATOBukkit`为服务器插件。独立附属插件只依赖JavaRailways最终主插件公开API。
- 本模块使用JVM 25，与Paper/Folia host target一致；平台无关`SimpleATO`继续使用JVM 17。

## 开发前检查

- [ ] 新代码是否确实需要Bukkit/Folia、主插件数据目录或platform dispatch；纯运营规则留在`SimpleATO`。
- [ ] 是否能由`BukkitPlugin`直接构造并传入依赖，而不是新增内部service discovery。
- [ ] 是否保持单一`JavaPlugin`生命周期和单一部署JAR。
- [ ] target-aware side effect是否回到location/entity owner；纯IO是否避开simulation owner热路径。
- [ ] 是否有真实SimpleATO producer/consumer；通用Bukkit工具和独立信号插件功能不得为了填充模块而迁入。

## 质量检查

```bash
./gradlew :SimpleATOBukkit:test
./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar
./gradlew verifySignalModuleBoundaries
python3 ./.trellis/scripts/get_context.py --mode packages
git diff --check
```

确认`BukkitPlugin`仍以`implementation(project(":SimpleATOBukkit"))`依赖本模块，仓库没有第二个
SimpleATO Bukkit descriptor、`JavaPlugin`入口或独立部署artifact配置。根级门禁读取实际 adapter/main JAR
以及直接项目依赖，自动确认 adapter 没有 descriptor、`JavaPlugin`/`ServicesManager`或具体信号插件引用，
并确认主插件 JAR 已嵌入`SimpleATOBukkitModule.class`而仍只有 JavaRailways descriptor。
