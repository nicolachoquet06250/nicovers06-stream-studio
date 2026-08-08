package fr.nicovers06.streamstudio.model

/**
 * Référence d’une couche dans l’ordre de superposition.
 * Les widgets mono-instance n’ont pas d’[instanceId] ;
 * les images multi-instances utilisent l’id du [ImageComponent].
 */
data class LayerRef(
    val type: WidgetType,
    val instanceId: String? = null,
) {
    init {
        if (type == WidgetType.IMAGE) {
            require(!instanceId.isNullOrBlank()) { "IMAGE layer requires instanceId" }
        } else {
            require(instanceId.isNullOrBlank()) { "${type.name} layer must not have instanceId" }
        }
    }

    fun storageKey(): String =
        if (instanceId.isNullOrBlank()) type.name else "${type.name}:$instanceId"

    companion object {
        fun singleton(type: WidgetType): LayerRef = LayerRef(type, null)

        fun image(instanceId: String): LayerRef = LayerRef(WidgetType.IMAGE, instanceId)

        fun parse(raw: String): LayerRef? {
            if (raw.isBlank()) return null
            val sep = raw.indexOf(':')
            return if (sep < 0) {
                val type = runCatching { WidgetType.valueOf(raw) }.getOrNull() ?: return null
                if (type == WidgetType.IMAGE) null else LayerRef(type, null)
            } else {
                val typeName = raw.substring(0, sep)
                val id = raw.substring(sep + 1)
                val type = runCatching { WidgetType.valueOf(typeName) }.getOrNull() ?: return null
                if (type != WidgetType.IMAGE || id.isBlank()) null else LayerRef(type, id)
            }
        }
    }
}
