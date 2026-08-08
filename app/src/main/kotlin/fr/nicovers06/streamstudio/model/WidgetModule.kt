package fr.nicovers06.streamstudio.model

/**
 * Catalogue des widgets de scène.
 * Chaque module déclare un plafond d’instances utilisables dans une même scène.
 */
enum class WidgetType {
    SCREEN,
    CAMERA,
    CHAT,
    /** Source audio uniquement — pas de calque OpenGL / bounds d’aperçu. */
    MICROPHONE,
}

data class WidgetModule(
    val type: WidgetType,
    /** Libellé FR affiché dans le dropdown et l’UI. */
    val label: String,
    /**
     * Nombre maximum d’instances de ce widget dans une scène.
     * `1` = exclusif (cas actuel écran / caméra / chat / micro).
     */
    val maxInstancesPerScene: Int,
    /** true si le widget participe à la composition visuelle (bounds + filtres GL). */
    val visual: Boolean = true,
) {
    init {
        require(maxInstancesPerScene >= 1) {
            "maxInstancesPerScene must be >= 1 for ${type.name}"
        }
    }
}

object WidgetModules {
    val all: List<WidgetModule> = listOf(
        WidgetModule(
            type = WidgetType.SCREEN,
            label = "Partage d’écran",
            maxInstancesPerScene = 1,
        ),
        WidgetModule(
            type = WidgetType.CAMERA,
            label = "Caméra",
            maxInstancesPerScene = 1,
        ),
        WidgetModule(
            type = WidgetType.CHAT,
            label = "Bloc de chat",
            maxInstancesPerScene = 1,
        ),
        WidgetModule(
            type = WidgetType.MICROPHONE,
            label = "Microphone",
            maxInstancesPerScene = 1,
            visual = false,
        ),
    )

    val visualTypes: Set<WidgetType> =
        all.filter { it.visual }.map { it.type }.toSet()

    fun of(type: WidgetType): WidgetModule =
        all.first { it.type == type }

    fun instanceCount(scene: StreamScene, type: WidgetType): Int = when (type) {
        WidgetType.SCREEN -> if (scene.screen.enabled) 1 else 0
        WidgetType.CAMERA -> if (scene.camera.enabled) 1 else 0
        WidgetType.CHAT -> if (scene.chat.enabled) 1 else 0
        WidgetType.MICROPHONE -> if (scene.microphoneEnabled) 1 else 0
    }

    fun canAdd(scene: StreamScene, type: WidgetType): Boolean {
        val module = of(type)
        return instanceCount(scene, type) < module.maxInstancesPerScene
    }

    /** Widgets encore ajoutables dans la scène (sous le plafond max). */
    fun availableToAdd(scene: StreamScene): List<WidgetModule> =
        all.filter { canAdd(scene, it.type) }

    /**
     * Ordre par défaut sidebar (haut = devant pour les widgets visuels).
     * Le micro est listé mais n’affecte pas la composition GL.
     */
    val defaultLayerOrder: List<WidgetType> = listOf(
        WidgetType.CHAT,
        WidgetType.CAMERA,
        WidgetType.SCREEN,
        WidgetType.MICROPHONE,
    )

    /** Liste front→back complète, sans doublon, avec types manquants en fin. */
    fun normalizeLayerOrder(order: List<WidgetType>): List<WidgetType> {
        val seen = linkedSetOf<WidgetType>()
        order.forEach { type ->
            if (type in WidgetType.entries.toSet()) seen.add(type)
        }
        defaultLayerOrder.forEach { if (it !in seen) seen.add(it) }
        WidgetType.entries.forEach { if (it !in seen) seen.add(it) }
        return seen.toList()
    }

    /** Sous-ensemble visuel (front→back) pour aperçu et filtres GL. */
    fun visualLayerOrder(order: List<WidgetType>): List<WidgetType> =
        normalizeLayerOrder(order).filter { it in visualTypes }

    /** Place [type] tout devant (index 0). */
    fun bringToFront(order: List<WidgetType>, type: WidgetType): List<WidgetType> {
        val normalized = normalizeLayerOrder(order).toMutableList()
        normalized.remove(type)
        normalized.add(0, type)
        return normalized
    }
}
