# Decorations backend

适用于独立 Decorations Gradle 模块。公开宿主 API 在 `work.art1st.javarailways.bukkit.api.model.extension`；禁止链接 host config/model/render 私有实现。

CURRENT：Decorations hard-depend JavaRailways，soft-depend SimpleDisplays。普通 JAR 不打入宿主、SimpleDisplays API、Kotlin/serialization/YAML runtime。`verifyPluginJar` 检查 descriptor 与 owner class 边界。只实现 display，不迁移 vehicle collision platform 或普通 item_display pantograph。

`DecorationsPlugin` 先发现可选服务，再注册 `decorations:display` provider，检查已有包失败并记录诊断；disable 先关闭注册，再关闭可选服务连接。DisplayPartConfig 严格校验 category/interior/width/height，payload schema-version 为 1。

manifest 的 `extensions.decorations:display.directory: decorations` 捕获目录下 `displays.yml` 与 `displays/*.xml`。DisplayResourceParser 只读不可变捕获 map，无磁盘 IO；严格拒绝重复键、未知字段、非法相对路径、无效 UTF-8（宿主捕获）、坏引用。templates/defaults 结构不变，模板 file 相对扩展目录。XML/JS 原文保留；可用的 SimpleDisplays 在 provider 校验中编译全部模板，拒绝坏 XML/布局/JS 语法，但不执行 JS。无可选服务时继续仅元数据校验。

OptionalSimpleDisplays 保持 API-free 加载边界：仅启用的命名 SimpleDisplays 插件存在时延迟加载 typed binding。binding 验证 Bukkit service owner 和 API 类身份。缺失/不兼容不阻止 Decorations 或列车加载。provider disable/service unregister/Decorations disable 释放连接和 listener；不自动重连，替换后需重启 Decorations。缺失 API 的独立 classloader 测试不能用 null service 替代。

本批不定义 runtime mount factory、backend text handle 或 no-op renderer，不宣称已显示屏幕。后续 actual rendering 必须遵循宿主 Folia ownership，并复用 native/EntityLib 后端生命周期。测试命令：`:Decorations:test :Decorations:verifyPluginJar`；宿主改动另运行 BukkitPlugin 测试/打包。

定义及资源 schema 位于 Decorations/schemas，使用示例/构建说明见 Decorations/README.md。

## 模板编译缓存（CURRENT）

`OptionalSimpleDisplays.validateTemplates(String, DisplayResources)` 保持 API-free；实际 typed binding 通过 `DisplayService.compile` 编译，ABI 必须支持 `DisplayTemplate : AutoCloseable`。当前独立 API 基线 410af2e，不能只凭 SNAPSHOT 坐标推测产物版本。

每连接最多 64 项 exact-source LRU，当前无 icons 声明，源码相同才复用。缓存只记忆校验，不按 package-name 发布 active 状态，不供挂载直接持有；未来渲染必须先定义 lease/lifetime。坏候选的部分成功编译可留在有界缓存，不改变已发布 snapshot。重复并发编译关闭 loser，退休后完成的对象关闭且不能安装。compile 和 close 都不能在 cache lock 下执行。

close/eviction 逐一尝试释放并聚合报告，不能因一个失败跳过后续对象，也不能关闭共享 DisplayService。service unregister、provider disable、Decorations disable 均清空缓存/引用。连接跨 reload worker/生命周期线程读取使用明确可见性。异常保留 package/template/file 和真实 cause；编译错误不能伪装成缺少服务。定向回归覆盖有界/LRU/重试/部分成功、并发 duplicate/retire、任意 callback 不持锁、API 缺失的新增入口和真实 Paper 语法拒绝/保留旧快照。
