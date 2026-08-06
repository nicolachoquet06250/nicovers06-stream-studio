package fr.nicovers06.streamstudio.data

import android.content.Context
import fr.nicovers06.streamstudio.model.StreamScene
import org.json.JSONArray

class SceneRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): MutableList<StreamScene> {
        val raw = preferences.getString(KEY_SCENES, null) ?: return mutableListOf(StreamScene())
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(StreamScene.fromJson(it)) }
                }
            }.toMutableList().ifEmpty { mutableListOf(StreamScene()) }
        }.getOrElse { mutableListOf(StreamScene()) }
    }

    fun save(scenes: List<StreamScene>) {
        val array = JSONArray().apply { scenes.forEach { put(it.toJson()) } }
        preferences.edit().putString(KEY_SCENES, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "studio_scenes"
        const val KEY_SCENES = "scenes_v1"
    }
}
