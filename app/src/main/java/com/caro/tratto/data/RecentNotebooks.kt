package com.caro.tratto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Quaderni aperti di recente, il più recente per primo (filesDir/recent.json), ognuno con l'ultima
 * pagina guardata: dalla libreria si riapre proprio lì.
 */
class RecentNotebooks(context: Context) {

    class Entry(val notebookId: String, val page: Int, val time: Long)

    private val file = File(context.filesDir, "recent.json")

    init {
        // miniature delle pagine della versione 0.32–0.40: non servono più
        File(context.filesDir, "recent").takeIf { it.exists() }?.deleteRecursively()
    }

    fun list(): List<Entry> = synchronized(LOCK) { read() }

    /** Il quaderno va in cima, con la pagina che si sta guardando. */
    fun record(notebookId: String, page: Int) = synchronized(LOCK) {
        val list = listOf(Entry(notebookId, page, System.currentTimeMillis())) + read().filter { it.notebookId != notebookId }
        write(list.take(MAX))
    }

    fun clear() = synchronized(LOCK) { file.delete() }

    /** Toglie i quaderni che non esistono più. */
    fun prune(existing: Set<String>) = synchronized(LOCK) {
        val all = read()
        val keep = all.filter { it.notebookId in existing }
        if (keep.size != all.size) write(keep)
    }

    // un quaderno una volta sola (i file vecchi avevano una voce per pagina: vale la più recente)
    private fun read(): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("nb"), o.getInt("page"), o.getLong("time"))
        }.distinctBy { it.notebookId }
    }.getOrDefault(emptyList())

    private fun write(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("nb", it.notebookId).put("page", it.page).put("time", it.time)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX = 40
        val LOCK = Any()
    }
}
