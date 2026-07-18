package work.art1st.mineRails.bukkit.ui.dialog

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import work.art1st.mineRails.basic.api.ui.config.*

/**
 * ConfigAPI Dialog 渲染后端。
 *
 * 使用 Paper 1.21+ 原生 [Dialog] API 渲染配置界面。
 *
 * ## 映射
 * | ConfigValueType | DialogInput |
 * |-----------------|-------------|
 * | BOOLEAN | [DialogInput.bool] |
 * | NUMBER | [DialogInput.numberRange] |
 * | INTEGER | [DialogInput.numberRange] + step=1 |
 * | STRING | [DialogInput.text] |
 * | ENUM | [DialogInput.singleOption] |
 * | VECTOR3D | 3 个 [DialogInput.numberRange] (X/Y/Z) |
 *
 * ## 条件可见
 * 所有项始终包含在 Dialog 中（Paper Dialog 不支持动态显隐）。
 * [ConfigKey.visibleWhen] 仅在首次 [buildGroupForPlayer] 时过滤。
 * 若需运行时联动，提交后重建 Dialog 重开。
 */
class DialogRenderer(
    private val configUI: ConfigUI
) {
    companion object {
        /** ClickCallback 默认生命周期 */
        private val DEFAULT_LIFETIME = ClickCallback.DEFAULT_LIFETIME
        private val DEFAULT_TEXT_RESOLVER: (String) -> String = { key ->
            when (key) {
                "dialog.confirm" -> "✓ 确认"
                "dialog.enabled" -> "开启"
                "dialog.disabled" -> "关闭"
                else -> key
            }
        }

        /**
         * 将 [ConfigKey.id] 转换为合法的 Dialog input key。
         * Paper Dialog API 要求 key 仅含 `[a-zA-Z0-9_]+`，不允许 `.`。
         */
        private fun toInputKey(configKeyId: String): String = configKeyId.replace('.', '_')
    }

    data class MenuEntry(val label: String, val onSelected: (Player) -> Unit)

    fun showMenu(player: Player, title: String, description: String, entries: List<MenuEntry>) {
        val buttons = entries.map { entry ->
            val action = DialogAction.customClick(
                { _, audience -> if (audience is Player) entry.onSelected(audience) },
                ClickCallback.Options.builder().uses(1).lifetime(DEFAULT_LIFETIME).build()
            )
            ActionButton.builder(Component.text(entry.label, NamedTextColor.YELLOW))
                .action(action)
                .build()
        }
        val bodies = listOf(DialogBody.plainMessage(Component.text(description, NamedTextColor.GRAY)))
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                        .body(bodies)
                        .canCloseWithEscape(true)
                        .build()
                )
                .type(DialogType.multiAction(buttons, null, 1))
        }
        player.showDialog(dialog)
    }

    /**
     * 向玩家展示配置分组的原生 Dialog。
     */
    fun showConfigDialog(
        player: Player,
        groupId: String,
        context: Map<String, Any> = emptyMap(),
        textResolver: (String) -> String = DEFAULT_TEXT_RESOLVER,
        onConfirmed: ((Player) -> Unit)? = null
    ) {
        val session = configUI.getPlayerSession(player.uniqueId)
        val builtGroup = configUI.buildGroupForPlayer(groupId, player.uniqueId, context)
        showNativeDialog(player, builtGroup, session, textResolver, onConfirmed)
    }

    /**
     * 构建并展示 Paper 原生 Dialog。
     */
    private fun showNativeDialog(
        player: Player,
        builtGroup: BuiltGroup,
        session: PlayerConfigSession,
        textResolver: (String) -> String,
        onConfirmed: ((Player) -> Unit)? = null
    ) {
        val group = builtGroup.group

        // 构建 DialogInput 列表
        val inputs = buildInputs(builtGroup, session, textResolver)

        // 构建 body（描述文本）
        val bodies = mutableListOf<DialogBody>()
        if (group.description.isNotBlank()) {
            bodies.add(DialogBody.plainMessage(Component.text(textResolver(group.description), NamedTextColor.GRAY)))
        }

        // 子分组作为 body section 标题
        builtGroup.effectiveSubGroups.forEach { sub ->
            if (sub.items.isNotEmpty()) {
                bodies.add(DialogBody.plainMessage(
                    Component.text("▸ ${textResolver(sub.label)}", NamedTextColor.YELLOW)
                ))
            }
        }

        // 确认按钮回调
        val confirmAction = DialogAction.customClick(
            { view, audience ->
                if (audience is Player) {
                    onConfirm(view, builtGroup, session, audience, textResolver, onConfirmed)
                }
            },
            ClickCallback.Options.builder()
                .uses(1)
                .lifetime(DEFAULT_LIFETIME)
                .build()
        )

        val confirmButton = ActionButton.builder(
            Component.text(textResolver("dialog.confirm"), NamedTextColor.GREEN)
        )
            .action(confirmAction)
            .build()

        // 构建 Dialog
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(Component.text(textResolver(group.label), NamedTextColor.GOLD))
                        .body(bodies)
                        .inputs(inputs)
                        .canCloseWithEscape(true)
                        .build()
                )
                .type(DialogType.notice(confirmButton))
        }

        player.showDialog(dialog)
    }

    /**
     * 处理确认按钮回调：读取所有 Dialog 输入值并写入 session。
     */
    private fun onConfirm(
        view: io.papermc.paper.dialog.DialogResponseView,
        builtGroup: BuiltGroup,
        session: PlayerConfigSession,
        player: Player,
        textResolver: (String) -> String,
        onConfirmed: ((Player) -> Unit)? = null
    ) {
        val allItems = builtGroup.effectiveItems +
            builtGroup.effectiveSubGroups.flatMap { it.items }

        var needsRebuild = false

        allItems.forEach { item ->
            val key = item.key
            val inputKey = toInputKey(key.id)
            val oldValue = session.getValue(key as ConfigKey<Any?>)

            when (key.valueType) {
                ConfigValueType.BOOLEAN -> {
                    val value = view.getBoolean(inputKey)
                    if (value != null) {
                        @Suppress("UNCHECKED_CAST")
                        session.setValue(key as ConfigKey<Boolean>, value)
                    }
                }
                ConfigValueType.NUMBER -> {
                    val value = view.getFloat(inputKey)
                    if (value != null) {
                        @Suppress("UNCHECKED_CAST")
                        session.setValue(key as ConfigKey<Double>, value.toDouble())
                    }
                }
                ConfigValueType.INTEGER -> {
                    val value = view.getFloat(inputKey)
                    if (value != null) {
                        @Suppress("UNCHECKED_CAST")
                        session.setValue(key as ConfigKey<Int>, value.toInt())
                    }
                }
                ConfigValueType.STRING -> {
                    val value = view.getText(inputKey)
                    if (value != null) {
                        @Suppress("UNCHECKED_CAST")
                        session.setValue(key as ConfigKey<String>, value)
                    }
                }
                ConfigValueType.ENUM -> {
                    val optionId = view.getText(inputKey)
                    if (optionId != null) {
                        val constraints = key.constraints as? ConfigConstraints.EnumOptions<*>
                        val enumValue = constraints?.options?.find {
                            it.name == optionId
                        }
                        if (enumValue != null) {
                            @Suppress("UNCHECKED_CAST")
                            session.setValue(key as ConfigKey<Any?>, enumValue)
                        }
                    }
                }
                ConfigValueType.VECTOR3D -> {
                    val x = view.getFloat(toInputKey("${key.id}.x"))
                    val y = view.getFloat(toInputKey("${key.id}.y"))
                    val z = view.getFloat(toInputKey("${key.id}.z"))
                    if (x != null && y != null && z != null) {
                        val vec = org.joml.Vector3d(x.toDouble(), y.toDouble(), z.toDouble())
                        @Suppress("UNCHECKED_CAST")
                        session.setValue(key as ConfigKey<org.joml.Vector3d>, vec)
                    }
                }
            }

            // 检查是否修改了触发键（使用原始 key.id）
            val newValue = session.getValue(key as ConfigKey<Any?>)
            if (oldValue != newValue && key.id in builtGroup.triggerKeyIds) {
                needsRebuild = true
            }
        }

        // 提交；触发键变化时先重建 Dialog，最终确认时才调用业务回调。
        session.commit()
        if (needsRebuild) {
            val newGroup = configUI.buildGroupForPlayer(
                builtGroup.group.id, player.uniqueId, emptyMap()
            )
            showNativeDialog(
                player,
                newGroup,
                configUI.getPlayerSession(player.uniqueId),
                textResolver,
                onConfirmed
            )
            return
        }

        onConfirmed?.invoke(player)
    }

    /**
     * 将 [BuiltGroup] 中的配置项转换为 [DialogInput] 列表。
     */
    private fun buildInputs(
        builtGroup: BuiltGroup,
        session: PlayerConfigSession,
        textResolver: (String) -> String
    ): List<DialogInput> {
        val allItems = builtGroup.effectiveItems +
            builtGroup.effectiveSubGroups.flatMap { it.items }

        return allItems.mapNotNull { item -> toDialogInput(item, session, textResolver) }
    }

    /**
     * 将单个 [ConfigItem] 转换为 [DialogInput]。
     */
    @Suppress("UNCHECKED_CAST")
    private fun toDialogInput(
        item: ConfigItem,
        session: PlayerConfigSession,
        textResolver: (String) -> String
    ): DialogInput? {
        val key = item.key
        val current = session.getValue(key as ConfigKey<Any?>) ?: key.defaultValue

        val inputKey = toInputKey(key.id)

        return when (key.valueType) {
            ConfigValueType.BOOLEAN -> {
                DialogInput.bool(inputKey, Component.text(textResolver(key.label)))
                    .initial(current as? Boolean ?: false)
                    .onTrue(textResolver("dialog.enabled"))
                    .onFalse(textResolver("dialog.disabled"))
                    .build()
            }
            ConfigValueType.NUMBER -> {
                val constraints = key.constraints as? ConfigConstraints.NumberRange
                val min = constraints?.min?.toFloat() ?: 0f
                val max = constraints?.max?.toFloat() ?: 100f
                val step = constraints?.step?.toFloat() ?: 1f
                val value = (current as? Double)?.toFloat() ?: min
                DialogInput.numberRange(inputKey, Component.text(textResolver(key.label)), min, max)
                    .step(step)
                    .initial(value)
                    .build()
            }
            ConfigValueType.INTEGER -> {
                val constraints = key.constraints as? ConfigConstraints.IntRange
                val min = constraints?.min?.toFloat() ?: 0f
                val max = constraints?.max?.toFloat() ?: 100f
                val value = (current as? Int)?.toFloat() ?: min
                DialogInput.numberRange(inputKey, Component.text(textResolver(key.label)), min, max)
                    .step(1f)
                    .initial(value)
                    .build()
            }
            ConfigValueType.STRING -> {
                val constraints = key.constraints as? ConfigConstraints.StringLength
                val value = (current as? String) ?: ""
                val builder = DialogInput.text(inputKey, Component.text(textResolver(key.label)))
                    .initial(value)
                if (constraints != null) {
                    builder.maxLength(constraints.maxLength)
                }
                builder.build()
            }
            ConfigValueType.ENUM -> {
                val constraints = key.constraints as? ConfigConstraints.EnumOptions<Enum<*>>
                    ?: return null
                val currentEnum = current as? Enum<*>
                val options = constraints.options.map { option ->
                    val displayName = textResolver(constraints.displayName(option))
                    SingleOptionDialogInput.OptionEntry.create(
                        option.name,                  // id (used as return value)
                        Component.text(displayName),   // display text
                        option == currentEnum          // initial selection
                    )
                }
                DialogInput.singleOption(
                    inputKey,
                    Component.text(textResolver(key.label)),
                    options
                ).build()
            }
            ConfigValueType.VECTOR3D -> {
                // 不渲染 VECTOR3D 为单一 input；需要外部单独处理
                null
            }
        }
    }
}
