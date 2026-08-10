/*
 * Adapted from RootEncoder's SurfaceFilterRender and BaseObjectFilterRender.
 * Copyright (C) 2024 pedroSG94. Licensed under the Apache License, Version 2.0.
 */
package fr.nicovers06.streamstudio.stream

import android.content.Context
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.input.gl.Sprite
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import com.pedro.encoder.utils.gl.TranslateTo
import fr.nicovers06.streamstudio.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Filtre de surface qui rogne la vidéo au centre pour remplir son cadre, sans étirement.
 *
 * RootEncoder utilise la même coordonnée pour copier la scène et échantillonner la surface objet.
 * Ce filtre sépare les deux : la scène reste intacte et seule la texture vidéo reçoit le crop.
 */
class CoverSurfaceFilterRender(
    private val surfaceReadyCallback: SurfaceReadyCallback? = null,
) : BaseObjectFilterRender() {
    fun interface SurfaceReadyCallback {
        fun surfaceReady(surfaceTexture: SurfaceTexture)
    }

    private val objectSprite = Sprite()
    private val objectVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(objectSprite.transformedVertices.size * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(objectSprite.transformedVertices).position(0) }

    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var aTextureObjectHandle = -1
    private var uObjectMatrixHandle = -1
    private var uSamplerHandle = -1
    private var uObjectHandle = -1
    private var alphaHandle = -1
    private var overlayAlpha = 1f

    private lateinit var inputSurfaceTexture: SurfaceTexture
    private var inputSurface: Surface? = null
    private val objectTransform = FloatArray(16)

    @Volatile
    private var sourceAspect = 1f

    @Volatile
    private var targetAspect = 1f

    fun setSourceAspectRatio(value: Float) {
        if (value.isFinite() && value > 0f) sourceAspect = value
    }

    fun setTargetAspectRatio(value: Float) {
        if (value.isFinite() && value > 0f) targetAspect = value
    }

    override fun initGlFilter(context: Context) {
        val vertexShader = GlUtil.getStringFromRaw(context, R.raw.media_cover_vertex)
        val fragmentShader = GlUtil.getStringFromRaw(context, R.raw.media_cover_fragment)
        program = GlUtil.createProgram(vertexShader, fragmentShader)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        aTextureObjectHandle = GLES20.glGetAttribLocation(program, "aTextureObjectCoord")
        uObjectMatrixHandle = GLES20.glGetUniformLocation(program, "uObjectMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uObjectHandle = GLES20.glGetUniformLocation(program, "uObject")
        alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")

        GlUtil.createExternalTextures(streamObjectTextureId.size, streamObjectTextureId, 0)
        inputSurfaceTexture = SurfaceTexture(streamObjectTextureId[0]).apply {
            setDefaultBufferSize(getWidth(), getHeight())
        }
        inputSurface = Surface(inputSurfaceTexture)
        surfaceReadyCallback?.let { callback ->
            Handler(Looper.getMainLooper()).post { callback.surfaceReady(inputSurfaceTexture) }
        }
    }

    override fun drawFilter() {
        inputSurfaceTexture.updateTexImage()
        val crop = MediaCoverCrop.centered(sourceAspect, targetAspect)
        // SurfaceFilterRender échantillonne la surface avec une matrice identité. Appliquer ici
        // SurfaceTexture.getTransformMatrix() ajoute une seconde inversion verticale au média.
        // La matrice personnalisée ne doit donc contenir que le crop centré.
        crop.writeTextureMatrix(objectTransform)

        GLES20.glUseProgram(program)
        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES,
            squareVertex,
        )
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES,
            squareVertex,
        )
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        objectVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aTextureObjectHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            2 * FLOAT_SIZE_BYTES,
            objectVertexBuffer,
        )
        GLES20.glEnableVertexAttribArray(aTextureObjectHandle)

        GLES20.glUniformMatrix4fv(uObjectMatrixHandle, 1, false, objectTransform, 0)
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        GLES20.glUniform1i(uObjectHandle, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, streamObjectTextureId[0])
        GLES20.glUniform1f(alphaHandle, overlayAlpha)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTextureHandle, aTextureObjectHandle, aPositionHandle)
    }

    override fun release() {
        if (program >= 0) GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(streamObjectTextureId.size, streamObjectTextureId, 0)
        if (::inputSurfaceTexture.isInitialized) inputSurfaceTexture.release()
        inputSurface?.release()
        inputSurface = null
        objectSprite.reset()
    }

    override fun setAlpha(alpha: Float) {
        overlayAlpha = alpha
    }

    override fun setScale(scaleX: Float, scaleY: Float) {
        objectSprite.scale(scaleX, scaleY)
        updateObjectVertices()
    }

    override fun setPosition(x: Float, y: Float) {
        objectSprite.translate(x, y)
        updateObjectVertices()
    }

    override fun setPosition(positionTo: TranslateTo) {
        objectSprite.translate(positionTo)
        updateObjectVertices()
    }

    override fun getScale(): PointF = objectSprite.scale

    override fun getPosition(): PointF = objectSprite.translation

    override fun setRotation(angle: Int) {
        objectSprite.rotation = angle
        updateObjectVertices()
    }

    override fun getRotation(): Int = objectSprite.rotation

    fun getSurfaceTexture(): SurfaceTexture = inputSurfaceTexture

    private fun updateObjectVertices() {
        objectVertexBuffer.clear()
        objectVertexBuffer.put(objectSprite.transformedVertices).position(0)
    }
}
