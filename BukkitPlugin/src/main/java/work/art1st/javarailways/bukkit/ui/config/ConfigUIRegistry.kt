package work.art1st.javarailways.bukkit.ui.config

import work.art1st.javarailways.bukkit.api.wand.config.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * [ConfigUI] 的默认实现。
 *
 * 注册/结构变更由调用方串行安排，不得与分组构建并发；同一玩家会话也由其调用方串行使用。
 * 并发映射只隔离身份查找，不保证可变分组、配置项或跨键提交的整体原子性。
 * 构建快照逐键捕获后保持稳定，不是跨键配置事务；全局写入协调仍属于调用方。
 *
 * ## 分组合并语义
 * - 注册同名分组时，新分组的 items 和 subGroups **追加**到现有分组
 * - 扩展点贡献（[registerToGroup]）也会被合并
 * - 如果注册时目标分组尚不存在，贡献会被**暂存**，等目标分组注册后再合并
 *
 * ## 可见性评估
 * [buildGroupForPlayer] 时对每个 item/group 评估 [ConfigKey.visibleWhen]/[ConfigGroup.visibleWhen]
 * 以及触发 [ConfigGroupBuildEvent] 让监听器动态调整。
 */
class ConfigUIRegistry : ConfigUI {

    // ═══════════════════════════════════════════
    // 内部存储
    // ═══════════════════════════════════════════

    /** 所有已注册的分组 (groupId -> ConfigGroup) */
    private val groups = ConcurrentHashMap<String, ConfigGroup>()

    /** 所有已注册的项 (keyId -> WandConfigItem) */
    private val items = ConcurrentHashMap<String, WandConfigItem>()

    /** 扩展点贡献：暂存的目标分组尚不存在的 items (groupId -> pending items) */
    private val pendingItems = ConcurrentHashMap<String, MutableList<WandConfigItem>>()

    /** 扩展点贡献：暂存的目标分组尚不存在的 subGroups (groupId -> pending subGroups) */
    private val pendingSubGroups = ConcurrentHashMap<String, MutableList<ConfigGroup>>()

    /** 构建监听器 (groupId -> listeners) */
    private val buildListeners = ConcurrentHashMap<String, MutableList<ConfigGroupBuildListener>>()

    /** 玩家会话 (playerId -> session) */
    private val playerSessions = ConcurrentHashMap<UUID, PlayerConfigSessionImpl>()

    /** 值变化回调 */
    private var valueChangedCallback: ((ConfigKey<*>, Any?, Any?) -> Unit)? = null

    // ═══════════════════════════════════════════
    // 注册
    // ═══════════════════════════════════════════

    override fun register(group: ConfigGroup) {
        val existing = groups[group.id]
        val acceptsPending = (existing ?: group).allowExtensions
        val declaredItems = collectItems(group)
        val incoming = declaredItems + if (acceptsPending) {
            pendingItems[group.id].orEmpty() + pendingSubGroups[group.id].orEmpty().flatMap(::collectItems)
        } else emptyList()
        validateItems(incoming)
        val target = if (existing != null) {
            // 合并：追加 items 和 subGroups
            synchronized(existing) {
                existing.items.addAll(group.items)
                existing.subGroups.addAll(group.subGroups)
            }
            existing
        } else {
            groups[group.id] = group
            group
        }

        // 注册所有项
        declaredItems.forEach { items[it.key.id] = it }

        // 结算该分组此前暂存的扩展贡献
        applyPendingExtensions(group.id, target)
    }

    override fun register(item: WandConfigItem) {
        validateItems(listOf(item))
        items[item.key.id] = item
        val groupId = item.key.group
        if (groupId != null) {
            val group = groups[groupId]
            if (group != null) {
                synchronized(group) {
                    if (!group.items.contains(item)) {
                        group.items.add(item)
                    }
                }
            } else {
                // 目标分组尚不存在，暂存
                pendingItems.getOrPut(groupId) { mutableListOf() }.add(item)
            }
        }
    }

    override fun register(items: Sequence<WandConfigItem>) {
        val batch = items.toList()
        validateItems(batch)
        batch.forEach { register(it) }
    }

    override fun registerToGroup(groupId: String, item: WandConfigItem) {
        validateItems(listOf(item))
        val group = groups[groupId]
        if (group == null) {
            // 目标分组尚不存在：只暂存，不接受进正式索引。
            pendingItems.getOrPut(groupId) { mutableListOf() }.add(item)
            return
        }
        require(group.allowExtensions) {
            "Config group '$groupId' does not allow extensions; rejected item '${item.key.id}'"
        }
        acceptExtensionItem(group, item)
    }

    override fun registerToGroup(groupId: String, subGroup: ConfigGroup) {
        validateItems(collectItems(subGroup))
        val group = groups[groupId]
        if (group == null) {
            pendingSubGroups.getOrPut(groupId) { mutableListOf() }.add(subGroup)
            return
        }
        require(group.allowExtensions) {
            "Config group '$groupId' does not allow extensions; rejected subgroup '${subGroup.id}'"
        }
        acceptExtensionSubGroup(group, subGroup)
    }

    override fun pendingExtensionGroupIds(): Set<String> =
        (pendingItems.keys + pendingSubGroups.keys).toSet()

    // ═══════════════════════════════════════════
    // 值访问
    // ═══════════════════════════════════════════

    override fun <T> get(key: ConfigKey<T>): T? {
        val item = items[key.id] ?: return null
        item.key.requireSameType(key)
        @Suppress("UNCHECKED_CAST")
        return item.currentValue as T
    }

    override fun <T> getOrDefault(key: ConfigKey<T>): T {
        return get(key) ?: key.defaultValue
    }

    override fun <T> setValue(key: ConfigKey<T>, value: T) {
        key.validateValue(value)
        val item = items[key.id]
        if (item != null) {
            item.key.requireSameType(key)
            require(item is WandConfigItemImpl<*>) {
                "Config item '${key.id}' is ${item::class.qualifiedName}; ConfigUI.setValue requires " +
                    "WandConfigItemImpl and will not replace a registered implementation"
            }
            val oldValue = item.currentValue
            (item as WandConfigItemImpl<T>).setValue(value)
            item.commit()
            notifyValueChanged(key, oldValue, value)
        } else {
            // 项未注册，自动注册
            val newItem = WandConfigItemImpl(key, value)
            items[key.id] = newItem
            notifyValueChanged(key, null, value)
        }
    }

    // ═══════════════════════════════════════════
    // 查找
    // ═══════════════════════════════════════════

    override fun getItem(keyId: String): WandConfigItem? = items[keyId]

    override fun getGroup(groupId: String): ConfigGroup? = groups[groupId]

    override fun getAllGroups(): List<ConfigGroup> = groups.values.toList()

    override fun getEffectiveItems(groupId: String): List<WandConfigItem> {
        val group = groups[groupId] ?: return emptyList()
        val snapshot = createSnapshot()
        val filtered = filterGroup(group, snapshot, emptySet(), emptySet()) ?: return emptyList()
        return collectItems(filtered).distinctBy { it.key.id }.sortedBy { it.key.order }
    }

    // ═══════════════════════════════════════════
    // 预渲染事件
    // ═══════════════════════════════════════════

    override fun addBuildListener(groupId: String, listener: ConfigGroupBuildListener) {
        buildListeners.getOrPut(groupId) { mutableListOf() }.add(listener)
    }

    override fun removeBuildListener(groupId: String, listener: ConfigGroupBuildListener) {
        buildListeners[groupId]?.remove(listener)
    }

    override fun buildGroupForPlayer(
        groupId: String,
        playerId: UUID,
        context: Map<String, Any>
    ): BuiltGroup {
        val group = groups[groupId]
            ?: return BuiltGroup(
                ConfigGroup(groupId, groupId),
                emptyList(), emptyList(), emptySet(),
                createSnapshot(playerId)
            )

        val event = ConfigGroupBuildEvent(groupId, playerId, context)
        buildListeners[groupId]?.forEach { it.onBuild(event) }
        val candidates = ConfigGroup(group.id, group.label, group.description,
            (group.items + event.collectExtraItems()).toMutableList(),
            (group.subGroups + event.collectExtraGroups()).toMutableList(),
            group.allowExtensions, group.isExpanded, group.visibleWhen)
        val candidateItems = collectItems(candidates)
        candidateItems.forEach { candidate ->
            val registered = requireNotNull(items[candidate.key.id]) {
                "Config group '$groupId' contains unregistered item '${candidate.key.id}'; " +
                    "build events may only compose registered items"
            }
            registered.key.requireSameType(candidate.key)
        }
        val snapshot = createSnapshot(playerId)
        val filtered = filterGroup(candidates, snapshot, event.collectHiddenItemIds(), event.collectHiddenGroupIds())
        val effectiveItems = filtered?.let(::collectItems).orEmpty()
            .distinctBy { it.key.id }.sortedBy { it.key.order }
        val hiddenIds = event.collectHiddenItemIds() +
            (candidateItems.map { it.key.id }.toSet() - effectiveItems.map { it.key.id }.toSet())
        // Dependencies come from the complete candidate tree, including items hidden right now.
        val triggerKeyIds = candidateItems.asSequence()
            .flatMap { it.key.dependsOn.asSequence() }
            .toSet()
        return BuiltGroup(group, effectiveItems, filtered?.subGroups.orEmpty(), hiddenIds, snapshot, triggerKeyIds)
    }

    // ═══════════════════════════════════════════
    // 值变化回调
    // ═══════════════════════════════════════════

    override fun onValueChanged(key: ConfigKey<*>, oldValue: Any?, newValue: Any?) {
        // 默认空实现。外部可以通过 setValueChangedCallback 设置自定义逻辑。
    }

    /**
     * 设置值变化回调（供 BukkitPlugin 等外部模块使用）。
     */
    fun setValueChangedCallback(callback: (ConfigKey<*>, Any?, Any?) -> Unit) {
        valueChangedCallback = callback
    }

    // ═══════════════════════════════════════════
    // 玩家会话
    // ═══════════════════════════════════════════

    override fun getPlayerSession(playerId: UUID): PlayerConfigSession {
        return playerSessions.getOrPut(playerId) { PlayerConfigSessionImpl(playerId, this) }
    }

    override fun removePlayerSession(playerId: UUID) {
        playerSessions.remove(playerId)
    }

    // ═══════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════

    private fun notifyValueChanged(key: ConfigKey<*>, oldValue: Any?, newValue: Any?) {
        try {
            valueChangedCallback?.invoke(key, oldValue, newValue)
            onValueChanged(key, oldValue, newValue)
        } catch (failure: Exception) {
            throw ConfigNotificationException(key.id, failure)
        }
    }

    /** Validate the whole incoming batch before changing any tree, index or pending contribution. */
    private fun validateItems(incoming: List<WandConfigItem>) {
        val keys = items.mapValues { it.value.key }.toMutableMap()
        incoming.forEach { item ->
            requireSupportedItem(item)
            item.key.validateValue(item.currentValue)
            keys.putIfAbsent(item.key.id, item.key)?.requireSameType(item.key)
        }
    }

    /** 递归注册分组及其子分组中的所有项；调用者已完整验证。 */
    private fun registerAllItems(group: ConfigGroup) {
        group.items.forEach {
            items[it.key.id] = it
        }
        group.subGroups.forEach { registerAllItems(it) }
    }

    private fun requireSupportedItem(item: WandConfigItem) {
        require(item is WandConfigItemImpl<*>) {
            "ConfigUI only supports WandConfigItemImpl values, got ${item::class.qualifiedName} " +
                "for '${item.key.id}'"
        }
    }

    private fun acceptExtensionItem(group: ConfigGroup, item: WandConfigItem) {
        items[item.key.id] = item
        synchronized(group) {
            if (!group.items.contains(item)) {
                group.items.add(item)
            }
        }
    }

    private fun acceptExtensionSubGroup(group: ConfigGroup, subGroup: ConfigGroup) {
        synchronized(group) {
            if (!group.subGroups.contains(subGroup)) {
                group.subGroups.add(subGroup)
            }
        }
        registerAllItems(subGroup)
    }

    /** 结算目标分组的暂存贡献；已知不允许扩展时丢弃而不是悬挂在半注册状态。 */
    private fun applyPendingExtensions(groupId: String, group: ConfigGroup) {
        val pendingItemList = pendingItems.remove(groupId).orEmpty()
        val pendingSubGroupList = pendingSubGroups.remove(groupId).orEmpty()
        if (pendingItemList.isEmpty() && pendingSubGroupList.isEmpty()) return
        if (!group.allowExtensions) {
            LOGGER.warning(
                "Discarded extension contributions for config group '$groupId' because it does not " +
                    "allow extensions: items=${pendingItemList.map { it.key.id }}, " +
                    "subgroups=${pendingSubGroupList.map { it.id }}"
            )
            return
        }
        pendingItemList.forEach { acceptExtensionItem(group, it) }
        pendingSubGroupList.forEach { acceptExtensionSubGroup(group, it) }
    }

    private companion object {
        private val LOGGER = Logger.getLogger(ConfigUIRegistry::class.java.name)
    }

    /** Filter whole subtrees before flattening; hidden descendants never remain editable. */
    private fun filterGroup(
        group: ConfigGroup,
        snapshot: ConfigSnapshot,
        hiddenItems: Set<String>,
        hiddenGroups: Set<String>
    ): ConfigGroup? {
        if (group.id in hiddenGroups || group.visibleWhen?.invoke(snapshot) == false) return null
        return ConfigGroup(group.id, group.label, group.description,
            group.items.filter { it.key.id !in hiddenItems && it.key.visibleWhen?.invoke(snapshot) != false }.toMutableList(),
            group.subGroups.mapNotNull { filterGroup(it, snapshot, hiddenItems, hiddenGroups) }.toMutableList(),
            group.allowExtensions, group.isExpanded)
    }

    private fun collectItems(group: ConfigGroup): List<WandConfigItem> = buildList {
        fun visit(current: ConfigGroup) {
            addAll(current.items)
            current.subGroups.forEach(::visit)
        }
        visit(group)
    }

    /** 创建当前玩家会话优先、全局值回退的配置快照。 */
    private fun createSnapshot(playerId: UUID? = null): ConfigSnapshot {
        val session = playerId?.let(playerSessions::get)
        val values = items.mapValues { (_, item) ->
            @Suppress("UNCHECKED_CAST")
            val key = item.key as ConfigKey<Any?>
            session?.getValue(key) ?: item.currentValue
        }
        return object : ConfigSnapshot {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(keyId: String): T? {
                return values[keyId] as? T
            }

            override fun <T> getOrDefault(keyId: String, default: T): T {
                return get<T>(keyId) ?: default
            }

            override fun contains(keyId: String): Boolean {
                return values.containsKey(keyId)
            }
        }
    }
}

/**
 * [PlayerConfigSession] 的默认实现。
 *
 * 每个玩家持有独立的配置值副本，通过 [commit] 同步到全局 [ConfigUIRegistry]。
 */
class PlayerConfigSessionImpl(
    override val playerId: UUID,
    private val registry: ConfigUIRegistry
) : PlayerConfigSession {

    /** 玩家本地值副本 (keyId -> value) */
    private val localValues = ConcurrentHashMap<String, Any?>()

    /** Type identity captured alongside each local value. */
    private val localKeys = mutableMapOf<String, ConfigKey<*>>()

    /** dirty 标记 (keyId) */
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> getValue(key: ConfigKey<T>): T? {
        registry.getItem(key.id)?.key?.requireSameType(key)
        localKeys[key.id]?.requireSameType(key)
        // 优先返回本地副本
        if (localValues.containsKey(key.id)) {
            return localValues[key.id] as T
        }
        // 回退到全局值
        return registry.get(key)
    }

    override fun <T> setValue(key: ConfigKey<T>, value: T) {
        registry.getItem(key.id)?.key?.requireSameType(key)
        localKeys[key.id]?.requireSameType(key)
        key.validateValue(value)
        val unchanged = getValue(key) == value
        localKeys[key.id] = key
        localValues[key.id] = value
        if (!unchanged) dirtyKeys.add(key.id)
    }

    override fun commit(): ConfigCommitResult {
        val committed = linkedSetOf<String>()
        val failures = mutableListOf<ConfigCommitFailure>()
        val notificationFailures = mutableListOf<ConfigCommitFailure>()
        for (keyId in dirtyKeys.toList()) {
            val item = registry.getItem(keyId)
            if (item == null) {
                failures += ConfigCommitFailure(keyId, "configuration item is not registered")
                continue
            }
            val value = checkNotNull(localValues[keyId]) { "Dirty config '$keyId' has no local value" }
            item.key.requireSameType(checkNotNull(localKeys[keyId]))
            try {
                @Suppress("UNCHECKED_CAST")
                registry.setValue(item.key as ConfigKey<Any?>, value)
                committed += keyId
                // Only successful writes leave the dirty set; failures retain both value and flag.
                dirtyKeys.remove(keyId)
            } catch (failure: ConfigNotificationException) {
                committed += keyId
                dirtyKeys.remove(keyId)
                notificationFailures += ConfigCommitFailure(keyId, failure.message ?: "change notification failed")
            } catch (failure: IllegalArgumentException) {
                failures += ConfigCommitFailure(
                    keyId, failure.message ?: "configuration value is invalid"
                )
            }
        }
        return ConfigCommitResult(committed, failures, notificationFailures)
    }

    override fun reset() {
        localValues.clear()
        localKeys.clear()
        dirtyKeys.clear()
    }

    override fun hasPendingChanges(): Boolean = dirtyKeys.isNotEmpty()

    override fun getDirtyKeyIds(): Set<String> = dirtyKeys.toSet()
}
