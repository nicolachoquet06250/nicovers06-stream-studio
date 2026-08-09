package fr.nicovers06.streamstudio.model

/**
 * Référence d’une couche dans l’ordre de superposition.
 * Les widgets historiques mono-instance n'ont pas d'[instanceId] ; les images et widgets natifs
 * dynamiques utilisent l'identifiant de leur instance.
 */
data class LayerRef(
    val type: WidgetType,
    val instanceId: String? = null,
) {
    init {
        if (type.instanceBased) {
            require(!instanceId.isNullOrBlank()) { "${type.name} layer requires instanceId" }
        } else {
            require(instanceId.isNullOrBlank()) { "${type.name} layer must not have instanceId" }
        }
    }

    fun storageKey(): String =
        if (instanceId.isNullOrBlank()) type.name else "${type.name}:$instanceId"

    companion object {
        fun singleton(type: WidgetType): LayerRef {
            require(!type.instanceBased) { "${type.name} is instance-based" }
            return LayerRef(type, null)
        }

        fun image(instanceId: String): LayerRef = LayerRef(WidgetType.IMAGE, instanceId)

        fun instance(type: WidgetType, instanceId: String): LayerRef = LayerRef(type, instanceId)

        fun parse(raw: String): LayerRef? {
            if (raw.isBlank()) return null
            val sep = raw.indexOf(':')
            return if (sep < 0) {
                val type = runCatching { WidgetType.valueOf(raw) }.getOrNull() ?: return null
                if (type.instanceBased) null else LayerRef(type, null)
            } else {
                val typeName = raw.substring(0, sep)
                val id = raw.substring(sep + 1)
                val type = runCatching { WidgetType.valueOf(typeName) }.getOrNull() ?: return null
                if (!type.instanceBased || id.isBlank()) null else LayerRef(type, id)
            }
        }
    }
}
