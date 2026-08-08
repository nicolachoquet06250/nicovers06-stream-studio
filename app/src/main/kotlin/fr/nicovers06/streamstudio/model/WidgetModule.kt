package fr.nicovers06.streamstudio.model

/**
 * Catalogue des widgets de scène.
 * Chaque module déclare un plafond d’instances utilisables dans une même scène.
 */
enum class WidgetType {
    SCREEN,
    CAMERA,
    CHAT,
    IMAGE,
    /** Source audio uniquement — pas de calque OpenGL / bounds d’aperçu. */
    MICROPHONE,
}

data class WidgetModule(
    val type: WidgetType,
    /** Libellé FR affiché dans le dropdown et l’UI. */
    val label: String,
    /**
     * Nombre maximum d’instances de ce widget dans une scène.
     * `1` = exclusif (écran / caméra / chat / micro).
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
            type = WidgetType.IMAGE,
            label = "Image",
            maxInstancesPerScene = ImageComponent.MAX_PER_SCENE,
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
        WidgetType.IMAGE -> scene.images.size
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
     * Les images s’insèrent dynamiquement en tête à l’ajout.
     */
    val defaultLayerOrder: List<LayerRef> = listOf(
        LayerRef.singleton(WidgetType.CHAT),
        LayerRef.singleton(WidgetType.CAMERA),
        LayerRef.singleton(WidgetType.SCREEN),
        LayerRef.singleton(WidgetType.MICROPHONE),
    )

    private val singletonTypes: List<WidgetType> = listOf(
        WidgetType.CHAT,
        WidgetType.CAMERA,
        WidgetType.SCREEN,
        WidgetType.MICROPHONE,
    )

    /** Liste front→back complète, sans doublon, avec types manquants / images absentes. */
    fun normalizeLayerOrder(order: List<LayerRef>, scene: StreamScene): List<LayerRef> {
        val imageIds = scene.images.map { it.id }.toSet()
        val seen = linkedSetOf<String>()
        val result = mutableListOf<LayerRef>()

        fun tryAdd(ref: LayerRef) {
            val key = ref.storageKey()
            if (key in seen) return
            when (ref.type) {
                WidgetType.IMAGE -> {
                    val id = ref.instanceId ?: return
                    if (id !in imageIds) return
                }
                else -> Unit
            }
            seen.add(key)
            result.add(ref)
        }

        order.forEach { tryAdd(it) }
        // Images absentes de l’ordre → devant (après celles déjà placées).
        scene.images.forEach { img -> tryAdd(LayerRef.image(img.id)) }
        singletonTypes.forEach { tryAdd(LayerRef.singleton(it)) }
        return result
    }

    /** Sous-ensemble visuel (front→back) pour aperçu et filtres GL. */
    fun visualLayerOrder(order: List<LayerRef>, scene: StreamScene): List<LayerRef> =
        normalizeLayerOrder(order, scene).filter { it.type in visualTypes }

    /** Place [ref] tout devant (index 0). */
    fun bringToFront(order: List<LayerRef>, ref: LayerRef, scene: StreamScene): List<LayerRef> {
        val normalized = normalizeLayerOrder(order, scene).toMutableList()
        normalized.removeAll { it.storageKey() == ref.storageKey() }
        normalized.add(0, ref)
        return normalized
    }

    fun bringToFront(order: List<LayerRef>, type: WidgetType, scene: StreamScene): List<LayerRef> {
        require(type != WidgetType.IMAGE) { "Use bringToFront with LayerRef for IMAGE" }
        return bringToFront(order, LayerRef.singleton(type), scene)
    }
}
