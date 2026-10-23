package com.caro.tratto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.caro.tratto.export.FolderSync

/** Avanzamento della sincronizzazione: quaderno [done] + 1 di [total]. */
internal class SyncProgress(val done: Int, val total: Int, val title: String, val stopping: Boolean = false)

/** Esito da mostrare: [result] se è andata, [error] se la cartella non si raggiunge. */
internal class SyncOutcome(val result: FolderSync.Result?, val error: String?, val label: String?)

/** Prima volta: cosa succede e dove scegliere la cartella. */
@Composable
internal fun SyncIntroDialog(onPick: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sincronizza con una cartella") },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Scegli la cartella, anche su OneDrive: nel selettore apri il menu ☰ e tocca OneDrive. " +
                        "Meglio una cartella nuova, per esempio “Tratto” (si crea dal menu ⋮ del selettore).",
                )
                Text(
                    "Tratto ci copia il PDF di ogni quaderno, con le stesse cartelle che hai qui. Le volte dopo basta " +
                        "toccare di nuovo questo pulsante: aggiorna i quaderni cambiati, sposta o toglie le copie dei " +
                        "quaderni spostati o eliminati e non tocca gli altri file.",
                )
                Text(
                    "È una copia a senso unico: le modifiche fatte nella cartella non tornano in Tratto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onPick) { Text("Scegli cartella") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
internal fun SyncProgressDialog(progress: SyncProgress, label: String?, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Sincronizzazione…") },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress.total > 0) {
                    LinearProgressIndicator(progress = { progress.done / progress.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    when {
                        progress.stopping -> "Mi fermo dopo questo quaderno…"
                        progress.total == 0 -> "Guardo cosa c'è nella cartella…"
                        progress.done >= progress.total -> "Riordino la cartella…"
                        else -> "Quaderno ${progress.done + 1} di ${progress.total} · ${progress.title}"
                    },
                )
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onStop, enabled = !progress.stopping) { Text("Interrompi") } },
    )
}

@Composable
internal fun SyncOutcomeDialog(outcome: SyncOutcome, onPick: () -> Unit, onDismiss: () -> Unit) {
    val r = outcome.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    r == null -> "Sincronizzazione non riuscita"
                    r.cancelled -> "Sincronizzazione interrotta"
                    r.failed.isNotEmpty() -> "Sincronizzazione fatta in parte"
                    else -> "Sincronizzazione fatta"
                },
            )
        },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r == null) {
                    Text(outcome.error.orEmpty().replaceFirstChar { it.uppercase() })
                } else {
                    Text(
                        when (r.written) {
                            0 -> "Nessun quaderno da aggiornare"
                            1 -> "Aggiornato 1 quaderno"
                            else -> "Aggiornati ${r.written} quaderni"
                        },
                    )
                    if (r.written > 0 && r.unchanged > 0) Text("Già a posto: ${r.unchanged}")
                    if (r.removed > 0) Text("Copie tolte (quaderni eliminati): ${r.removed}")
                    if (r.failed.isNotEmpty()) Text("Non riusciti: ${r.failed.joinToString(", ")} (riprova più tardi)")
                }
                if (outcome.label != null) {
                    Text(
                        "Cartella: ${outcome.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = if (r == null) {
            { TextButton(onClick = onPick) { Text("Scegli cartella") } }
        } else {
            null
        },
    )
}

/** Sezione "Sincronizzazione" delle impostazioni generali. */
@Composable
internal fun SyncSettingsSection(target: FolderSync.Target?, onPick: () -> Unit, onUnlink: () -> Unit) {
    Text(
        "Sincronizzazione",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
    )
    Text(target?.label ?: "Nessuna cartella scelta", style = MaterialTheme.typography.bodyLarge)
    if (target != null) {
        Text(
            if (target.lastSync > 0) "Ultima sincronizzazione: ${formatDate(target.lastSync)}" else "Non ancora sincronizzata",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "Il PDF di ogni quaderno, con le stesse cartelle, in una cartella a tua scelta (anche su OneDrive). " +
            "Si aggiorna con il pulsante ☁ in alto nella libreria.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row {
        TextButton(onClick = onPick) { Text(if (target == null) "Scegli cartella" else "Cambia cartella") }
        if (target != null) TextButton(onClick = onUnlink) { Text("Scollega") }
    }
}
