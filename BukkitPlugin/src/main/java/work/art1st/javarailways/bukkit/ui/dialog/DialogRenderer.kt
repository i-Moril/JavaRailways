package work.art1st.javarailways.bukkit.ui.dialog

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
import work.art1st.javarailways.basic.api.ui.config.*
import work.art1st.javarailways.bukkit.util.PlatformDispatcher

internal const val DIALOG_NUMBER_TEXT_MAX_LENGTH = 32

internal fun formatDialogNumberText(value: Double): String {
    require(value.isFinite()) { "Dialog number text value must be finite: $value" }
    val plain = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    if (plain.length <= DIALOG_NUMBER_TEXT_MAX_LENGTH) return plain
    return value.toString().also { rendered ->
        check(rendered.length <= DIALOG_NUMBER_TEXT_MAX_LENGTH) {
            "Dialog number text exceeds $DIALOG_NUMBER_TEXT_MAX_LENGTH characters: $rendered"
        }
    }
}

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
 * | NUMBER_TEXT | [DialogInput.text] with numeric validation |
 * | INTEGER | [DialogInput.numberRange] + step=1 |
 * | STRING | [DialogInput.text] |
 * | ENUM | [DialogInput.singleOption] |
 * | VECTOR3D | 3 个 [DialogInput.numberRange] (X/Y/Z) |
 *
 * ## 条件可见
 * [ConfigKey.visibleWhen] 在每次构建 Dialog 时过滤。被其他字段依赖的 ENUM 会渲染为
 * action buttons；点击后立即保存当前输入并重建 Dialog，因此角色相关字段会即时更新。
 */
class DialogRenderer(
    private val configUI: ConfigUI,
    private val dispatcher: PlatformDispatcher
) {
    companion object {
        /** ClickCallback 默认生命周期 */
        private val DEFAULT_LIFETIME = ClickCallback.DEFAULT_LIFETIME
        private val DEFAULT_TEXT_RESOLVER: (String) -> String = { key ->
            when (key) {
                "dialog.confirm" -> "✓ 确认"
                "dialog.enabled" -> "开启"
                "dialog.disabled" -> "关闭"
                "dialog.invalid_number" -> "%s 不是有效数字: %s"
                "dialog.finite_number_required" -> "%s 必须是有限数字"
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
                playerDialogCallback(dispatcher, { Unit }) { _, player -> entry.onSelected(player) },
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
        additionalBody: List<Component> = emptyList(),
        onConfirmed: ((Player) -> Unit)? = null
    ) {
        val session = configUI.getPlayerSession(player.uniqueId)
        val builtGroup = configUI.buildGroupForPlayer(groupId, player.uniqueId, context)
        showNativeDialog(player, builtGroup, session, textResolver, additionalBody, onConfirmed)
    }

    /**
     * 构建并展示 Paper 原生 Dialog。
     */
    private fun showNativeDialog(
        player: Player,
        builtGroup: BuiltGroup,
        session: PlayerConfigSession,
        textResolver: (String) -> String,
        additionalBody: List<Component>,
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
        additionalBody.forEach { bodies.add(DialogBody.plainMessage(it)) }

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
            playerDialogCallback(dispatcher, { captureResponse(it, builtGroup) }) { inputs, audience ->
                onConfirm(inputs, builtGroup, session, audience, textResolver, additionalBody, onConfirmed)
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
        val buttons = buildTriggerButtons(
            builtGroup,
            session,
            textResolver,
            additionalBody,
            onConfirmed
        ) + confirmButton

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
                .type(DialogType.multiAction(buttons, null, 1))
        }

        player.showDialog(dialog)
    }

    private fun buildTriggerButtons(
        builtGroup: BuiltGroup,
        session: PlayerConfigSession,
        textResolver: (String) -> String,
        additionalBody: List<Component>,
        onConfirmed: ((Player) -> Unit)?
    ): List<ActionButton> {
        val allItems = builtGroup.effectiveItems +
            builtGroup.effectiveSubGroups.flatMap { it.items }
        return allItems.flatMap { item ->
            val key = item.key
            if (key.id !in builtGroup.triggerKeyIds || key.valueType != ConfigValueType.ENUM) {
                return@flatMap emptyList()
            }
            val constraints = key.constraints as? ConfigConstraints.EnumOptions<*>
                ?: return@flatMap emptyList()
            val current = session.getValue(key as ConfigKey<Any?>) ?: key.defaultValue
            constraints.options.map { option ->
                val selected = option == current
                val label = buildString {
                    if (selected) append("✓ ")
                    append(textResolver(key.label))
                    append(": ")
                    val displayName = constraints.displayNames.entries
                        .firstOrNull { it.key == option }?.value ?: option.name
                    append(textResolver(displayName))
                }
                val action = DialogAction.customClick(
                    playerDialogCallback(dispatcher, { captureResponse(it, builtGroup) }) { inputs, audience ->
                        captureInputs(inputs, builtGroup, session, textResolver, setOf(key.id))
                        @Suppress("UNCHECKED_CAST")
                        session.setValue(key as ConfigKey<Any?>, option)
                        session.commit()
                        val rebuilt = configUI.buildGroupForPlayer(
                            builtGroup.group.id,
                            audience.uniqueId,
                            emptyMap()
                        )
                        showNativeDialog(
                            audience,
                            rebuilt,
                            configUI.getPlayerSession(audience.uniqueId),
                            textResolver,
                            additionalBody,
                            onConfirmed
                        )
                    },
                    ClickCallback.Options.builder().uses(1).lifetime(DEFAULT_LIFETIME).build()
                )
                ActionButton.builder(
                    Component.text(label, if (selected) NamedTextColor.GREEN else NamedTextColor.YELLOW)
                ).action(action).build()
            }
        }
    }

    /** 处理确认按钮回调：读取所有 Dialog 输入值并写入 session。 */
    private fun onConfirm(
        view: DialogInputValues,
        builtGroup: BuiltGroup,
        session: PlayerConfigSession,
        player: Player,
        textResolver: (String) -> String,
        additionalBody: List<Component>,
        onConfirmed: ((Player) -> Unit)? = null
    ) {
        val needsRebuild = try {
            captureInputs(view, builtGroup, session, textResolver)
        } catch (failure: IllegalArgumentException) {
            player.sendMessage(Component.text(failure.message ?: "数值输入无效", NamedTextColor.RED))
            showNativeDialog(player, builtGroup, session, textResolver, additionalBody, onConfirmed)
            return
        }
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
                additionalBody,
                onConfirmed
            )
            return
        }
        onConfirmed?.invoke(player)
    }

    // Copy response primitives before handing work to the player's owner; do not retain Paper's view.
    private fun captureResponse(
        view: io.papermc.paper.dialog.DialogResponseView,
        builtGroup: BuiltGroup
    ): DialogInputValues {
        val text = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val numbers = mutableMapOf<String, Float>()
        val items = builtGroup.effectiveItems + builtGroup.effectiveSubGroups.flatMap { it.items }
        items.forEach { item ->
            val key = toInputKey(item.key.id)
            when (item.key.valueType) {
                ConfigValueType.BOOLEAN -> view.getBoolean(key)?.let { booleans[key] = it }
                ConfigValueType.NUMBER, ConfigValueType.INTEGER -> view.getFloat(key)?.let { numbers[key] = it }
                ConfigValueType.NUMBER_TEXT, ConfigValueType.STRING, ConfigValueType.ENUM ->
                    view.getText(key)?.let { text[key] = it }
                ConfigValueType.VECTOR3D -> listOf("x", "y", "z").forEach { axis ->
                    val axisKey = "${key}_$axis"
                    view.getFloat(axisKey)?.let { numbers[axisKey] = it }
                }
            }
        }
        return DialogInputValues(text.toMap(), booleans.toMap(), numbers.toMap())
    }

    private data class DialogInputValues(
        val text: Map<String, String>,
        val booleans: Map<String, Boolean>,
        val numbers: Map<String, Float>
    ) {
        fun getText(key: String) = text[key]
        fun getBoolean(key: String) = booleans[key]
        fun getFloat(key: String) = numbers[key]
    }

    @Suppress("UNCHECKED_CAST")
    private fun captureInputs(
        view: DialogInputValues,
        builtGroup: BuiltGroup,
        session: PlayerConfigSession,
        textResolver: (String) -> String,
        ignoredKeyIds: Set<String> = emptySet()
    ): Boolean {
        val allItems = builtGroup.effectiveItems +
            builtGroup.effectiveSubGroups.flatMap { it.items }
        var needsRebuild = false
        allItems.filterNot { it.key.id in ignoredKeyIds }.forEach { item ->
            val key = item.key
            val inputKey = toInputKey(key.id)
            val oldValue = session.getValue(key as ConfigKey<Any?>)
            when (key.valueType) {
                ConfigValueType.BOOLEAN -> view.getBoolean(inputKey)?.let {
                    session.setValue(key as ConfigKey<Boolean>, it)
                }
                ConfigValueType.NUMBER -> view.getFloat(inputKey)?.let {
                    session.setValue(key as ConfigKey<Double>, it.toDouble())
                }
                ConfigValueType.NUMBER_TEXT -> view.getText(inputKey)?.let { raw ->
                    val value = raw.trim().toDoubleOrNull()
                        ?: throw IllegalArgumentException(
                            textResolver("dialog.invalid_number").format(textResolver(key.label), raw)
                        )
                    require(value.isFinite()) {
                        textResolver("dialog.finite_number_required").format(textResolver(key.label))
                    }
                    session.setValue(key as ConfigKey<Double>, value)
                }
                ConfigValueType.INTEGER -> view.getFloat(inputKey)?.let {
                    session.setValue(key as ConfigKey<Int>, it.toInt())
                }
                ConfigValueType.STRING -> view.getText(inputKey)?.let {
                    session.setValue(key as ConfigKey<String>, it)
                }
                ConfigValueType.ENUM -> view.getText(inputKey)?.let { optionId ->
                    val constraints = key.constraints as? ConfigConstraints.EnumOptions<*>
                    constraints?.options?.find { it.name == optionId }?.let {
                        session.setValue(key as ConfigKey<Any?>, it)
                    }
                }
                ConfigValueType.VECTOR3D -> {
                    val x = view.getFloat(toInputKey("${key.id}.x"))
                    val y = view.getFloat(toInputKey("${key.id}.y"))
                    val z = view.getFloat(toInputKey("${key.id}.z"))
                    if (x != null && y != null && z != null) {
                        session.setValue(
                            key as ConfigKey<org.joml.Vector3d>,
                            org.joml.Vector3d(x.toDouble(), y.toDouble(), z.toDouble())
                        )
                    }
                }
            }
            val newValue = session.getValue(key as ConfigKey<Any?>)
            if (oldValue != newValue && key.id in builtGroup.triggerKeyIds) needsRebuild = true
        }
        return needsRebuild
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

        return allItems
            .filterNot { it.key.id in builtGroup.triggerKeyIds && it.key.valueType == ConfigValueType.ENUM }
            .mapNotNull { item -> toDialogInput(item, session, textResolver) }
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
            ConfigValueType.NUMBER_TEXT -> {
                val constraints = key.constraints as? ConfigConstraints.NumberRange
                val unit = constraints?.unit?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                val value = (current as? Double)?.let(::formatDialogNumberText) ?: ""
                DialogInput.text(inputKey, Component.text(textResolver(key.label) + unit))
                    .initial(value)
                    .maxLength(DIALOG_NUMBER_TEXT_MAX_LENGTH)
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
                val constraints = key.constraints as? ConfigConstraints.EnumOptions<*>
                    ?: return null
                val currentEnum = current as? Enum<*>
                val options = constraints.options.map { option ->
                    val displayKey = constraints.displayNames.entries
                        .firstOrNull { it.key == option }?.value ?: option.name
                    val displayName = textResolver(displayKey)
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
