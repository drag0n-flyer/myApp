package org.example.app.ui.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One open-source component. Its license texts live in [OssBook.texts], by id. */
data class OssComponent(
    val name: String,
    val version: String,
    val license: String,
    val authors: String,
    val repository: String?,
    val textIds: List<String>,
    /** True while the full license text is still to come with the component's first release. */
    val pending: Boolean,
) {
    val id: String get() = "$name $version"
}

data class OssBook(val components: List<OssComponent>, val texts: Map<String, String>) {
    /** Every text a component references, separated by a rule. */
    fun text(component: OssComponent): String =
        component.textIds.mapNotNull { texts[it] }.joinToString("\n\n----\n\n")
}

/** Reads `assets/oss-notices.json`, which `scripts/generate-oss-notices.py` writes. */
object OssNotices {
    const val ASSET = "oss-notices.json"

    fun parse(json: String): OssBook {
        val root = JSONObject(json)
        val texts = root.getJSONObject("texts").let { t -> t.keys().asSequence().associateWith { t.getString(it) } }
        val components = root.getJSONArray("components").let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                OssComponent(
                    name = c.getString("name"),
                    version = c.getString("version"),
                    license = c.getString("license"),
                    authors = c.optString("authors", ""),
                    repository = if (c.isNull("repository")) null else c.getString("repository"),
                    textIds = c.optJSONArray("textIds").strings(),
                    pending = c.optBoolean("pending", false),
                )
            }
        }
        return OssBook(components, texts)
    }

    fun load(context: Context): OssBook =
        parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
