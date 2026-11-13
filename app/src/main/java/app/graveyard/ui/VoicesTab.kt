package app.graveyard.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.graveyard.Bench
import app.graveyard.GraveyardApp
import app.graveyard.suite.SectionTitle
import app.graveyard.suite.SettingsCard
import app.graveyard.suite.SettingsHint
import app.graveyard.text.Sentences
import app.graveyard.tts.VoiceSpec
import app.graveyard.tts.Voices
import app.graveyard.tts.Wav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bench: download a voice, read the same passage with it, and keep what it cost (generation
 * time over audio time, delay before the first sound). The voice ticked here is the one the Livre
 * and Webtoon tabs read with.
 */
@Composable
fun VoicesTab(app: GraveyardApp) {
    val hub = app.hub
    val speaker = app.speaker
    var sample by rememberSaveable { mutableStateOf(Bench.SAMPLE) }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var testing by remember { mutableStateOf<String?>(null) }
    // Bumped when a voice is downloaded or deleted, so the list reads the disk again.
    var revision by remember { mutableIntStateOf(0) }

    // A test that ends (or is stopped) leaves its measurements with the voice.
    LaunchedEffect(speaker.state.active, testing) {
        val id = testing
        if (!speaker.state.active && id != null) {
            speaker.state.error?.let { errors[id] = it } ?: app.bench.record(id, speaker.state.stats)
            testing = null
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            SectionTitle("Réglages du test")
            SettingsCard {
                Text(device(), fontWeight = FontWeight.SemiBold)
                Text("Fils d'exécution", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4, 6, 8).forEach { n ->
                        FilterChip(selected = hub.threads == n, onClick = { hub.changeThreads(n) }, label = { Text("$n") })
                    }
                }
                Text("Débit de la voix : ${"%.2f".format(hub.speed).replace('.', ',')}", style = MaterialTheme.typography.labelLarge)
                Slider(value = hub.speed, onValueChange = { hub.changeSpeed(it) }, valueRange = 0.7f..1.5f)
                OutlinedTextField(
                    value = sample, onValueChange = { sample = it },
                    label = { Text("Passage lu par « Tester »") }, modifier = Modifier.fillMaxWidth(), maxLines = 6,
                )
                SettingsHint("Tous les modèles tournent sur le téléphone. Rien n'est envoyé ; seuls les modèles sont téléchargés, depuis Hugging Face.")
            }
            SectionTitle("Voix")
        }
        items(Voices.all, key = { it.id }) { spec ->
            val installed = remember(revision, spec.id) { hub.store.installed(spec) }
            VoiceCard(
                app, spec, installed,
                progress = progress[spec.id],
                error = errors[spec.id],
                testing = testing == spec.id,
                onDownload = {
                    errors.remove(spec.id)
                    progress[spec.id] = 0f
                    app.scope.launch {
                        try {
                            hub.store.download(spec) { p -> progress[spec.id] = p }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors[spec.id] = e.message ?: e.toString()
                        } finally {
                            progress.remove(spec.id)
                            revision++
                        }
                    }
                },
                onDelete = {
                    app.scope.launch {
                        if (testing == spec.id) speaker.stop()
                        hub.unload(spec)
                        withContext(Dispatchers.IO) { hub.store.delete(spec) }
                        revision++
                    }
                },
                onTest = {
                    if (testing == spec.id) {
                        speaker.stop()
                    } else {
                        errors.remove(spec.id)
                        hub.select(spec)
                        val pieces = Sentences.split(sample)
                        testing = spec.id
                        speaker.play(0) { pieces.getOrNull(it) }
                    }
                },
            )
        }
    }
}

@Composable
private fun VoiceCard(
    app: GraveyardApp,
    spec: VoiceSpec,
    installed: Boolean,
    progress: Float?,
    error: String?,
    testing: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    val hub = app.hub
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val pickWav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            note = try {
                withContext(Dispatchers.IO) {
                    val target = File(hub.store.dir(spec), Voices.CUSTOM_REFERENCE)
                    target.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    Wav.read(target)
                    Unit
                }
                hub.unload(spec)
                "Enregistrement pris en compte."
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }
    }
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed) RadioButton(selected = hub.activeId == spec.id, onClick = { hub.select(spec) })
            Column(Modifier.weight(1f)) {
                Text(spec.name, fontWeight = FontWeight.SemiBold)
                Text("${spec.family.label} · ${spec.sizeMb} Mo", style = MaterialTheme.typography.bodySmall)
            }
        }
        SettingsHint(spec.note)
        when {
            progress != null -> {
                SettingsHint("Téléchargement… ${(progress * 100).toInt()} %")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            !installed -> Button(onClick = onDownload) { Text("Télécharger (${spec.sizeMb} Mo)") }
            else -> {
                if (spec.variants.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { menu = true }) { Text("Variante : ${hub.variant(spec).label}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            spec.variants.forEachIndexed { i, v ->
                                DropdownMenuItem(text = { Text(v.label) }, onClick = { hub.setVariant(spec, i); menu = false })
                            }
                        }
                    }
                    if (hub.variant(spec).reference == Voices.CUSTOM_REFERENCE) {
                        OutlinedButton(onClick = { pickWav.launch(arrayOf("audio/*")) }) { Text("Choisir un WAV de 5 à 15 secondes") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTest) { Text(if (testing) "Arrêter" else "Tester") }
                    OutlinedButton(onClick = onDelete) { Text("Supprimer") }
                }
                if (testing) {
                    val s = app.speaker.state
                    SettingsHint(if (s.loading) "Chargement du modèle et première phrase…" else Bench.describe(s.stats))
                } else {
                    app.bench.results[spec.id]?.let { SettingsHint(Bench.describe(it)) }
                }
            }
        }
        note?.let { SettingsHint(it) }
        error?.let { SettingsHint(it, MaterialTheme.colorScheme.error) }
    }
}

private fun device(): String {
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " · ${Build.SOC_MODEL}" else ""
    return "${Build.MANUFACTURER} ${Build.MODEL}$soc · ${Runtime.getRuntime().availableProcessors()} cœurs"
}
