package work.art1st.mineRails.basic.impl.config

import work.art1st.mineRails.basic.api.ui.config.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [ConfigUI] 的默认实现。
 *
 * 线程安全（使用 [ConcurrentHashMap]），支持多玩家并发访问。
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
        return flattenItems(group, snapshot, emptySet()).sortedBy { it.key.order }
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
                createSnapshot()
            )

        val snapshot = createSnapshot()

        // Step 1: 展平 items（评估 visibleWhen）
        val flattenedItems = flattenItems(group, snapshot, emptySet())

        // Step 2: 展平 subGroups（评估 visibleWhen）
        val visibleSubGroups = group.subGroups.filter { subGroup ->
            val visible = subGroup.visibleWhen?.invoke(snapshot) ?: true
            visible
        }

        // Step 3: 触发 BuildEvent
        val event = ConfigGroupBuildEvent(groupId, playerId, context)
        buildListeners[groupId]?.forEach { it.onBuild(event) }

        // Step 4: 收集隐藏项
        val hiddenIds = mutableSetOf<String>()
        // visibleWhen 隐藏的项
        val allItemIds = collectAllItemIds(group)
        val visibleItemIds = flattenedItems.map { it.key.id }.toSet()
        hiddenIds.addAll(allItemIds - visibleItemIds)
        // 事件隐藏的项
        hiddenIds.addAll(event.collectHiddenItemIds())

        // Step 5: 合并事件添加的额外项
        val allItems = (flattenedItems + event.collectExtraItems())
            .filter { it.key.id !in hiddenIds }
            .sortedBy { it.key.order }

        // Step 6: 合并事件添加的额外分组
        val allSubGroups = (visibleSubGroups + event.collectExtraGroups())
            .filter { it.id !in event.collectHiddenGroupIds() }

        return BuiltGroup(group, allItems, allSubGroups, hiddenIds, snapshot)
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

    /** 展平分组的所有项（评估可见性，递归处理子分组） */
    private fun flattenItems(
        group: ConfigGroup,
        snapshot: ConfigSnapshot,
        hiddenIds: Set<String>
    ): List<ConfigItem> {
        val result = mutableListOf<ConfigItem>()

        // 自身的 items
        group.items.forEach { item ->
            if (item.key.id !in hiddenIds) {
                val visible = item.key.visibleWhen?.invoke(snapshot) ?: true
                if (visible) {
                    result.add(item)
                }
            }
        }

        // 子分组的 items（递归）
        group.subGroups.forEach { subGroup ->
            val groupVisible = subGroup.visibleWhen?.invoke(snapshot) ?: true
            if (groupVisible && subGroup.id !in hiddenIds) {
                result.addAll(flattenItems(subGroup, snapshot, hiddenIds))
            }
        }

        return result
    }

    /** 收集分组及其子分组的所有项 ID */
    private fun collectAllItemIds(group: ConfigGroup): Set<String> {
        val ids = mutableSetOf<String>()
        group.items.forEach { ids.add(it.key.id) }
        group.subGroups.forEach { ids.addAll(collectAllItemIds(it)) }
        return ids
    }

    /** 创建当前全局配置的快照 */
    private fun createSnapshot(): ConfigSnapshot {
        return object : ConfigSnapshot {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(keyId: String): T? {
                return items[keyId]?.currentValue as? T
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T> getOrDefault(keyId: String, default: T): T {
                return (items[keyId]?.currentValue as? T) ?: default
            }

            override fun contains(keyId: String): Boolean {
                return items.containsKey(keyId)
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
