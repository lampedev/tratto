package com.caro.tratto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.caro.tratto.data.NotebookRepository
import com.caro.tratto.ui.EditorScreen
import com.caro.tratto.ui.GraphScreen
import com.caro.tratto.ui.LibraryScreen
import com.caro.tratto.ui.TrattoTheme

class MainActivity : ComponentActivity() {

    /** File arrivati da un'altra app ("Apri con" / "Condividi"): PDF o note di Samsung Notes, li importa la libreria. */
    private var incoming by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMaxRefreshRate()
        if (savedInstanceState == null) incoming = filesFrom(intent)
        val repository = NotebookRepository(applicationContext)
        setContent {
            TrattoTheme {
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                var openPage by rememberSaveable { mutableStateOf(0) }
                var recentView by rememberSaveable { mutableStateOf(false) }
                var libraryVersion by remember { mutableStateOf(0) }
                var folder by rememberSaveable { mutableStateOf<String?>(null) }
                var favoritesView by rememberSaveable { mutableStateOf(false) }
                var search by rememberSaveable { mutableStateOf<String?>(null) }
                var tagView by rememberSaveable { mutableStateOf<String?>(null) }
                // grafo dei collegamenti: aprendo un quaderno da lì, chiudendolo si torna al grafo
                var graphView by rememberSaveable { mutableStateOf(false) }
                // arrivo da un collegamento: pagina per id e punto; pila dei ritorni "quaderno|pagina"
                var openPageId by rememberSaveable { mutableStateOf<String?>(null) }
                var openPointX by rememberSaveable { mutableStateOf(Float.NaN) }
                var openPointY by rememberSaveable { mutableStateOf(Float.NaN) }
                var linkBack by rememberSaveable { mutableStateOf("") }
                fun open(id: String, page: Int, pageId: String? = null, x: Float = Float.NaN, y: Float = Float.NaN) {
                    openPage = page
                    openPageId = pageId
                    openPointX = x
                    openPointY = y
                    openId = id
                }
                fun closeEditor() {
                    openId = null
                    linkBack = ""
                    libraryVersion++
                }
                // un file in arrivo chiude l'editor (che salva uscendo) e passa dalla libreria
                LaunchedEffect(incoming) {
                    if (incoming.isNotEmpty()) graphView = false
                    if (incoming.isNotEmpty() && openId != null) {
                        openId = null
                        libraryVersion++
                    }
                }
                val id = openId
                if (id == null && graphView) {
                    BackHandler { graphView = false }
                    GraphScreen(repository = repository, onBack = { graphView = false }, onOpen = { open(it, 0) })
                } else if (id == null) {
                    LibraryScreen(
                        repository = repository,
                        refreshKey = libraryVersion,
                        currentFolder = folder,
                        onFolderChange = { folder = it },
                        onOpen = { open(it, 0) },
                        onOpenPage = { id, p -> open(id, p) },
                        recentView = recentView,
                        onRecentView = { recentView = it },
                        favoritesView = favoritesView,
                        onFavoritesView = { favoritesView = it },
                        search = search,
                        onSearch = { search = it },
                        tagView = tagView,
                        onTagView = { tagView = it },
                        incoming = incoming,
                        onIncomingHandled = { incoming = emptyList() },
                        onGraph = { graphView = true },
                    )
                } else {
                    BackHandler { closeEditor() }
                    val back = linkBack.lines().lastOrNull { it.isNotEmpty() }
                    // un quaderno diverso = un editor nuovo (lo stato non passa da un quaderno all'altro)
                    key(id) {
                        EditorScreen(
                            repository = repository,
                            notebookId = id,
                            onBack = { closeEditor() },
                            startPage = openPage,
                            startPageId = openPageId,
                            startPoint = if (openPointX.isNaN()) null else android.graphics.PointF(openPointX, openPointY),
                            backTo = back?.substringBefore('|'),
                            onLinkBack = {
                                if (back != null) {
                                    linkBack = linkBack.lines().filter { it.isNotEmpty() }.dropLast(1).joinToString("\n")
                                    open(back.substringBefore('|'), back.substringAfter('|').toIntOrNull() ?: 0)
                                }
                            },
                            onOpenLink = { fromPage, target, pageId, pageIndex, point ->
                                if (repository.list().none { it.id == target }) {
                                    android.widget.Toast.makeText(this@MainActivity, "Il quaderno collegato non c'è più", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    linkBack = (linkBack.lines().filter { it.isNotEmpty() } + "$id|$fromPage").takeLast(20).joinToString("\n")
                                    open(target, pageIndex, pageId, point?.x ?: Float.NaN, point?.y ?: Float.NaN)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        filesFrom(intent).takeIf { it.isNotEmpty() }?.let { incoming = it }
    }

    private fun filesFrom(intent: Intent?): List<Uri> {
        val listed = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return emptyList()
        }
        // qualche app mette i file solo nella ClipData
        val clip = intent.clipData
        return listed.ifEmpty { if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.distinct()
    }

    /** Il tratto segue la penna a ogni refresh: chiediamo il modo display più veloce (90 Hz sul Tab S9 FE). */
    private fun requestMaxRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }
}
