package fr.nicovers06.streamstudio.model

/**
 * Catalogue des widgets de scène.
 * Chaque module déclare un plafond d’instances utilisables dans une même scène.
 */
enum class WidgetType(val instanceBased: Boolean = false) {
    SCREEN,
    CAMERA,
    CHAT,
    IMAGE(instanceBased = true),
    TIMER(instanceBased = true),
    SHAPE(instanceBased = true),
    BACKGROUND(instanceBased = true),
    TICKER(instanceBased = true),
    MEDIA(instanceBased = true),
    ALERT(instanceBased = true),
    POLL(instanceBased = true),
    TEXT(instanceBased = true),
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
        WidgetModule(WidgetType.TIMER, "Minuteur", 1),
        WidgetModule(WidgetType.SHAPE, "Forme", 10),
        WidgetModule(WidgetType.BACKGROUND, "Arrière-plan", 1),
        WidgetModule(WidgetType.TICKER, "Bandeau défilant", 2),
        WidgetModule(WidgetType.MEDIA, "Média", 1),
        WidgetModule(WidgetType.ALERT, "Alerte", Int.MAX_VALUE),
        WidgetModule(WidgetType.POLL, "Sondage / Question", 1),
        WidgetModule(WidgetType.TEXT, "Texte / Lower third", Int.MAX_VALUE),
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
        WidgetType.SCREEN -> if (scene.screenPresent) 1 else 0
        WidgetType.CAMERA -> if (scene.cameraPresent) 1 else 0
        WidgetType.CHAT -> if (scene.chatPresent) 1 else 0
        WidgetType.IMAGE -> scene.images.size
        WidgetType.TIMER,
        WidgetType.SHAPE,
        WidgetType.BACKGROUND,
        WidgetType.TICKER,
        WidgetType.MEDIA,
        WidgetType.ALERT,
        WidgetType.POLL,
        WidgetType.TEXT,
        -> scene.nativeWidgets.count { it.type == type }
        WidgetType.MICROPHONE -> if (scene.microphonePresent) 1 else 0
    }

    fun activeInstanceCount(scene: StreamScene, type: WidgetType): Int = when (type) {
        WidgetType.SCREEN -> if (scene.screenPresent && scene.screen.enabled) 1 else 0
        WidgetType.CAMERA -> if (scene.cameraPresent && scene.camera.enabled) 1 else 0
        WidgetType.CHAT -> if (scene.chatPresent && scene.chat.enabled) 1 else 0
        WidgetType.IMAGE -> scene.images.count { it.enabled }
        WidgetType.TIMER,
        WidgetType.SHAPE,
        WidgetType.BACKGROUND,
        WidgetType.TICKER,
        WidgetType.MEDIA,
        WidgetType.ALERT,
        WidgetType.POLL,
        WidgetType.TEXT,
        -> scene.nativeWidgets.count { it.type == type && it.enabled }
        WidgetType.MICROPHONE -> if (scene.microphonePresent && scene.microphoneEnabled) 1 else 0
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

    private fun singletonPresent(scene: StreamScene, type: WidgetType): Boolean = when (type) {
        WidgetType.SCREEN -> scene.screenPresent
        WidgetType.CAMERA -> scene.cameraPresent
        WidgetType.CHAT -> scene.chatPresent
        WidgetType.MICROPHONE -> scene.microphonePresent
        else -> false
    }

    /** Liste front→back complète, sans doublon, avec types manquants / instances absentes. */
    fun normalizeLayerOrder(order: List<LayerRef>, scene: StreamScene): List<LayerRef> {
        val imageIds = scene.images.map { it.id }.toSet()
        val nativeWidgetsById = scene.nativeWidgets.associateBy { it.id }
        val seen = linkedSetOf<String>()
        val result = mutableListOf<LayerRef>()

        fun tryAdd(ref: LayerRef) {
            val key = ref.storageKey()
            if (key in seen) return
            when {
                ref.type == WidgetType.IMAGE -> {
                    val id = ref.instanceId ?: return
                    if (id !in imageIds) return
                }
                ref.type in NativeWidgetComponent.NATIVE_TYPES -> {
                    val id = ref.instanceId ?: return
                    if (nativeWidgetsById[id]?.type != ref.type) return
                }
                ref.type in singletonTypes -> if (!singletonPresent(scene, ref.type)) return
            }
            seen.add(key)
            result.add(ref)
        }

        // Un arrière-plan reste toujours sous toutes les autres couches.
        order.filterNot { it.type == WidgetType.BACKGROUND }.forEach { tryAdd(it) }
        // Images absentes de l’ordre → devant (après celles déjà placées).
        scene.images.forEach { img -> tryAdd(LayerRef.image(img.id)) }
        // Les widgets natifs absents de l'ordre sont restaurés, sauf l'arrière-plan placé au fond.
        scene.nativeWidgets.filter { it.type != WidgetType.BACKGROUND }.forEach { widget ->
            tryAdd(LayerRef.instance(widget.type, widget.id))
        }
        singletonTypes.filter { singletonPresent(scene, it) }.forEach { tryAdd(LayerRef.singleton(it)) }
        scene.nativeWidgets.filter { it.type == WidgetType.BACKGROUND }.forEach { widget ->
            tryAdd(LayerRef.instance(widget.type, widget.id))
        }
        return result
    }

    /** Sous-ensemble visuel (front→back) pour aperçu et filtres GL. */
    fun visualLayerOrder(order: List<LayerRef>, scene: StreamScene): List<LayerRef> =
        normalizeLayerOrder(order, scene).filter { it.type in visualTypes }

    /** Place [ref] tout devant (index 0), sauf l'arrière-plan qui reste au fond. */
    fun bringToFront(order: List<LayerRef>, ref: LayerRef, scene: StreamScene): List<LayerRef> {
        val normalized = normalizeLayerOrder(order, scene).toMutableList()
        if (ref.type == WidgetType.BACKGROUND) return normalized
        normalized.removeAll { it.storageKey() == ref.storageKey() }
        normalized.add(0, ref)
        return normalizeLayerOrder(normalized, scene)
    }

    fun bringToFront(order: List<LayerRef>, type: WidgetType, scene: StreamScene): List<LayerRef> {
        require(!type.instanceBased) { "Use bringToFront with LayerRef for instance-based widgets" }
        return bringToFront(order, LayerRef.singleton(type), scene)
    }
}
