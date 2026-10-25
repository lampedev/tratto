package com.caro.tratto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Piano di riordino: un file JSON (`"tratto": "piano"`) preparato fuori dall'app, per esempio da Claude
 * leggendo le note, con le cartelle da creare, dove mettere ogni quaderno e i collegamenti da aggiungere.
 * I quaderni si riconoscono dalla nota di Samsung Notes da cui sono nati ("creazione|modifica|titolo",
 * la stessa chiave di samsung_imports.json) o, se manca, dal titolo. Zone e punti in punti pagina.
 */
internal object PlanFile {

    class Folder(val path: List<String>, val color: Int?)

    class Notebook(val note: String, val title: String, val folder: List<String>)

    class Link(
        val from: String,
        val page: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val to: String,
        val toPage: Int,
        val pointX: Float,
        val pointY: Float,
    )

    class Plan(val title: String, val folders: List<Folder>, val notebooks: List<Notebook>, val links: List<Link>)

    /** Il piano, o null se il testo non è un piano di Tratto. */
    fun parse(text: String): Plan? = runCatching {
        val o = JSONObject(text)
        if (o.optString("tratto") != "piano") return null
        Plan(
            title = o.optString("titolo").ifBlank { "Piano di riordino" },
            folders = o.optJSONArray("cartelle").objects().map { f ->
                Folder(f.getJSONArray("percorso").strings(), f.optString("colore").takeIf { it.isNotBlank() }?.let(::parseColor))
            },
            notebooks = o.optJSONArray("quaderni").objects().map { n ->
                Notebook(n.getString("nota"), n.optString("titolo"), n.optJSONArray("cartella").strings())
            },
            links = o.optJSONArray("collegamenti").objects().map { l ->
                val zone = l.getJSONArray("zona")
                val point = l.optJSONArray("punto")
                Link(
                    from = l.getString("da"),
                    page = l.getInt("pagina"),
                    x = zone.getDouble(0).toFloat(), y = zone.getDouble(1).toFloat(),
                    w = zone.getDouble(2).toFloat(), h = zone.getDouble(3).toFloat(),
                    to = l.getString("a"),
                    toPage = l.optInt("paginaArrivo", 0),
                    pointX = point?.getDouble(0)?.toFloat() ?: Float.NaN,
                    pointY = point?.getDouble(1)?.toFloat() ?: Float.NaN,
                )
            },
        )
    }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }.filter { it.isNotBlank() }

    /** "#RRGGBB" → ARGB opaco. */
    private fun parseColor(s: String): Int? = s.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (it or 0xFF000000L).toInt() }
}
