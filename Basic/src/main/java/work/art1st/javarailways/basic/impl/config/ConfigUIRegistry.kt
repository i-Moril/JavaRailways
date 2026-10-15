package work.art1st.javarailways.basic.impl.config

import work.art1st.javarailways.basic.api.ui.config.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    /** 所有已注册的项 (keyId -> ConfigItem) */
    private val items = ConcurrentHashMap<String, ConfigItem>()

    /** 扩展点贡献：暂存的目标分组尚不存在的 items (groupId -> pending items) */
    private val pendingItems = ConcurrentHashMap<String, MutableList<ConfigItem>>()

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
        if (existing != null) {
            // 合并：追加 items 和 subGroups
            synchronized(existing) {
                existing.items.addAll(group.items)
                existing.subGroups.addAll(group.subGroups)
            }
        } else {
            groups[group.id] = group
        }

        // 注册所有项
        registerAllItems(group)

        // 处理该分组的待合并扩展
        applyPendingExtensions(group.id, group)
    }

    override fun register(item: ConfigItem) {
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

    override fun register(items: Sequence<ConfigItem>) {
        items.forEach { register(it) }
    }

    override fun registerToGroup(groupId: String, item: ConfigItem) {
        val group = groups[groupId]
        if (group != null && group.allowExtensions) {
            items[item.key.id] = item
            synchronized(group) {
                if (!group.items.contains(item)) {
                    group.items.add(item)
                }
            }
        } else if (group == null) {
            // 目标分组尚不存在，暂存
            items[item.key.id] = item
            pendingItems.getOrPut(groupId) { mutableListOf() }.add(item)
        }
        // 如果 group 存在但 allowExtensions = false，静默忽略
    }

    override fun registerToGroup(groupId: String, subGroup: ConfigGroup) {
        val group = groups[groupId]
        if (group != null && group.allowExtensions) {
            synchronized(group) {
                if (!group.subGroups.contains(subGroup)) {
                    group.subGroups.add(subGroup)
                }
            }
            registerAllItems(subGroup)
        } else if (group == null) {
            pendingSubGroups.getOrPut(groupId) { mutableListOf() }.add(subGroup)
            registerAllItems(subGroup)
        }
    }

    // ═══════════════════════════════════════════
    // 值访问
    // ═══════════════════════════════════════════

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: ConfigKey<T>): T? {
        return items[key.id]?.currentValue as? T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getOrDefault(key: ConfigKey<T>): T {
        return (items[key.id]?.currentValue as? T) ?: key.defaultValue
    }

    override fun <T> setValue(key: ConfigKey<T>, value: T) {
        key.constraints?.validateValue(value)
        val item = items[key.id]
        if (item != null && item is ConfigItemImpl<*>) {
            val oldValue = item.currentValue
            (item as ConfigItemImpl<T>).setValue(value)
            item.commit()
            valueChangedCallback?.invoke(key, oldValue, value)
            onValueChanged(key, oldValue, value)
        } else {
            // 项未注册，自动注册
            val newItem = ConfigItemImpl(key, value)
            items[key.id] = newItem
            valueChangedCallback?.invoke(key, null, value)
            onValueChanged(key, null, value)
        }
    }

    // ═══════════════════════════════════════════
    // 查找
    // ═══════════════════════════════════════════

    override fun getItem(keyId: String): ConfigItem? = items[keyId]

    override fun getGroup(groupId: String): ConfigGroup? = groups[groupId]

    override fun getAllGroups(): List<ConfigGroup> = groups.values.toList()

    override fun getEffectiveItems(groupId: String): List<ConfigItem> {
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
        val snapshot = createSnapshot(playerId, candidateItems)
        val filtered = filterGroup(candidates, snapshot, event.collectHiddenItemIds(), event.collectHiddenGroupIds())
        val effectiveItems = filtered?.let(::collectItems).orEmpty()
            .distinctBy { it.key.id }.sortedBy { it.key.order }
        val hiddenIds = event.collectHiddenItemIds() +
            (candidateItems.map { it.key.id }.toSet() - effectiveItems.map { it.key.id }.toSet())
        return BuiltGroup(group, effectiveItems, filtered?.subGroups.orEmpty(), hiddenIds, snapshot)
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

    /** 递归注册分组及其子分组中的所有项 */
    private fun registerAllItems(group: ConfigGroup) {
        group.items.forEach { items[it.key.id] = it }
        group.subGroups.forEach { registerAllItems(it) }
    }

    /** 将待合并的扩展应用到新注册的分组 */
    private fun applyPendingExtensions(groupId: String, group: ConfigGroup) {
        if (!group.allowExtensions) return

        pendingItems.remove(groupId)?.let { pending ->
            synchronized(group) {
                pending.forEach { item ->
                    if (!group.items.contains(item)) {
                        group.items.add(item)
                    }
                }
            }
        }

        pendingSubGroups.remove(groupId)?.let { pending ->
            synchronized(group) {
                pending.forEach { sub ->
                    if (!group.subGroups.contains(sub)) {
                        group.subGroups.add(sub)
                    }
                }
            }
        }
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

    private fun collectItems(group: ConfigGroup): List<ConfigItem> = buildList {
        fun visit(current: ConfigGroup) {
            addAll(current.items)
            current.subGroups.forEach(::visit)
        }
        visit(group)
    }

    /** 创建当前玩家会话优先、全局值回退的配置快照。 */
    private fun createSnapshot(playerId: UUID? = null, extraItems: List<ConfigItem> = emptyList()): ConfigSnapshot {
        val session = playerId?.let(playerSessions::get)
        val candidates = LinkedHashMap(items)
        extraItems.forEach { candidates.putIfAbsent(it.key.id, it) }
        val values = candidates.mapValues { (_, item) ->
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

    /** dirty 标记 (keyId) */
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> getValue(key: ConfigKey<T>): T? {
        // 优先返回本地副本
        if (localValues.containsKey(key.id)) {
            return localValues[key.id] as? T
        }
        // 回退到全局值
        return registry.get(key)
    }

    override fun <T> setValue(key: ConfigKey<T>, value: T) {
        key.constraints?.validateValue(value)
        localValues[key.id] = value
        dirtyKeys.add(key.id)
    }

    override fun commit() {
        dirtyKeys.forEach { keyId ->
            val item = registry.getItem(keyId) ?: return@forEach
            val value = localValues[keyId] ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            registry.setValue(item.key as ConfigKey<Any?>, value)
        }
        dirtyKeys.clear()
    }

    override fun reset() {
        localValues.clear()
        dirtyKeys.clear()
    }

    override fun hasPendingChanges(): Boolean = dirtyKeys.isNotEmpty()

    override fun getDirtyKeyIds(): Set<String> = dirtyKeys.toSet()
}
