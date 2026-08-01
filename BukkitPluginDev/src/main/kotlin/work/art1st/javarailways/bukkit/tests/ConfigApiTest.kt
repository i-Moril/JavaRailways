package work.art1st.javarailways.bukkit.tests

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import work.art1st.javarailways.basic.api.ui.config.*
import work.art1st.javarailways.basic.impl.config.ConfigUIRegistry
import work.art1st.javarailways.bukkit.ui.dialog.DialogRenderer

/**
 * ConfigAPI 测试命令: /jrdev configtest
 *
 * 演示全部 4 种组合机制:
 * 1. 层级组合 - ConfigGroup 嵌套
 * 2. 扩展点注册 - registerToGroup
 * 3. 条件可见 - visibleWhen
 * 4. 预渲染事件 - ConfigGroupBuildEvent
 */
class ConfigApiTest : BasicCommand {

    companion object {
        lateinit var INSTANCE: ConfigApiTest
    }

    private val configUI = ConfigUIRegistry()
    private val renderer = DialogRenderer(configUI)
    private var initialized = false

    /**
     * 注册测试用的配置分组和项，展示全部 4 种组合机制。
     */
    fun initialize() {
        if (initialized) return
        initialized = true

        // ═══════════════════════════════════════
        // 定义 Keys
        // ═══════════════════════════════════════

        val advancedKey = ConfigKey.build<Boolean>("rail.advanced") {
            label = "高级模式"
            description = "启用高级轨道参数配置"
            defaultValue(false)
            valueType = ConfigValueType.BOOLEAN
            group = "rail.draw"
            order = 10
        }

        val radiusKey = ConfigKey.build<Double>("rail.radius") {
            label = "曲线半径"
            description = "弯曲半径，越大越平缓"
            defaultValue(300.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(10.0, 10000.0, 1.0, "m")
            group = "rail.draw"
            order = 0
        }

        val lengthKey = ConfigKey.build<Double>("rail.length") {
            label = "轨道长度"
            defaultValue(50.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(1.0, 500.0, 1.0, "m")
            group = "rail.draw"
            order = 1
        }

        val designSpeedKey = ConfigKey.build<Double>("rail.designSpeed") {
            label = "设计速度"
            description = "用于计算超高"
            defaultValue(80.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 350.0, 5.0, "km/h")
            group = "rail.draw"
            order = 2
        }

        // 依赖项: 仅在高级模式开启时可见
        val superelevationKey = ConfigKey.build<Double>("rail.superelevation") {
            label = "超高"
            description = "外轨超高值"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 180.0, 5.0, "mm")
            group = "rail.draw"
            order = 20
            dependsOn = listOf("rail.advanced")
            visibleWhen = { it.get<Boolean>("rail.advanced") == true }
        }

        val clothoidKey = ConfigKey.build<Double>("rail.clothoidLen") {
            label = "缓和曲线长"
            description = "默认自动 = R/9"
            defaultValue(33.3)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 200.0, 1.0, "m")
            group = "rail.draw"
            order = 21
            dependsOn = listOf("rail.advanced")
            visibleWhen = { it.get<Boolean>("rail.advanced") == true }
        }

        val transitionKey = ConfigKey.build<SuperelevationTransition>("rail.transition") {
            label = "超高过渡方式"
            defaultValue(SuperelevationTransition.SINE_KLEIN)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(
                options = listOf(SuperelevationTransition.SINE_KLEIN, SuperelevationTransition.LINEAR),
                displayNames = mapOf(
                    SuperelevationTransition.SINE_KLEIN to "Sine/Klein 过渡（平滑）",
                    SuperelevationTransition.LINEAR to "线性过渡（简单）"
                )
            )
            group = "rail.draw"
            order = 22
            dependsOn = listOf("rail.advanced")
            visibleWhen = { it.get<Boolean>("rail.advanced") == true }
        }

        // ═══════════════════════════════════════
        // 方式 1: 层级组合
        // ═══════════════════════════════════════
        val advancedGroup = ConfigGroup(
            id = "rail.advanced_opts",
            label = "高级选项",
            visibleWhen = { it.get<Boolean>("rail.advanced") == true }
        ).apply {
            items.add(ConfigItemImpl(superelevationKey))
            items.add(ConfigItemImpl(clothoidKey))
            items.add(ConfigItemImpl(transitionKey))
        }

        val railGroup = ConfigGroup(
            id = "rail.draw",
            label = "轨道绘制",
            description = "配置轨道绘制参数"
        ).apply {
            items.add(ConfigItemImpl(radiusKey))
            items.add(ConfigItemImpl(lengthKey))
            items.add(ConfigItemImpl(designSpeedKey))
            items.add(ConfigItemImpl(advancedKey))
            subGroups.add(advancedGroup)
        }
        configUI.register(railGroup)

        // ═══════════════════════════════════════
        // 方式 2: 扩展点注册 - 模拟"信号插件"贡献
        // ═══════════════════════════════════════
        val signalKey = ConfigKey.build<Double>("signal.distance") {
            label = "信号间距"
            description = "自动信号机间距"
            defaultValue(200.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(50.0, 1000.0, 10.0, "m")
            group = "rail.draw"
            order = 100
        }
        configUI.registerToGroup("rail.draw", ConfigItemImpl(signalKey))

        // 模拟: 第二个外部插件贡献"自动闭塞"选项
        val autoBlockKey = ConfigKey.build<Boolean>("signal.autoBlock") {
            label = "自动闭塞"
            description = "启用自动闭塞信号系统"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "rail.draw"
            order = 101
        }
        configUI.registerToGroup("rail.draw", ConfigItemImpl(autoBlockKey))

        // ═══════════════════════════════════════
        // 方式 4: 预渲染事件 - 权限门控
        // ═══════════════════════════════════════
        configUI.addBuildListener("rail.draw") { event ->
            // 模拟: 仅 OP 玩家看到"装饰模式"选项
            val player = org.bukkit.Bukkit.getPlayer(event.playerId)
            if (player != null && player.isOp) {
                event.addItem(ConfigItemImpl(ConfigKey.build<Boolean>("rail.decorative") {
                    label = "装饰模式"
                    description = "OP 专属: 启用装饰轨道"
                    defaultValue(false)
                    valueType = ConfigValueType.BOOLEAN
                    group = "rail.draw"
                    order = 200
                }))
            }
        }
    }

    // ═══════════════════════════════════════════
    // 命令处理
    // ═══════════════════════════════════════════

    override fun execute(ctx: CommandSourceStack, args: Array<out String>) {
        val sender = ctx.sender
        if (sender !is Player) {
            sender.sendMessage("Player only")
            return
        }

        when (args.firstOrNull()) {
            "open" -> {
                val groupId = args.getOrNull(1) ?: "rail.draw"
                initialize()
                renderer.showConfigDialog(sender, groupId)
            }
            "list" -> {
                initialize()
                sender.sendMessage(Component.text("=== 已注册的配置分组 ===", NamedTextColor.GOLD))
                configUI.getAllGroups().forEach { group ->
                    sender.sendMessage(
                        Component.text("  ${group.id}", NamedTextColor.YELLOW)
                            .append(Component.text(" - ${group.label}", NamedTextColor.WHITE))
                            .append(Component.text(" (${group.items.size} items, ${group.subGroups.size} subGroups)", NamedTextColor.GRAY))
                    )
                }
            }
            "info" -> {
                initialize()
                val groupId = args.getOrNull(1) ?: "rail.draw"
                val group = configUI.getGroup(groupId)
                if (group == null) {
                    sender.sendMessage(Component.text("分组不存在: $groupId", NamedTextColor.RED))
                    return
                }
                val items = configUI.getEffectiveItems(groupId)
                sender.sendMessage(Component.text("=== $groupId (${items.size} effective items) ===", NamedTextColor.GOLD))
                items.sortedBy { it.key.order }.forEach { item ->
                    val value = configUI.get(item.key as ConfigKey<Any?>) ?: item.key.defaultValue
                    sender.sendMessage(
                        Component.text("  [${item.key.order}]", NamedTextColor.DARK_GRAY)
                            .append(Component.text(" ${item.key.id}", NamedTextColor.AQUA))
                            .append(Component.text(" = $value", NamedTextColor.WHITE))
                            .append(Component.text(" (${item.key.valueType})", NamedTextColor.GRAY))
                    )
                }
            }
            "set" -> {
                initialize()
                val keyId = args.getOrNull(1) ?: run {
                    sender.sendMessage("用法: /jrdev configtest set <keyId> <value>")
                    return
                }
                val valueStr = args.getOrNull(2) ?: run {
                    sender.sendMessage("用法: /jrdev configtest set <keyId> <value>")
                    return
                }
                val item = configUI.getItem(keyId)
                if (item == null) {
                    sender.sendMessage(Component.text("配置项不存在: $keyId", NamedTextColor.RED))
                    return
                }
                setConfigValue(item, valueStr)
                sender.sendMessage(Component.text("已设置 $keyId = $valueStr", NamedTextColor.GREEN))
            }
            else -> {
                sender.sendMessage(Component.text("=== ConfigAPI 测试命令 ===", NamedTextColor.GOLD))
                sender.sendMessage(Component.text("/jrdev configtest open [groupId]", NamedTextColor.YELLOW))
                sender.sendMessage(Component.text("/jrdev configtest list", NamedTextColor.YELLOW))
                sender.sendMessage(Component.text("/jrdev configtest info [groupId]", NamedTextColor.YELLOW))
                sender.sendMessage(Component.text("/jrdev configtest set <keyId> <value>", NamedTextColor.YELLOW))
            }
        }
    }

    /**
     * 按 keyId 设置配置值（支持类型转换）。
     */
    private fun setConfigValue(item: ConfigItem, valueStr: String) {
        val key = item.key
        when (key.valueType) {
            ConfigValueType.BOOLEAN -> {
                val value = valueStr.toBooleanStrictOrNull() ?: return
                @Suppress("UNCHECKED_CAST")
                configUI.setValue(key as ConfigKey<Boolean>, value)
            }
            ConfigValueType.NUMBER -> {
                val value = valueStr.toDoubleOrNull() ?: return
                @Suppress("UNCHECKED_CAST")
                configUI.setValue(key as ConfigKey<Double>, value)
            }
            ConfigValueType.INTEGER -> {
                val value = valueStr.toIntOrNull() ?: return
                @Suppress("UNCHECKED_CAST")
                configUI.setValue(key as ConfigKey<Int>, value)
            }
            ConfigValueType.STRING -> {
                @Suppress("UNCHECKED_CAST")
                configUI.setValue(key as ConfigKey<String>, valueStr)
            }
            ConfigValueType.ENUM -> {
                // Try to find matching enum option
                val constraints = key.constraints as? ConfigConstraints.EnumOptions<*>
                val option = constraints?.options?.find { it.name.equals(valueStr, ignoreCase = true) }
                if (option != null) {
                    @Suppress("UNCHECKED_CAST")
                    configUI.setValue(key as ConfigKey<Any?>, option)
                }
            }
            ConfigValueType.VECTOR3D -> {
                // 格式: x,y,z
                val parts = valueStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                if (parts.size == 3) {
                    @Suppress("UNCHECKED_CAST")
                    configUI.setValue(key as ConfigKey<org.joml.Vector3d>, org.joml.Vector3d(parts[0], parts[1], parts[2]))
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 测试用枚举
    // ═══════════════════════════════════════════

    enum class SuperelevationTransition {
        SINE_KLEIN,
        LINEAR
    }
}
