package fr.nicovers06.streamstudio.stream

/** Portion centrée de la texture source à conserver pour remplir un cadre sans étirement. */
data class MediaCoverCrop(
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    companion object {
        val FULL = MediaCoverCrop(
            scaleX = 1f,
            scaleY = 1f,
            offsetX = 0f,
            offsetY = 0f,
        )

        fun centered(sourceAspect: Float, targetAspect: Float): MediaCoverCrop {
            if (!sourceAspect.isFinite() || sourceAspect <= 0f ||
                !targetAspect.isFinite() || targetAspect <= 0f
            ) {
                return FULL
            }
            return when {
                sourceAspect > targetAspect -> {
                    val visibleWidth = (targetAspect / sourceAspect).coerceIn(0f, 1f)
                    MediaCoverCrop(
                        scaleX = visibleWidth,
                        scaleY = 1f,
                        offsetX = (1f - visibleWidth) / 2f,
                        offsetY = 0f,
                    )
                }
                sourceAspect < targetAspect -> {
                    val visibleHeight = (sourceAspect / targetAspect).coerceIn(0f, 1f)
                    MediaCoverCrop(
                        scaleX = 1f,
                        scaleY = visibleHeight,
                        offsetX = 0f,
                        offsetY = (1f - visibleHeight) / 2f,
                    )
                }
                else -> FULL
            }
        }
    }
}
