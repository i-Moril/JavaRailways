# SimpleDisplays 可选服务接入

## 状态、范围与 owner

CURRENT：`BukkitPlugin` 负责独立 SimpleDisplays 插件的可选服务发现和连接退休。
本阶段仅建立 API 边界；车包屏幕、样式编译缓存、列车数据投影和显示实体仍是后续任务。

## 正常启动与内部机制

`BukkitPlugin.enablePlugin` 在加载模型前创建 `OptionalSimpleDisplays` 并调用 `start()`。
加载器先查找名称为 `SimpleDisplays` 的已启用插件，再通过固定代码字符串加载宿主的
`SimpleDisplaysBinding`，由后者向 Bukkit `ServicesManager` 取得 `DisplayService`。
它核对服务注册 owner 与该插件为同一实例，并验证当前 API 的必要方法和实现。
成功后保存连接、注册生命周期 listener；不编译 XML，不生成实体。

必须保留 API-free 加载边界：`OptionalSimpleDisplays`、`SimpleDisplaysConnection` 以及主插件
字段签名均不得引用 `simpledisplays.api.*`。仅延迟加载的 binding 依赖 API 类型，避免没有插件时
JVM 在加载主插件或 listener 时解析缺失类。反射目标是宿主固定类名，不接受配置或存档类名。

## 接口与构建契约

- `OptionalSimpleDisplays.start(): Unit` 执行一次启动发现；`close(): Unit` 清除连接并注销 listener。
- `SimpleDisplaysConnection.provider: Any?` 和 `serviceType: Class<*>` 仅供生命周期核对服务类型与 provider 身份，不是公开 add-on API。
- 当前外部 API：`DisplayService.compile(String, Map): DisplayTemplate`；模板提供宽高和 `draw(Map, Long)`。
- `simpledisplays:simpledisplays-api:0.1.0-SNAPSHOT` 为 compile-only 依赖，测试单独加入 API。
- generated `plugin.yml` 声明 `softdepend: SimpleDisplays`。API 类不能进入宿主 shadow JAR。
- 仓库路径及独立 API 发布步骤见`BukkitPlugin/docs/simpledisplays-integration.md`。不得以本地复制 API
  源码或打包整个 SimpleDisplays 实现代替独立 artifact。

## 生命周期与线程

发现和关闭属于插件 lifecycle，不访问 world/entity，不产生区域调度任务或异步线程。
`PluginDisableEvent` 只按提供插件的准确实例清除连接；`ServiceUnregisterEvent` 同时匹配当前服务类型与 provider
对象，避免同一对象注册其他接口时误退休有效连接。无关插件或服务事件不影响连接。主插件禁用时显式调用 `close()`。
退休必须同时清除 holder 和 binding 内的服务引用；先前取得的 binding 不能继续保留死 provider。
本阶段不支持热启用后的自动发现，也不保存连接状态或增加 schema version。

## 校验、失败与退化

缺少或未启用 SimpleDisplays 时记录提示，继续宿主启动且不解析 API。
插件存在但 API 类/方法不符、服务未注册或 owner 不符时记录包含原始原因的警告，保持无连接。
反射包装异常必须展开原因；不可吞掉 JVM fatal error。服务正常时不调用编译方法作探测，避免
在启动边界执行模板工作或引入 provider 副作用。

固定数量的插件/服务查询、成员校验和身份比较为 O(1)，仅启动时发生；事件处理与关闭也为 O(1)。
没有 tick 扫描、后台重试或兼容版本列表。不可用只关闭显示接入，不改变列车或世界运行状态。

## 场景与测试门禁

正常安装时发现同一 owner 的服务；未安装时宿主启动；无关服务注销保留连接；当前服务注销或
owner 停用后，holder 与之前的 binding 都不再持有 provider。API 不兼容时应有诊断且宿主可继续。

测试必须覆盖缺失 API 的独立 classloader（主动禁止加载 `simpledisplays.api.*`，不能仅让服务返回
null），正常发现、disabled/missing plugin、缺失服务、错误 owner、ABI/provider 不兼容、服务注销、
提供者停用和主插件关闭。运行 `:BukkitPlugin:test :BukkitPlugin:shadowJar`，检查 generated descriptor
和 shadow JAR 条目；真实 Paper/Folia 启动验收另记证据，不能用代理 server 的单测冒充启动成功。

## 后续与禁止替代

车载渲染后续沿用同一 owner 边界，届时再定义使用期间的同步和退休行为；当前没有异步 consumer。
不要在主插件添加 `DisplayService?` 字段，即使初始化为 null 也可能使缺失 API 的类加载失败；保持
API-free holder 和延迟 typed binding。不要把整个 provider JAR 加入 implementation 以使单测通过。
