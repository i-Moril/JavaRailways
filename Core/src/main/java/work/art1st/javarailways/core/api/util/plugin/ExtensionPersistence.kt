package work.art1st.javarailways.core.api.util.plugin

/**
 * Collects saved extension payloads into one stable extension-owner-keyed map.
 *
 * Extension slots are independent plugins, so two slots may legitimately produce the same owner key.
 * Such a collision cannot be represented in saved data and is rejected instead of silently keeping
 * the last payload. [host] identifies the persisted owner in the failure message.
 */
internal fun <T : Extension<*>> List<T?>.collectSavedExtensionData(
    host: String
): Map<String, SerializableExtensionData> {
    val saved = LinkedHashMap<String, SerializableExtensionData>()
    val slots = LinkedHashMap<String, Int>()
    forEachIndexed { slot, extension ->
        val snapshot = extension?.save() ?: return@forEachIndexed
        val (ownerKey, payload) = snapshot
        val firstSlot = slots[ownerKey]
        require(firstSlot == null) {
            "Extension owner key '$ownerKey' is saved by multiple slots for $host: " +
                "slot $firstSlot and slot $slot"
        }
        slots[ownerKey] = slot
        saved[ownerKey] = payload
    }
    return saved
}
