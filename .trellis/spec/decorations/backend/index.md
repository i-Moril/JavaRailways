# Decorations backend

适用于独立 Decorations Gradle 模块。公开宿主 API 在 `work.art1st.javarailways.bukkit.api.model.extension`；禁止链接 host config/model/render 私有实现。

CURRENT：Decorations hard-depend JavaRailways，soft-depend SimpleDisplays。普通 JAR 不打入宿主、SimpleDisplays API、Kotlin/serialization/YAML runtime。`verifyPluginJar` 检查 descriptor 与 owner class 边界。只实现 display，不迁移 vehicle collision platform 或普通 item_display pantograph。

`DecorationsPlugin` 注册 `decorations:display` provider，检查已有包失败并记录诊断；disable 先关闭注册，再关闭可选服务连接。DisplayPartConfig 严格校验 category/interior/width/height，payload schema-version 为 1。

manifest 的 `extensions.decorations:display.directory: decorations` 捕获目录下 `displays.yml` 与 `displays/*.xml`。DisplayResourceParser 只读不可变捕获 map，无磁盘 IO；严格拒绝重复键、未知字段、非法相对路径、无效 UTF-8（宿主捕获）、坏引用。templates/defaults 结构不变，模板 file 相对扩展目录。XML/JS 原文保留但尚不编译。

OptionalSimpleDisplays 保持 API-free 加载边界：仅启用的命名 SimpleDisplays 插件存在时延迟加载 typed binding。binding 验证 Bukkit service owner 和 API 类身份。缺失/不兼容不阻止 Decorations 或列车加载。provider disable/service unregister/Decorations disable 释放连接和 listener；不自动重连，替换后需重启 Decorations。缺失 API 的独立 classloader 测试不能用 null service 替代。

本批不定义 runtime mount factory、backend text handle 或 no-op renderer，不宣称已显示屏幕。后续 actual rendering 必须遵循宿主 Folia ownership，并复用 native/EntityLib 后端生命周期。测试命令：`:Decorations:test :Decorations:verifyPluginJar`；宿主改动另运行 BukkitPlugin 测试/打包。

定义及资源 schema 位于 Decorations/schemas，使用示例/构建说明见 Decorations/README.md。
