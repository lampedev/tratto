package com.caro.tratto.data

import android.content.Context
import android.graphics.Bitmap
import com.caro.tratto.export.PageRenderer
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.NotebookMeta
import com.caro.tratto.model.Folder
import com.caro.tratto.model.LineSpacing
import com.caro.tratto.model.PageBackground
import com.caro.tratto.model.PageFormat
import com.caro.tratto.model.PageModel
import com.caro.tratto.model.PageStyle
import com.caro.tratto.model.PaperColor
import com.caro.tratto.model.Tag
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Quaderni su disco: filesDir/notebooks/<id>/{meta.json, strokes.bin, strokes.bak}.
 * strokes.bin conserva gli input grezzi della penna (x, y, tempo, pressione, tilt) con la
 * serializzazione ufficiale di Ink: la geometria si rigenera identica a ogni caricamento.
 */
class NotebookRepository(context: Context) {

    private val root = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(id: String) = locks.getOrPut(id) { Any() }
    private fun dirOf(id: String) = File(root, id)

    fun list(): List<NotebookMeta> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun create(title: String, format: PageFormat, style: PageStyle, folderId: String? = null, favorite: Boolean = false): NotebookMeta {
        val now = System.currentTimeMillis()
        val meta = NotebookMeta(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Senza titolo" },
            format = format,
            style = style,
            createdAt = now,
            updatedAt = now,
            pageCount = 1,
            folderId = folderId,
            favorite = favorite,
        )
        val dir = dirOf(meta.id).apply { mkdirs() }
        synchronized(lockFor(meta.id)) {
            val pages = listOf(PageModel())
            atomicWrite(File(dir, STROKES)) { StrokesFile.write(it, pages) }
            writeMeta(dir, meta)
            writeThumbnail(dir, NotebookDocument(meta, pages.toMutableList()))
        }
        return meta
    }

    /** PDF importato del quaderno (esiste solo per i quaderni nati da un PDF). */
    fun pdfFile(id: String): File = File(dirOf(id), SOURCE_PDF)

    /**
     * Nuovo quaderno da un PDF (anche un libro scansionato): il file si copia nella cartella del
     * quaderno e ogni pagina del PDF diventa una pagina su cui scrivere. Il formato del quaderno è
     * la misura della prima pagina; le pagine di misura diversa si adattano dentro, centrate.
     * Lancia IOException con il motivo in italiano.
     */
    fun importPdf(context: Context, uri: android.net.Uri, folderId: String?, onProgress: (Float) -> Unit = {}): NotebookMeta {
        val id = UUID.randomUUID().toString()
        val dir = dirOf(id).apply { mkdirs() }
        try {
            val pdf = File(dir, SOURCE_PDF)
            val total = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("file non leggibile")
            input.use {
                FileOutputStream(pdf).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                    out.fd.sync()
                }
            }
            val (count, w, h) = try {
                PdfPages.inspect(pdf)
            } catch (e: SecurityException) {
                throw IOException("il PDF è protetto da password")
            } catch (e: Exception) {
                throw IOException("non è un PDF leggibile")
            }
            val now = System.currentTimeMillis()
            val meta = NotebookMeta(
                id = id,
                title = displayName(context, uri)?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")?.ifBlank { null } ?: "PDF",
                format = PageFormat.pdf(Math.round(w), Math.round(h)),
                style = PageStyle(background = PageBackground.LINES),
                createdAt = now,
                updatedAt = now,
                pageCount = count,
                folderId = folderId,
                fromPdf = true,
            )
            synchronized(lockFor(id)) {
                val pages = (0 until count).mapTo(ArrayList(count)) { PageModel(pdfPage = it) }
                atomicWrite(File(dir, STROKES)) { StrokesFile.write(it, pages) }
                writeMeta(dir, meta)
                writeThumbnail(dir, NotebookDocument(meta, pages))
            }
            return meta
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun displayName(context: Context, uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Quaderno letto dal disco ma non ancora ricostruito (vedi [build]). */
    internal class RawNotebook(val meta: NotebookMeta, val raw: StrokesFile.Raw, val fromBackup: Boolean)

    /** Quaderno pronto; [notice] spiega un eventuale recupero (copia di sicurezza, tratti saltati). */
    class Loaded(val doc: NotebookDocument, val notice: String?)

    /**
     * Prima metà dell'apertura, veloce: legge meta.json e strokes.bin. Se strokes.bin manca o è
     * rotto si usa la copia di sicurezza (il salvataggio precedente); il file rotto resta da parte.
     */
    internal fun readRaw(id: String): RawNotebook = synchronized(lockFor(id)) {
        val dir = dirOf(id)
        val meta = readMeta(dir)
        val main = File(dir, STROKES)
        val backup = File(dir, STROKES_BACKUP)
        val a = if (main.exists()) StrokesFile.parse(main.readBytes()) else null
        if (a != null && a.complete) return RawNotebook(meta, a, fromBackup = false)
        if (a != null) {
            val keep = File(dir, STROKES_BROKEN)
            if (!keep.exists()) runCatching { main.copyTo(keep) }
        }
        val b = if (backup.exists()) StrokesFile.parse(backup.readBytes()) else null
        when {
            b != null && (b.complete || b.strokeCount > (a?.strokeCount ?: -1)) -> RawNotebook(meta, b, fromBackup = true)
            a != null -> RawNotebook(meta, a, fromBackup = false)
            else -> RawNotebook(meta, StrokesFile.Raw(emptyList(), complete = true), fromBackup = false)
        }
    }

    /** Seconda metà, lenta: ricostruisce la geometria dei tratti su tutti i core. Fuori dal lock. */
    internal fun build(raw: RawNotebook, cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): Loaded {
        val threads = Runtime.getRuntime().availableProcessors()
        val built = StrokesFile.build(raw.raw, threads, cancelled, onProgress)
        val doc = NotebookDocument(raw.meta, built.pages.ifEmpty { mutableListOf(PageModel()) })
        val notes = buildList {
            if (raw.fromBackup) add("Ripristinata la copia di sicurezza dell'ultimo salvataggio")
            else if (!raw.raw.complete) add("File in parte danneggiato: recuperato tutto il leggibile")
            if (built.skipped > 0) add("${built.skipped} tratti illeggibili saltati")
        }
        return Loaded(doc, notes.joinToString(" · ").ifEmpty { null })
    }

    fun save(doc: NotebookDocument) {
        val id = doc.meta.id
        synchronized(lockFor(id)) {
            val dir = dirOf(id)
            if (!dir.exists()) return   // eliminato nel frattempo
            val t0 = android.os.SystemClock.uptimeMillis()
            atomicWrite(File(dir, STROKES), backup = File(dir, STROKES_BACKUP)) { StrokesFile.write(it, doc.pages) }
            val t1 = android.os.SystemClock.uptimeMillis()
            val meta = doc.meta.copy(
                updatedAt = System.currentTimeMillis(),
                pageCount = doc.pages.size,
                linkTargets = doc.pages.flatMap { it.links }.mapNotNull { it.notebookId }.distinct(),
            )
            writeMeta(dir, meta)
            doc.meta = meta
            writeThumbnail(dir, doc)
            android.util.Log.i("Tratto", "salvato: tratti ${t1 - t0} ms, copertina ${android.os.SystemClock.uptimeMillis() - t1} ms")
        }
    }

    /** File di un'immagine del quaderno. */
    fun imageFile(id: String, name: String): File = File(File(dirOf(id), IMAGES), name)

    /**
     * Copia un'immagine scelta dall'utente dentro il quaderno, ridotta a [maxSide] px sul lato lungo.
     * Ritorna (nome file, larghezza px, altezza px) o null se non leggibile.
     */
    fun importImage(context: Context, id: String, uri: android.net.Uri, maxSide: Int = 2048): Triple<String, Int, Int>? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val s = maxOf(info.size.width, info.size.height)
            if (s > maxSide) {
                val k = maxSide.toFloat() / s
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // PNG solo se c'è trasparenza vera; il resto (foto, screenshot) in WebP, che pesa molto meno
        val transparent = !ImageLoader.isOpaque(bmp)
        val name = UUID.randomUUID().toString() + if (transparent) ".png" else ".webp"
        val file = imageFile(id, name).apply { parentFile?.mkdirs() }
        atomicWrite(file) {
            if (transparent) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            } else {
                bmp.setHasAlpha(false)   // niente piano alfa nel file
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, it)
            }
        }
        Triple(name, bmp.width, bmp.height).also { bmp.recycle() }
    }.getOrNull()

    /** Copia un'immagine da un quaderno a un altro (incolla fra quaderni); ritorna il nuovo nome. */
    fun copyImage(fromId: String, toId: String, name: String): String? = runCatching {
        val src = imageFile(fromId, name)
        val newName = UUID.randomUUID().toString() + "." + src.extension
        val dst = imageFile(toId, newName).apply { parentFile?.mkdirs() }
        src.copyTo(dst)
        newName
    }.getOrNull()

    // foto rimpicciolite per le copertine: la copertina si rifà a ogni salvataggio, la foto no
    private val thumbImages = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun thumbImage(id: String, name: String): Bitmap? =
        thumbImages.get(name) ?: ImageLoader.decode(imageFile(id, name), 400)?.also { thumbImages.put(name, it) }

    /** Copertina (prima pagina) per la libreria; può non esistere per quaderni vecchi. */
    fun thumbnail(id: String): File = File(dirOf(id), THUMB)

    // pagine del PDF rimpicciolite per le copertine, come le foto
    private val thumbPdf = android.util.LruCache<String, PdfPages.Rendered>(6)

    private fun thumbPdfPage(id: String, page: Int): PdfPages.Rendered? {
        val key = "$id:$page"
        return thumbPdf.get(key) ?: PdfPages.renderOnce(pdfFile(id), page, THUMB_WIDTH)?.also { thumbPdf.put(key, it) }
    }

    private fun writeThumbnail(dir: File, doc: NotebookDocument) {
        runCatching {
            val bmp = PageRenderer.thumbnail(
                doc, 0, THUMB_WIDTH,
                images = { img -> thumbImage(doc.meta.id, img.file) },
                pdf = { page -> thumbPdfPage(doc.meta.id, page) },
            )
            try {
                atomicWrite(File(dir, THUMB)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bmp.recycle()
            }
        }
    }

    fun rename(id: String, title: String) = synchronized(lockFor(id)) {
        val dir = dirOf(id)
        val meta = readMeta(dir)
        writeMeta(dir, meta.copy(title = title.ifBlank { meta.title }, updatedAt = System.currentTimeMillis()))
    }

    /** Aggiunge o toglie dai preferiti; la data di modifica non cambia (l'ordine resta quello). */
    fun setFavorite(id: String, favorite: Boolean) = synchronized(lockFor(id)) {
        val dir = dirOf(id)
        if (dir.exists()) writeMeta(dir, readMeta(dir).copy(favorite = favorite))
    }

    /** Sposta un quaderno in un'altra cartella (null = radice). */
    fun moveNotebook(id: String, folderId: String?) = synchronized(lockFor(id)) {
        val dir = dirOf(id)
        writeMeta(dir, readMeta(dir).copy(folderId = folderId))
    }

    // ---------------- cartelle (filesDir/folders.json) ----------------

    private val foldersFile = File(root.parentFile, "folders.json")
    private val foldersLock = Any()

    fun folders(): List<Folder> = synchronized(foldersLock) { readFolders() }

    fun createFolder(name: String, parentId: String?, color: Int? = null): Folder = synchronized(foldersLock) {
        val f = Folder(UUID.randomUUID().toString(), name.ifBlank { "Nuova cartella" }, parentId, System.currentTimeMillis(), color)
        writeFolders(readFolders() + f)
        f
    }

    fun renameFolder(id: String, name: String) = synchronized(foldersLock) {
        writeFolders(readFolders().map { if (it.id == id && name.isNotBlank()) it.copy(name = name) else it })
    }

    /** Sposta una cartella dentro un'altra (null = radice); mai dentro sé stessa o una sua sottocartella. */
    fun moveFolder(id: String, parentId: String?) = synchronized(foldersLock) {
        val all = readFolders()
        var p = parentId
        while (p != null) {
            if (p == id) return@synchronized
            p = all.firstOrNull { it.id == p }?.parentId
        }
        writeFolders(all.map { if (it.id == id) it.copy(parentId = parentId) else it })
    }

    /** Colore della cartella (null = quello di serie). */
    fun setFolderColor(id: String, color: Int?) = synchronized(foldersLock) {
        writeFolders(readFolders().map { if (it.id == id) it.copy(color = color) else it })
    }

    /** Elimina la cartella: sottocartelle e quaderni risalgono alla cartella superiore. */
    fun deleteFolder(id: String) {
        val parent = synchronized(foldersLock) {
            val all = readFolders()
            val target = all.firstOrNull { it.id == id } ?: return
            writeFolders(all.filter { it.id != id }.map { if (it.parentId == id) it.copy(parentId = target.parentId) else it })
            target.parentId
        }
        list().filter { it.folderId == id }.forEach { moveNotebook(it.id, parent) }
    }

    private fun readFolders(): List<Folder> = runCatching {
        if (!foldersFile.exists()) return emptyList()
        val arr = org.json.JSONArray(foldersFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Folder(
                o.getString("id"),
                o.getString("name"),
                o.optString("parentId").ifBlank { null },
                o.optLong("createdAt"),
                if (o.has("color")) o.getInt("color") else null,
            )
        }
    }.getOrDefault(emptyList())

    private fun writeFolders(list: List<Folder>) {
        val arr = org.json.JSONArray()
        list.forEach {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: "").put("createdAt", it.createdAt)
            it.color?.let { c -> o.put("color", c) }
            arr.put(o)
        }
        atomicWrite(foldersFile) { it.write(arr.toString(2).toByteArray()) }
    }

    fun delete(id: String) {
        synchronized(lockFor(id)) { dirOf(id).deleteRecursively() }
    }

    // ---------------- tag (filesDir/tags.json) ----------------

    private val tagsFile = File(root.parentFile, "tags.json")
    private val tagsLock = Any()

    fun tags(): List<Tag> = synchronized(tagsLock) { readTags() }

    fun createTag(name: String, color: Int): Tag = synchronized(tagsLock) {
        val t = Tag(UUID.randomUUID().toString(), name.trim().ifBlank { "Tag" }, color)
        writeTags(readTags() + t)
        t
    }

    fun updateTag(id: String, name: String, color: Int) = synchronized(tagsLock) {
        writeTags(readTags().map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, color = color) else it })
    }

    /** Elimina il tag e lo toglie da tutti i quaderni (i quaderni restano). */
    fun deleteTag(id: String) {
        synchronized(tagsLock) { writeTags(readTags().filter { it.id != id }) }
        list().filter { id in it.tags }.forEach { setTag(it.id, id, false) }
    }

    /** Mette o toglie un tag a un quaderno; la data di modifica non cambia. */
    fun setTag(notebookId: String, tagId: String, on: Boolean) = synchronized(lockFor(notebookId)) {
        val dir = dirOf(notebookId)
        if (!dir.exists()) return@synchronized
        val m = readMeta(dir)
        val tags = if (on) (m.tags + tagId).distinct() else m.tags - tagId
        if (tags != m.tags) writeMeta(dir, m.copy(tags = tags))
    }

    private fun readTags(): List<Tag> = runCatching {
        if (!tagsFile.exists()) return emptyList()
        val arr = org.json.JSONArray(tagsFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Tag(o.getString("id"), o.getString("name"), o.getInt("color"))
        }
    }.getOrDefault(emptyList())

    private fun writeTags(list: List<Tag>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        atomicWrite(tagsFile) { it.write(arr.toString(2).toByteArray()) }
    }

    // ---------------- meta.json ----------------

    private fun readMeta(dir: File): NotebookMeta {
        val o = JSONObject(File(dir, META).readText())
        return NotebookMeta(
            id = o.getString("id"),
            title = o.getString("title"),
            format = PageFormat.byName(o.optString("format"), o.optInt("formatW"), o.optInt("formatH")),
            style = PageStyle(
                background = enumOr(o.optString("background"), PageBackground.LINES),
                paper = enumOr(o.optString("paper"), PaperColor.WHITE),
                spacing = enumOr(o.optString("spacing"), LineSpacing.MEDIUM),
                marginLine = o.optBoolean("marginLine", false),
                customPaper = o.optInt("customPaper", 0xFFFFFFFF.toInt()),
            ),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            pageCount = o.optInt("pageCount", 1),
            folderId = o.optString("folderId").ifBlank { null },
            fromPdf = o.optBoolean("fromPdf", false),
            favorite = o.optBoolean("favorite", false),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            linkTargets = o.optJSONArray("linkTargets")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }

    private fun writeMeta(dir: File, m: NotebookMeta) {
        val json = JSONObject()
            .put("version", 1)
            .put("id", m.id)
            .put("title", m.title)
            .put("format", m.format.name)
            .put("formatW", m.format.widthPt)
            .put("formatH", m.format.heightPt)
            .put("fromPdf", m.fromPdf)
            .put("background", m.style.background.name)
            .put("paper", m.style.paper.name)
            .put("spacing", m.style.spacing.name)
            .put("marginLine", m.style.marginLine)
            .put("customPaper", m.style.customPaper)
            .put("createdAt", m.createdAt)
            .put("updatedAt", m.updatedAt)
            .put("pageCount", m.pageCount)
            .put("folderId", m.folderId ?: "")
            .put("favorite", m.favorite)
            .put("tags", org.json.JSONArray(m.tags))
            .put("linkTargets", org.json.JSONArray(m.linkTargets))
        atomicWrite(File(dir, META)) { it.write(json.toString(2).toByteArray()) }
    }

    /**
     * Scrive su un file temporaneo e lo sostituisce al vecchio solo quando è completo e su disco.
     * Con [backup] la versione precedente diventa la copia di sicurezza (se l'app viene chiusa fra i
     * due passaggi, all'apertura si usa quella).
     */
    private fun atomicWrite(target: File, backup: File? = null, block: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            block(fos)
            fos.fd.sync()
        }
        if (backup != null && target.exists()) target.renameTo(backup)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("rename fallito: $target")
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val META = "meta.json"
        const val STROKES = "strokes.bin"
        const val STROKES_BACKUP = "strokes.bak"
        const val STROKES_BROKEN = "strokes.rotto"
        const val THUMB = "thumb.png"
        const val SOURCE_PDF = "source.pdf"
        const val IMAGES = "images"
        const val THUMB_WIDTH = 420
        /** WebP delle foto: ~80 è indistinguibile a schermo e pesa circa un terzo di un JPEG al 92. */
        const val WEBP_QUALITY = 80
    }
}
