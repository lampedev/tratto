package com.caro.tratto.export

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.caro.tratto.data.NotebookRepository
import com.caro.tratto.model.NotebookMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Copia dei quaderni in una cartella scelta dall'utente, anche nel cloud (OneDrive, Drive…),
 * attraverso lo Storage Access Framework: la scrittura la fa l'app del cloud, come con "Condividi".
 * Ogni quaderno diventa un PDF e le cartelle di Tratto diventano cartelle vere. È una copia a senso
 * unico, Tratto → cartella.
 *
 * In sync.json si ricorda dove ha scritto ogni quaderno (percorso di nomi, non l'id del documento:
 * con alcuni provider l'id cambia quando si rinomina una cartella) e quale versione, così:
 * - riscrive solo i quaderni cambiati;
 * - se un quaderno è rinominato, spostato o eliminato, toglie la copia vecchia;
 * - non tocca mai un file che non ha scritto lui (stesso nome già presente → "Titolo (2).pdf"),
 *   e cancella o rinomina solo cartelle create da lui.
 */
class FolderSync(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val stateFile = File(this.context.filesDir, "sync.json")

    /** Cartella di destinazione; [label] è da mostrare ("OneDrive › Tratto"). */
    class Target(val uri: Uri, val label: String, val lastSync: Long)

    class Result(val written: Int, val unchanged: Int, val removed: Int, val failed: List<String>, val cancelled: Boolean)

    /** [links]: dove stavano i PDF dei quaderni collegati quando si è scritto questo (se cambiano, si riscrive). */
    private class NbRecord(val path: List<String>, val updatedAt: Long, val links: String = "")

    /** [created]: la cartella l'ha creata Tratto (solo allora si può rinominare o togliere). */
    private class DirRecord(val path: List<String>, val created: Boolean)

    private class State(
        var tree: String? = null,
        var label: String = "",
        var lastSync: Long = 0,
        val notebooks: MutableMap<String, NbRecord> = HashMap(),
        val folders: MutableMap<String, DirRecord> = HashMap(),
    )

    /** La cartella scelta, se Tratto ha ancora il permesso di scriverci. */
    fun target(): Target? {
        val st = load()
        val uri = st.tree?.let(Uri::parse) ?: return null
        if (!hasPermission(uri)) return null
        return Target(uri, st.label, st.lastSync)
    }

    /** Nuova cartella (dal selettore di Android): il permesso resta anche dopo il riavvio. */
    fun setTarget(uri: Uri) {
        resolver.takePersistableUriPermission(uri, RW)
        val old = load()
        val st = if (old.tree == uri.toString()) old else State(tree = uri.toString())
        old.tree?.takeIf { it != uri.toString() }?.let { prev -> runCatching { resolver.releasePersistableUriPermission(Uri.parse(prev), RW) } }
        st.label = describe(uri)
        save(st)
    }

    fun clearTarget() {
        load().tree?.let { prev -> runCatching { resolver.releasePersistableUriPermission(Uri.parse(prev), RW) } }
        stateFile.delete()
    }

    /**
     * Allinea la cartella alla libreria. Gli errori di un singolo quaderno non fermano gli altri;
     * IOException se la cartella non è più raggiungibile.
     */
    fun sync(repo: NotebookRepository, cancelled: () -> Boolean, onProgress: (done: Int, total: Int, title: String) -> Unit): Result {
        val st = load()
        val treeUri = st.tree?.let(Uri::parse) ?: throw IOException("nessuna cartella scelta")
        if (!hasPermission(treeUri)) throw IOException("Tratto non ha più il permesso di scrivere nella cartella: sceglila di nuovo")
        val tree = Tree(resolver, treeUri)
        tree.checkRoot()
        val notebooks = repo.list()
        val folders = repo.folders()

        // 1. nomi delle cartelle: fratelli con lo stesso nome → "Nome (2)"; mai il nome di un file che c'è già.
        //    Chi è già al suo posto tiene il nome senza leggere il cloud (ogni lettura è un giro in rete).
        val folderPath = HashMap<String, List<String>>()
        fun assignFolders(parentId: String?, parentPath: List<String>) {
            val kids = folders.filter { it.parentId == parentId }.sortedWith(compareBy({ it.createdAt }, { it.id }))
            val used = HashSet<String>()
            val pending = ArrayList<com.caro.tratto.model.Folder>()
            for (f in kids) {
                val rec = st.folders[f.id]
                if (rec != null && key(rec.path.dropLast(1)) == key(parentPath) && fits(rec.path.last(), cleanName(f.name), "") && used.add(rec.path.last().lowercase())) {
                    folderPath[f.id] = rec.path
                } else {
                    pending += f
                }
            }
            if (pending.isNotEmpty()) {
                val files = tree.find(parentPath)?.takeIf { it.isDir }?.let { tree.children(it.id) }.orEmpty()
                    .values.filter { !it.isDir }.mapTo(HashSet()) { it.name.lowercase() }
                for (f in pending) folderPath[f.id] = parentPath + unique(cleanName(f.name), "", used) { it.lowercase() !in files }
            }
            for (f in kids) assignFolders(f.id, folderPath.getValue(f.id))
        }
        assignFolders(null, emptyList())
        fun dirPathOf(nb: NotebookMeta) = nb.folderId?.let(folderPath::get) ?: emptyList()

        // 2. cartelle rinominate in Tratto: si rinomina anche quella copiata (niente da ricaricare)
        folderPath.entries.sortedBy { it.value.size }.forEach { (id, now) ->
            val prev = st.folders[id] ?: return@forEach
            if (!prev.created || key(prev.path) == key(now) || prev.path.size != now.size) return@forEach
            if (key(prev.path.dropLast(1)) != key(now.dropLast(1))) return@forEach
            val old = tree.find(prev.path)?.takeIf { it.isDir } ?: return@forEach
            if (tree.find(now) != null) return@forEach
            if (tree.rename(old, now.last()) && tree.find(now)?.isDir == true) {
                replacePrefix(st, prev.path, now)
                save(st)
            }
        }

        // 3. nomi dei file: mai sopra un file che non è nostro. Anche qui chi è già al suo posto non legge il cloud.
        val ours = st.notebooks.values.mapTo(HashSet()) { key(it.path) }
        val nbPath = HashMap<String, List<String>>()
        for ((dirPath, list) in notebooks.groupBy(::dirPathOf)) {
            val used = folderPath.values.filter { it.size == dirPath.size + 1 && key(it.dropLast(1)) == key(dirPath) }
                .mapTo(HashSet()) { it.last().lowercase() }
            val pending = ArrayList<NotebookMeta>()
            for (nb in list.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
                val rec = st.notebooks[nb.id]
                if (rec != null && key(rec.path.dropLast(1)) == key(dirPath) && fits(rec.path.last(), cleanName(nb.title), ".pdf") && used.add(rec.path.last().lowercase())) {
                    nbPath[nb.id] = rec.path
                } else {
                    pending += nb
                }
            }
            if (pending.isEmpty()) continue
            val existing = tree.find(dirPath)?.takeIf { it.isDir }?.let { tree.children(it.id) }.orEmpty()
            for (nb in pending) {
                val name = unique(cleanName(nb.title), ".pdf", used) { candidate ->
                    val ex = existing[candidate.lowercase()]
                    ex == null || (!ex.isDir && key(dirPath + candidate) in ours)
                }
                nbPath[nb.id] = dirPath + name
            }
        }
        val assigned = nbPath.values.mapTo(HashSet()) { key(it) }

        // 4. scrittura dei quaderni cambiati
        val createdDirs = HashSet<String>()
        fun ensureDir(path: List<String>): String {
            var cur = tree.rootId
            for (j in path.indices) {
                val e = tree.children(cur)[path[j].lowercase()]
                cur = if (e != null && e.isDir) e.id else tree.create(cur, Document.MIME_TYPE_DIR, path[j]).also { createdDirs += key(path.take(j + 1)) }.id
            }
            return cur
        }
        // collegamenti fra quaderni: nel PDF diventano percorsi relativi verso il PDF dell'altro quaderno
        val byId = notebooks.associateBy { it.id }
        fun linkSig(nb: NotebookMeta) = nb.linkTargets.sorted().joinToString("|") { id -> nbPath[id]?.let(::key) ?: "-" }
        val targetPages = HashMap<String, List<String>>()   // id del quaderno → id delle sue pagine, in ordine
        fun remoteFor(from: List<String>): (com.caro.tratto.model.PageLink) -> PdfLinks.Remote? = remote@{ l ->
            val id = l.notebookId ?: return@remote null
            val to = nbPath[id] ?: return@remote null
            val meta = byId[id] ?: return@remote null
            val ids = targetPages.getOrPut(id) { runCatching { repo.readRaw(id).raw.pages.map { it.id } }.getOrDefault(emptyList()) }
            val page = ids.indexOf(l.pageId).takeIf { it >= 0 } ?: l.pageIndex.coerceIn(0, maxOf(0, meta.pageCount - 1))
            PdfLinks.Remote(relativePath(from.dropLast(1), to), page, meta.format.heightPt.toFloat(), pointUsable = !meta.fromPdf)
        }

        var written = 0
        var unchanged = 0
        var removed = 0
        val failed = ArrayList<String>()
        for ((i, nb) in notebooks.withIndex()) {
            if (cancelled()) return finish(st, folderPath, tree, createdDirs, Result(written, unchanged, removed, failed, cancelled = true))
            onProgress(i, notebooks.size, nb.title)
            val path = nbPath.getValue(nb.id)
            val rec = st.notebooks[nb.id]
            // già copiato così com'è: non si controlla nemmeno il cloud
            val sig = linkSig(nb)
            if (rec != null && key(rec.path) == key(path) && rec.updatedAt == nb.updatedAt && rec.links == sig) {
                unchanged++
                continue
            }
            try {
                val dirId = ensureDir(path.dropLast(1))
                val existing = tree.children(dirId)[path.last().lowercase()]?.takeIf { !it.isDir }
                val pdf = Exporter.exportPdf(context, repo.build(repo.readRaw(nb.id)).doc, remote = remoteFor(path))
                val name = try {
                    writeFile(tree, dirId, path.last(), existing, pdf)
                } finally {
                    pdf.delete()
                }
                val finalPath = path.dropLast(1) + name
                // rinominato o spostato: via la copia vecchia (se il posto non è stato preso da un altro)
                if (rec != null && key(rec.path) != key(finalPath) && key(rec.path) !in assigned) tree.deleteFile(rec.path)
                st.notebooks[nb.id] = NbRecord(finalPath, nb.updatedAt, sig)
                save(st)
                written++
            } catch (e: Exception) {
                android.util.Log.w("Tratto", "sync: ${nb.title}", e)
                failed += nb.title
            }
        }
        // anche le cartelle vuote (solo quelle nuove o spostate: le altre ci sono già)
        folderPath.forEach { (id, path) -> if (st.folders[id]?.let { key(it.path) } != key(path)) runCatching { ensureDir(path) } }

        // 5. quaderni eliminati in Tratto: via la loro copia
        val ids = notebooks.mapTo(HashSet()) { it.id }
        st.notebooks.keys.filter { it !in ids }.forEach { id ->
            val rec = st.notebooks.getValue(id)
            if (key(rec.path) !in assigned && tree.deleteFile(rec.path)) removed++
            st.notebooks.remove(id)
        }
        onProgress(notebooks.size, notebooks.size, "")
        return finish(st, folderPath, tree, createdDirs, Result(written, unchanged, removed, failed, cancelled = false))
    }

    /** Aggiorna le cartelle ricordate e toglie quelle nostre rimaste vuote (cartelle eliminate o spostate). */
    private fun finish(st: State, folderPath: Map<String, List<String>>, tree: Tree, createdDirs: Set<String>, result: Result): Result {
        val current = folderPath.values.mapTo(HashSet()) { key(it) }
        val stale = st.folders.filter { (id, rec) -> rec.created && key(rec.path) != folderPath[id]?.let(::key) }.values
        val next = HashMap<String, DirRecord>()
        stale.sortedByDescending { it.path.size }.forEach { rec ->
            if (key(rec.path) in current) return@forEach
            // non ancora vuota (sincronizzazione interrotta): si riprova la volta dopo
            if (!tree.deleteDirIfEmpty(rec.path) && tree.find(rec.path)?.isDir == true) next["old:" + key(rec.path)] = rec
        }
        for ((id, path) in folderPath) {
            val prev = st.folders[id]
            val created = key(path) in createdDirs || (prev != null && prev.created && key(prev.path) == key(path))
            if ((prev != null && key(prev.path) == key(path)) || key(path) in createdDirs || tree.find(path) != null) next[id] = DirRecord(path, created)
        }
        st.folders.clear()
        st.folders.putAll(next)
        if (!result.cancelled) st.lastSync = System.currentTimeMillis()
        save(st)
        return result
    }

    /** Scrive il PDF: sopra il file nostro se c'è, se no in un file nuovo. Restituisce il nome vero. */
    private fun writeFile(tree: Tree, dirId: String, name: String, existing: Tree.Entry?, pdf: File): String {
        if (existing != null) {
            val ok = runCatching {
                (resolver.openOutputStream(tree.docUri(existing.id), "wt") ?: throw IOException("non scrivibile")).use { out ->
                    pdf.inputStream().use { it.copyTo(out) }
                }
            }.isSuccess
            if (ok) return existing.name
            // il provider non sa riscrivere da capo: si rifà il file (una coda vecchia rovinerebbe il PDF)
            tree.delete(dirId, existing)
        }
        val e = tree.create(dirId, "application/pdf", name)
        (resolver.openOutputStream(tree.docUri(e.id), "w") ?: throw IOException("non scrivibile")).use { out ->
            pdf.inputStream().use { it.copyTo(out) }
        }
        return e.name
    }

    private fun replacePrefix(st: State, from: List<String>, to: List<String>) {
        val k = key(from)
        fun moved(p: List<String>) = if (p.size >= from.size && key(p.take(from.size)) == k) to + p.drop(from.size) else p
        st.notebooks.replaceAll { _, r -> NbRecord(moved(r.path), r.updatedAt, r.links) }
        st.folders.replaceAll { _, r -> DirRecord(moved(r.path), r.created) }
    }

    private fun hasPermission(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** "OneDrive › Tratto": app che ospita la cartella + nome della cartella. */
    private fun describe(uri: Uri): String {
        val auth = uri.authority.orEmpty()
        val app = when {
            "externalstorage" in auth -> "Memoria del tablet"
            "skydrive" in auth || "onedrive" in auth -> "OneDrive"
            "com.google.android.apps.docs" in auth -> "Google Drive"
            else -> runCatching {
                val pm = context.packageManager
                pm.resolveContentProvider(auth, 0)?.loadLabel(pm)?.toString()
            }.getOrNull()
        }
        val name = runCatching { Tree(resolver, uri).let { it.displayName(it.docUri(it.rootId)) } }.getOrNull()
        return listOfNotNull(app, name).joinToString(" › ").ifEmpty { "Cartella scelta" }
    }

    // ---------------- sync.json ----------------

    private fun load(): State = runCatching {
        val o = JSONObject(stateFile.readText())
        val st = State(o.optString("tree").ifBlank { null }, o.optString("label"), o.optLong("lastSync"))
        o.optJSONObject("notebooks")?.let { n ->
            n.keys().forEach { id ->
                n.getJSONObject(id).let { st.notebooks[id] = NbRecord(it.getJSONArray("path").strings(), it.optLong("updatedAt"), it.optString("links")) }
            }
        }
        o.optJSONObject("folders")?.let { f ->
            f.keys().forEach { id -> f.getJSONObject(id).let { st.folders[id] = DirRecord(it.getJSONArray("path").strings(), it.optBoolean("created")) } }
        }
        st
    }.getOrElse { State() }

    private fun save(st: State) {
        val n = JSONObject()
        st.notebooks.forEach { (id, r) -> n.put(id, JSONObject().put("path", JSONArray(r.path)).put("updatedAt", r.updatedAt).put("links", r.links)) }
        val f = JSONObject()
        st.folders.forEach { (id, r) -> f.put(id, JSONObject().put("path", JSONArray(r.path)).put("created", r.created)) }
        val o = JSONObject().put("tree", st.tree ?: "").put("label", st.label).put("lastSync", st.lastSync).put("notebooks", n).put("folders", f)
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(o.toString(1))
        if (!tmp.renameTo(stateFile)) throw IOException("sync.json non salvato")
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    /** Una cartella del provider vista attraverso il permesso sull'albero; i contenuti si leggono una volta. */
    private class Tree(private val resolver: ContentResolver, val uri: Uri) {
        class Entry(val id: String, val name: String, val isDir: Boolean)

        val rootId: String = DocumentsContract.getTreeDocumentId(uri)
        private val cache = HashMap<String, MutableMap<String, Entry>>()

        fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(uri, id)

        fun checkRoot() {
            val ok = runCatching {
                resolver.query(docUri(rootId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
            if (!ok) throw IOException("La cartella scelta non si raggiunge (eliminata o app del cloud disconnessa): sceglila di nuovo")
        }

        fun displayName(u: Uri): String? =
            resolver.query(u, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

        /** Contenuto di una cartella, per nome in minuscolo (OneDrive non distingue maiuscole e minuscole). */
        fun children(dirId: String): MutableMap<String, Entry> = cache.getOrPut(dirId) {
            val out = HashMap<String, Entry>()
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(uri, dirId)
            val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (true) {
                out.clear()
                val loading = (resolver.query(q, cols, null, null, null) ?: throw IOException("cartella illeggibile")).use { c ->
                    while (c.moveToNext()) {
                        val e = Entry(c.getString(0), c.getString(1).orEmpty(), c.getString(2) == Document.MIME_TYPE_DIR)
                        out[e.name.lowercase()] = e
                    }
                    c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
                }
                // i provider del cloud rispondono subito con quello che hanno e intanto chiedono il resto al server
                if (!loading || SystemClock.uptimeMillis() > deadline) break
                Thread.sleep(400)
            }
            out
        }

        fun find(path: List<String>): Entry? {
            var cur = Entry(rootId, "", isDir = true)
            for (name in path) {
                if (!cur.isDir) return null
                cur = children(cur.id)[name.lowercase()] ?: return null
            }
            return cur
        }

        fun create(parentId: String, mime: String, name: String): Entry {
            val u = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name) ?: throw IOException("impossibile creare “$name”")
            val e = Entry(DocumentsContract.getDocumentId(u), runCatching { displayName(u) }.getOrNull() ?: name, mime == Document.MIME_TYPE_DIR)
            children(parentId)[e.name.lowercase()] = e
            return e
        }

        fun delete(parentId: String, e: Entry): Boolean {
            val ok = runCatching { DocumentsContract.deleteDocument(resolver, docUri(e.id)) }.getOrDefault(false)
            if (ok) {
                children(parentId).remove(e.name.lowercase())
                cache.remove(e.id)
            }
            return ok
        }

        fun deleteFile(path: List<String>): Boolean {
            val parent = find(path.dropLast(1))?.takeIf { it.isDir } ?: return false
            val e = children(parent.id)[path.last().lowercase()]?.takeIf { !it.isDir } ?: return false
            return delete(parent.id, e)
        }

        fun deleteDirIfEmpty(path: List<String>): Boolean {
            if (path.isEmpty()) return false
            val parent = find(path.dropLast(1))?.takeIf { it.isDir } ?: return false
            val e = children(parent.id)[path.last().lowercase()]?.takeIf { it.isDir } ?: return false
            if (children(e.id).isNotEmpty()) return false
            return delete(parent.id, e)
        }

        /** Con alcuni provider rinominare cambia gli id di tutto il contenuto: si rilegge da capo. */
        fun rename(e: Entry, name: String): Boolean {
            val ok = runCatching { DocumentsContract.renameDocument(resolver, docUri(e.id), name) }.isSuccess
            cache.clear()
            return ok
        }
    }

    private companion object {
        const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (0..9).flatMap { listOf("COM$it", "LPT$it") }

        fun key(path: List<String>) = path.joinToString("/") { it.lowercase() }

        /** Da un file nella cartella [fromDir] al file [to] (percorsi di nomi dalla cartella scelta): "../Chimica/x.pdf". */
        fun relativePath(fromDir: List<String>, to: List<String>): String {
            var common = 0
            while (common < fromDir.size && common < to.size - 1 && fromDir[common].equals(to[common], ignoreCase = true)) common++
            return (List(fromDir.size - common) { ".." } + to.drop(common)).joinToString("/")
        }

        /** Nome valido per OneDrive/Windows: niente \ / : * ? " < > |, niente spazi o punti in fondo. */
        fun cleanName(raw: String): String {
            var s = raw.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "-").trim().trimEnd('.').trim()
            if (s.length > 100) s = s.take(100).trim()
            if (s.isEmpty()) s = "Senza titolo"
            if (s.uppercase() in RESERVED || s.startsWith("~$")) s = "_$s"
            return s
        }

        /** [name] è "base[ext]" o "base (k)[ext]": il nome dato prima va ancora bene. */
        fun fits(name: String, base: String, ext: String): Boolean {
            val n = name.lowercase()
            val b = base.lowercase()
            val e = ext.lowercase()
            if (n == b + e) return true
            if (!n.startsWith("$b (") || !n.endsWith(")$e")) return false
            return n.substring(b.length + 2, n.length - e.length - 1).toIntOrNull()?.let { it >= 2 } == true
        }

        /** Primo nome libero fra "base", "base (2)", "base (3)"…; [used] in minuscolo. */
        fun unique(base: String, ext: String, used: MutableSet<String>, ok: (String) -> Boolean = { true }): String {
            var k = 1
            while (true) {
                val name = if (k == 1) "$base$ext" else "$base ($k)$ext"
                if (name.lowercase() !in used && ok(name)) {
                    used += name.lowercase()
                    return name
                }
                k++
            }
        }
    }
}
