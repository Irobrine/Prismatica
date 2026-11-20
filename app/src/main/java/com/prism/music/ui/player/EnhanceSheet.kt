package com.prism.music.ui.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.ModelStore
import com.prism.music.data.enhance.AlignLanguage
import com.prism.music.data.enhance.EnhanceKind
import com.prism.music.data.enhance.EnhanceModel
import com.prism.music.data.enhance.EnhanceModelStore
import com.prism.music.data.enhance.EnhanceTask
import com.prism.music.data.enhance.InstrumentalKind
import com.prism.music.data.enhance.SeparationRecord
import com.prism.music.data.model.Song
import com.prism.music.tr
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.screens.settings.LinkRow
import com.prism.music.ui.screens.settings.SwitchRow
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** "12 MB on the phone" for a generated folder. */
private fun mb(bytes: Long): String = "${bytes / 1_000_000} MB"

/** The one MIME type the instrumental picker accepts (a constant, so no text is written in code). */
private const val ANY_AUDIO = "audio/*"

private fun qualityReason(code: String): String = when (code) {
    "low_confidence" -> tr(R.string.enhance_quality_reason_low_confidence)
    "timing_out_of_order" -> tr(R.string.enhance_quality_reason_timing_out_of_order)
    "empty_words" -> tr(R.string.enhance_quality_reason_empty_words)
    "word_lengths_irregular" -> tr(R.string.enhance_quality_reason_word_lengths_irregular)
    "words_in_silence" -> tr(R.string.enhance_quality_reason_words_in_silence)
    "no_words" -> tr(R.string.enhance_quality_reason_no_words)
    else -> code
}

/** Everything a song's operation can be in, from the same persisted queue the Settings sheet shows. */
private sealed interface LineState {
    /** Working. [progress] is null until the job has something to count: the bar animates rather than sitting at zero. */
    data class Running(val progress: Float?) : LineState
    data class Failed(val message: String) : LineState
    data class Other(val text: String) : LineState
}

private fun taskState(task: EnhanceTask?): LineState? = when (task?.state) {
    "queued" -> LineState.Running(null)
    "running" -> LineState.Running(task.progress.takeIf { it > 0f })
    "failed" -> LineState.Failed(task.message ?: tr(R.string.enhance_failed))
    "cancelled" -> LineState.Other(tr(R.string.enhance_cancelled))
    "interrupted" -> LineState.Other(tr(R.string.enhance_interrupted))
    "done" -> LineState.Other(tr(R.string.enhance_done))
    else -> null
}

/**
 * The per-song enhancement sheet: the three independent operations, the instrumental selector, the models, and how
 * reliable the last alignment looks. A missing model is offered here; it is never downloaded until the listener says so.
 *
 * Every action is its own job: pressing one queues only it (word timings additionally prepare the vocals they align
 * against when that setting is on), and each line says where its own job is — queued, working with a bar, failed with
 * the reason, or done — from the queue that survives leaving the sheet and a restart, not from the sheet's own memory.
 */
@Composable
fun EnhanceSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val tasks by c.enhance.tasks.collectAsState()
    val modelStates by c.enhance.models.states.collectAsState()
    val runtime = ModelStore.Model.singRuntime()
    val runtimeStates by c.models.states.collectAsState()
    var alignment by remember(song.id) { mutableStateOf(c.enhance.store.alignment(song.id)) }
    var lyricsShown by remember(song.id) { mutableStateOf(c.enhance.store.lyricsEnabled(song.id)) }
    var separation by remember(song.id) { mutableStateOf(c.enhance.store.separation(song.id)) }
    var hasGenerated by remember(song.id) { mutableStateOf(c.enhance.store.hasGeneratedInstrumental(song.id)) }
    var hasExternal by remember(song.id) { mutableStateOf(c.enhance.store.hasExternalInstrumental(song.id)) }
    var problem by remember(song.id) { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingEnhance?>(null) }
    var cancelAsk by remember { mutableStateOf<EnhanceTask?>(null) }
    var askVocalsFirst by remember(song.id) { mutableStateOf(false) }
    var redoAsk by remember { mutableStateOf<Set<EnhanceKind>?>(null) }
    var preview by remember(song.id) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var previewing by remember(song.id) { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf(0f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                c.enhance.addExternalInstrumental(song, uri, uri.lastPathSegment?.substringAfterLast('/') ?: "instrumental")
                problem = null
                hasExternal = c.enhance.store.hasExternalInstrumental(song.id)
            } catch (e: Exception) { problem = e.message ?: tr(R.string.enhance_external_import_failed) }
        }
    }
    // The results on disk, read again when one of this song's jobs finishes (a progress tick doesn't touch the files).
    val doneHere = tasks.count { it.song.id == song.id && it.state == "done" }
    LaunchedEffect(song.id, doneHere) {
        alignment = c.enhance.store.alignment(song.id)
        separation = c.enhance.store.separation(song.id)
        hasGenerated = c.enhance.store.hasGeneratedInstrumental(song.id)
        hasExternal = c.enhance.store.hasExternalInstrumental(song.id)
    }
    fun taskOf(kind: EnhanceKind) = tasks.lastOrNull { it.song.id == song.id && it.kind == kind }
    fun live(kind: EnhanceKind) = taskOf(kind)?.state == "queued" || taskOf(kind)?.state == "running"

    fun start(vararg kinds: EnhanceKind) {
        val wanted = LinkedHashSet<EnhanceKind>()
        wanted += kinds
        val needsAligner = EnhanceKind.LYRICS in wanted && !c.enhance.alignerReady()
        val needsSeparator = (EnhanceKind.VOCALS in wanted || EnhanceKind.INSTRUMENTAL in wanted) && !c.enhance.separatorReady()
        val missing = when {
            needsAligner -> EnhanceModel.ALIGNER
            needsSeparator -> EnhanceModel.SEPARATOR
            else -> null
        }
        if (missing != null || !c.enhance.runtimeReady()) {
            pending = PendingEnhance(missing, !c.enhance.runtimeReady(), wanted)
            return
        }
        problem = null
        c.enhance.enqueue(listOf(song), wanted)
    }

    /**
     * Sync lyrics. When the words would be timed against separated vocals but none are here, the listener is asked
     * whether to separate them first — the pipeline is theirs to choose, not a silent extra few minutes (or a silent
     * drop back to the mix). A separation already waiting or running is used without asking: the queue runs it first.
     * Words that were already timed ask first too ([redoAsk]): doing it again replaces the result.
     */
    fun sync() {
        if (alignment != null) { redoAsk = setOf(EnhanceKind.LYRICS); return }
        val vocalsHere = c.enhance.store.hasVocals(song.id)
        if (settings.enhanceAlignWithVocals && !vocalsHere && !live(EnhanceKind.VOCALS) && c.enhance.separatorReady()) {
            askVocalsFirst = true
            return
        }
        start(EnhanceKind.LYRICS)
    }

    /** Vocals and the instrumental also ask before replacing what they made. */
    fun separateAgain(kind: EnhanceKind, already: Boolean) {
        if (already) redoAsk = setOf(kind) else start(kind)
    }

    fun stopPreview() {
        preview?.let { mp -> runCatching { mp.stop() }; runCatching { mp.release() } }
        preview = null
        previewing = false
    }

    /** Plays the separated vocals on their own (the song pauses meanwhile), so bad separation can be heard for what it is. */
    fun togglePreview() {
        if (previewing) { stopPreview(); return }
        scope.launch {
            val wav = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { c.enhance.store.vocalsWav(song.id, java.io.File(c.app.cacheDir, "preview_${song.id}.wav")) }.getOrNull()
            }
            if (wav == null || !wav.isFile) { problem = tr(R.string.enhance_vocals_ready); return@launch }
            stopPreview()
            c.player.setPlaying(false)
            val mp = android.media.MediaPlayer()
            val ok = runCatching {
                mp.setDataSource(wav.absolutePath)
                mp.prepare()
                mp.setOnCompletionListener { previewing = false }
                mp.start()
            }.isSuccess
            if (!ok) { runCatching { mp.release() }; problem = tr(R.string.enhance_external_import_failed); return@launch }
            preview = mp
            previewing = true
        }
    }

    androidx.compose.runtime.DisposableEffect(song.id) {
        onDispose { stopPreview() }
    }
    var kind by remember(song.id, hasGenerated, hasExternal) { mutableStateOf(c.sing.instrumentalKind(song.id)) }
    fun choose(k: InstrumentalKind) { c.sing.setInstrumental(song.id, k); kind = k }
    val external = c.enhance.store.externalInstrumental(song.id)

    LaunchedEffect(Unit) { c.enhance.models.refresh(); c.models.refresh() }
    // Doing an operation again replaces its result: asked first, like stopping one.
    redoAsk?.let { kinds ->
        AlertDialog(
            onDismissRequest = { redoAsk = null },
            title = { Text(tr(R.string.enhance_redo_question)) },
            text = { Text(tr(R.string.enhance_redo_info)) },
            confirmButton = {
                PrismTextButton(onClick = {
                    val again = kinds
                    redoAsk = null
                    start(*again.toTypedArray())
                }) { Text(tr(R.string.enhance_redo)) }
            },
            dismissButton = { PrismTextButton(onClick = { redoAsk = null }) { Text(tr(R.string.cancel)) } },
        )
    }
    // Separate first, or time the words against the mix: the listener's call, made once per song.
    if (askVocalsFirst) {
        AlertDialog(
            onDismissRequest = { askVocalsFirst = false },
            title = { Text(tr(R.string.enhance_sync_with_vocals_question)) },
            text = { Text(tr(R.string.enhance_sync_with_vocals_info)) },
            confirmButton = {
                PrismTextButton(onClick = { askVocalsFirst = false; start(EnhanceKind.VOCALS, EnhanceKind.LYRICS) }) {
                    Text(tr(R.string.enhance_separate_and_sync))
                }
            },
            dismissButton = {
                Row {
                    PrismTextButton(onClick = { askVocalsFirst = false; start(EnhanceKind.LYRICS) }) {
                        Text(tr(R.string.enhance_sync_without_vocals))
                    }
                    PrismTextButton(onClick = { askVocalsFirst = false }) { Text(tr(R.string.cancel)) }
                }
            },
        )
    }
    // Stopping a job asks first: it's the listener's own work, and a long separation is not a slip of the finger.
    cancelAsk?.let { task ->
        AlertDialog(
            onDismissRequest = { cancelAsk = null },
            title = { Text(tr(R.string.enhance_cancel_task)) },
            text = { Text(tr(R.string.enhance_cancel_task_info)) },
            confirmButton = {
                PrismTextButton(onClick = { cancelAsk = null; c.enhance.cancel(task) }) { Text(tr(R.string.stop)) }
            },
            dismissButton = { PrismTextButton(onClick = { cancelAsk = null }) { Text(tr(R.string.cancel)) } },
        )
    }
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(tr(R.string.enhance_lyrics_enhancement)) },
            text = {
                Text(
                    when {
                        p.model != null -> tr(R.string.enhance_download_consent, p.model.title, EnhanceModel.sizeText(p.model))
                        else -> tr(R.string.enhance_needs_runtime)
                    },
                )
            },
            confirmButton = {
                PrismTextButton(onClick = {
                    val task = p
                    pending = null
                    scope.launch {
                        try {
                            if (p.runtime) {
                                val rt = ModelStore.Model.singRuntime()
                                if (rt != null) c.models.download(rt)
                            }
                            p.model?.let { downloaded = 0f; c.enhance.models.download(it) { f -> downloaded = f } }
                            downloaded = 0f
                            c.enhance.enqueue(listOf(song), task.kinds)
                        } catch (e: Throwable) { downloaded = 0f; problem = e.message ?: tr(R.string.enhance_failed) }
                    }
                }) { Text(tr(R.string.enhance_download_and_continue)) }
            },
            dismissButton = { PrismTextButton(onClick = { pending = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    ActionSheet(song.thumbnail, onDismiss) { _ ->
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PrismIcons.Lyrics, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.enhance_lyrics_enhancement), style = MaterialTheme.typography.titleLarge)
                    Text(tr(R.string.enhance_on_device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            // 1. Word timings.
            ActionLine(
                label = if (alignment != null) tr(R.string.enhance_resync_lyrics) else tr(R.string.enhance_sync_lyrics),
                title = if (alignment != null) tr(R.string.enhance_resync_lyrics) else tr(R.string.enhance_sync_lyrics),
                detail = alignment?.let { "${tr(R.string.enhance_words_timed, it.lines.sumOf { l -> l.words.size })} · ${if (it.quality.reliable) tr(R.string.enhance_quality_reliable) else tr(R.string.enhance_quality_doubtful)}" }
                    ?: tr(R.string.enhance_aligner_notice),
                done = alignment != null,
                state = taskState(taskOf(EnhanceKind.LYRICS)),
                tag = "enhance:sync",
                onCancel = { taskOf(EnhanceKind.LYRICS)?.let { cancelAsk = it } },
            ) { sync() }
            alignment?.let { a ->
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (a.quality.reliable) tr(R.string.enhance_quality_reliable) else tr(R.string.enhance_quality_doubtful)) +
                        (if (a.quality.reasons.isEmpty()) "" else ": " + a.quality.reasons.joinToString(", ") { qualityReason(it) }),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a.quality.reliable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                // The timings are kept either way: off, the song's own line timings are what the player shows.
                SwitchRow(
                    tr(R.string.enhance_use_synced_lyrics), lyricsShown,
                    info = tr(R.string.enhance_use_synced_lyrics_info),
                ) { on ->
                    lyricsShown = on
                    c.enhance.store.setLyricsEnabled(song.id, on)
                    // Open lyrics read the overlay again.
                    c.lyrics.overlayChanged()
                }
            }
            Spacer(Modifier.height(14.dp))
            // 2. Vocals.
            ActionLine(
                label = tr(R.string.enhance_separate_voices),
                title = tr(R.string.enhance_separate_voices),
                detail = if (separation != null) tr(R.string.enhance_vocals_ready) else tr(R.string.enhance_separation_original_kept),
                done = separation != null,
                state = taskState(taskOf(EnhanceKind.VOCALS)),
                tag = "enhance:vocals",
                onCancel = { taskOf(EnhanceKind.VOCALS)?.let { cancelAsk = it } },
            ) { separateAgain(EnhanceKind.VOCALS, separation != null) }
            if (separation != null) PrismTextButton(
                onClick = { togglePreview() },
                modifier = Modifier.testTag("enhance:vocals:play"),
            ) {
                Text(if (previewing) tr(R.string.enhance_stop_vocals) else tr(R.string.enhance_play_vocals))
            }
            Spacer(Modifier.height(10.dp))
            // 3. Instrumental, independent of the external one.
            ActionLine(
                label = tr(R.string.enhance_make_instrumental),
                title = tr(R.string.enhance_make_instrumental),
                detail = if (hasGenerated) tr(R.string.enhance_instrumental_ready) else tr(R.string.enhance_separator_notice),
                done = hasGenerated,
                state = taskState(taskOf(EnhanceKind.INSTRUMENTAL)),
                tag = "enhance:instrumental",
                onCancel = { taskOf(EnhanceKind.INSTRUMENTAL)?.let { cancelAsk = it } },
            ) { separateAgain(EnhanceKind.INSTRUMENTAL, hasGenerated) }
            if (problem != null) {
                Spacer(Modifier.height(6.dp))
                Text(problem!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(18.dp))
            Text(tr(R.string.enhance_instrumental), style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.enhance_choose_instrumental), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            // What Sing plays without the voice, as switches: both off is the song as it is, with Sing taking the voice
            // out. The imported file is never touched, only chosen.
            if (hasGenerated) SwitchRow(
                tr(R.string.enhance_generated), kind == InstrumentalKind.GENERATED,
                value = tr(R.string.enhance_instrumental_ready),
            ) { on -> choose(if (on) InstrumentalKind.GENERATED else InstrumentalKind.ORIGINAL) }
            if (hasExternal) SwitchRow(
                external?.let { tr(R.string.enhance_external) + " · " + it.name } ?: tr(R.string.enhance_external),
                kind == InstrumentalKind.EXTERNAL,
            ) { on -> choose(if (on) InstrumentalKind.EXTERNAL else InstrumentalKind.ORIGINAL) }
            if (hasGenerated || hasExternal) Text(
                tr(R.string.enhance_original_backing_detail),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrismTextButton(onClick = { picker.launch(arrayOf(ANY_AUDIO)) }, modifier = Modifier.testTag("enhance:inst:add")) {
                Text(tr(R.string.enhance_add_instrumental))
            }
            Text(tr(R.string.enhance_separation_original_kept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(tr(R.string.enhance_models), style = MaterialTheme.typography.titleMedium)
            EnhanceModelRow(EnhanceModel.ALIGNER, modelStates, runtime, runtimeStates)
            EnhanceModelRow(EnhanceModel.SEPARATOR, modelStates, runtime, runtimeStates)
            if (downloaded > 0f && pending == null) Text(tr(R.string.enhance_downloading, (downloaded * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class PendingEnhance(val model: EnhanceModel?, val runtime: Boolean, val kinds: Set<EnhanceKind>)

/**
 * One operation: what it does (and whether it has been done), its own job's state, and the button that starts it.
 * Only this line shows this operation's progress; another operation running is somebody else's line.
 */
@Composable
private fun ActionLine(
    label: String,
    title: String,
    detail: String,
    done: Boolean,
    state: LineState?,
    tag: String,
    onCancel: () -> Unit,
    onClick: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (done && state !is LineState.Failed) {
                        Icon(PrismIcons.CheckCircle, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (state) {
                    is LineState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is LineState.Other -> Text(state.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is LineState.Running -> Text(
                        tr(R.string.enhance_running, ((state.progress ?: 0f) * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    )
                    null -> Unit
                }
            }
            // While it runs, the same button stops it (asked first): the bar already says how far it is.
            if (state is LineState.Running) PrismTextButton(onClick = onCancel, modifier = Modifier.testTag("$tag:cancel")) {
                Text(tr(R.string.enhance_cancel))
            } else PrismTextButton(onClick = { scope.launch { onClick() } }, modifier = Modifier.testTag(tag)) {
                Text(label)
            }
        }
        if (state is LineState.Running) {
            val p = state.progress
            if (p == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            else androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
private fun EnhanceModelRow(model: EnhanceModel, states: Map<EnhanceModel, EnhanceModelStore.State>, runtime: ModelStore.Model?, runtimeStates: Map<ModelStore.Model, ModelStore.State>) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = states[model] ?: EnhanceModelStore.State.Missing
    val runtimeMissing = runtime != null && runtimeStates[runtime] != ModelStore.State.Ready
    val downloading = state as? EnhanceModelStore.State.Downloading
    var job by remember(model) { mutableStateOf<Job?>(null) }
    var confirming by remember(model) { mutableStateOf(false) }
    LinkRow(
        model.title,
        value = when {
            state == EnhanceModelStore.State.Ready -> tr(R.string.enhance_ready_on_phone, mb(c.enhance.models.sizeOnPhone(model)))
            downloading != null -> tr(R.string.enhance_downloading, (downloading.fraction * 100).toInt())
            runtimeMissing -> tr(R.string.enhance_needs_runtime)
            c.enhance.models.updateAvailable(model) -> tr(R.string.enhance_update) + " · " + EnhanceModel.sizeText(model)
            else -> tr(R.string.enhance_not_downloaded, EnhanceModel.sizeText(model))
        },
        info = model.notice + " · " + model.version + " " + tr(R.string.enhance_remove_model_keeps_work),
        action = true,
    ) {
        when {
            downloading != null -> job?.cancel()
            // Removing a gigabyte is not a slip of the finger: asked first, and the work it made is kept either way.
            state == EnhanceModelStore.State.Ready -> confirming = true
            else -> job = scope.launch { runCatching { c.enhance.download(model) } }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(model.title) },
            text = { Text(tr(R.string.enhance_remove_model_keeps_work)) },
            confirmButton = {
                PrismTextButton(onClick = { confirming = false; c.enhance.delete(model) }) { Text(tr(R.string.enhance_remove)) }
            },
            dismissButton = { PrismTextButton(onClick = { confirming = false }) { Text(tr(R.string.cancel)) } },
        )
    }
    if (downloading != null) {
        androidx.compose.material3.LinearProgressIndicator(progress = { downloading.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))
        PrismTextButton(onClick = { job?.cancel() }) { Text(tr(R.string.enhance_cancel_download)) }
    }
    if (state is EnhanceModelStore.State.Failed) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/**
 * Settings → Lyrics & Sing → "Lyrics enhancement": the models, a batch over the same sets Settings' "Measure songs now"
 * offers (the Library, downloads, the phone's own files, liked songs), and the queue (including jobs a restart cut
 * short). Nothing starts by itself.
 */
@Composable
fun EnhanceSettingsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val prefs = c.settings
    val scope = rememberCoroutineScope()
    val modelStates by c.enhance.models.states.collectAsState()
    val runtime = ModelStore.Model.singRuntime()
    val runtimeStates by c.models.states.collectAsState()
    val tasks by c.enhance.tasks.collectAsState()
    var lyrics by remember { mutableStateOf(true) }
    var vocals by remember { mutableStateOf(true) }
    var instrumentals by remember { mutableStateOf(false) }
    var batchProblem by remember { mutableStateOf<String?>(null) }
    // How many songs each set holds, counted as the sheet opens (the Library's needs the database read), as the
    // measuring walk's sheet does.
    var counts by remember { mutableStateOf<Map<EnhanceSet, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        c.enhance.models.refresh(); c.models.refresh()
        for (set in EnhanceSet.entries) {
            val n = runCatching { set.songs(c).size }.getOrNull() ?: continue
            counts = counts + (set to n)
        }
    }

    fun kinds(): Set<EnhanceKind> = buildSet {
        if (lyrics) add(EnhanceKind.LYRICS)
        if (vocals) add(EnhanceKind.VOCALS)
        if (instrumentals) add(EnhanceKind.INSTRUMENTAL)
    }
    fun run(set: EnhanceSet) {
        val wanted = kinds()
        if (wanted.isEmpty()) return
        val missing = when {
            EnhanceKind.LYRICS in wanted && !c.enhance.alignerReady() -> EnhanceModel.ALIGNER
            (EnhanceKind.VOCALS in wanted || EnhanceKind.INSTRUMENTAL in wanted) && !c.enhance.separatorReady() -> EnhanceModel.SEPARATOR
            else -> null
        }
        if (missing != null) {
            batchProblem = tr(R.string.enhance_download_consent, missing.title, EnhanceModel.sizeText(missing))
            return
        }
        batchProblem = null
        scope.launch {
            // The set's songs are read off the main thread (the Library's come from the database): the same sets the
            // measuring walk lists, so a batch is picked the way a walk is.
            val songs = runCatching { set.songs(c) }.getOrElse { e ->
                batchProblem = e.message ?: tr(R.string.enhance_failed); return@launch
            }
            if (songs.isEmpty()) { batchProblem = tr(R.string.enhance_no_songs_picked); return@launch }
            c.enhance.enqueue(songs, wanted, force = false)
        }
    }

    ActionSheet(null, onDismiss) { _ ->
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
            Text(tr(R.string.enhance_lyrics_enhancement), style = MaterialTheme.typography.titleLarge)
            Text(tr(R.string.enhance_on_device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            EnhanceModelRow(EnhanceModel.ALIGNER, modelStates, runtime, runtimeStates)
            EnhanceModelRow(EnhanceModel.SEPARATOR, modelStates, runtime, runtimeStates)
            Spacer(Modifier.height(14.dp))
            com.prism.music.ui.screens.settings.LsEnhancementRows(settings, prefs)
            LinkRow(tr(R.string.enhance_clear_generated_audio), value = mb(c.enhance.store.bytes()), action = true) { c.enhance.store.clearAudio() }
            Spacer(Modifier.height(18.dp))
            Text(tr(R.string.enhance_batch), style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.enhance_batch_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BatchChip(tr(R.string.enhance_batch_lyrics), lyrics) { lyrics = !lyrics }
                BatchChip(tr(R.string.enhance_batch_vocals), vocals) { vocals = !vocals }
                BatchChip(tr(R.string.enhance_batch_instrumentals), instrumentals) { instrumentals = !instrumentals }
            }
            Spacer(Modifier.height(6.dp))
            // The sets, as "Measure songs now" lists them: a tap runs the picked operations over the whole set.
            EnhanceSet.entries.forEach { set ->
                val count = counts[set]
                LinkRow(
                    set.title,
                    value = count?.let { "$it " + tr(R.string.songs) } ?: tr(R.string.checking),
                    action = true,
                    enabled = kinds().isNotEmpty() && count != 0,
                ) { run(set) }
            }
            if (batchProblem != null) Text(batchProblem!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Text(tr(R.string.enhance_queue), style = MaterialTheme.typography.titleMedium)
            if (tasks.isEmpty()) Text(tr(R.string.enhance_queue_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.prism.music.data.enhance.EnhanceTasks.shown(tasks).forEach { t ->
                val state = when (t.state) {
                    "done" -> tr(R.string.enhance_done)
                    "failed" -> t.message ?: tr(R.string.enhance_failed)
                    "running" -> tr(R.string.enhance_running, (t.progress * 100).toInt())
                    "cancelled" -> tr(R.string.enhance_cancelled)
                    "interrupted" -> tr(R.string.enhance_interrupted)
                    else -> tr(R.string.enhance_queued)
                }
                Text("${t.song.title} · ${kindLabel(t.kind)} — $state", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (tasks.any { it.state == "interrupted" }) Row {
                PrismTextButton({ c.enhance.resumeInterrupted() }) { Text(tr(R.string.enhance_resume)) }
                PrismTextButton({ c.enhance.discardInterrupted() }) { Text(tr(R.string.enhance_discard)) }
            }
            if (tasks.any { it.state == "running" || it.state == "queued" }) PrismTextButton({ c.enhance.cancelAll() }) { Text(tr(R.string.enhance_cancel)) }
            if (tasks.isNotEmpty()) PrismTextButton({ c.enhance.clearFinished() }) { Text(tr(R.string.enhance_clear_finished)) }
        }
    }
}

private fun kindLabel(kind: EnhanceKind): String = when (kind) {
    EnhanceKind.LYRICS -> tr(R.string.enhance_batch_lyrics)
    EnhanceKind.VOCALS -> tr(R.string.enhance_batch_vocals)
    EnhanceKind.INSTRUMENTAL -> tr(R.string.enhance_batch_instrumentals)
    EnhanceKind.EXTERNAL -> tr(R.string.enhance_external)
}

/**
 * The sets a batch runs over: the same four "Measure songs now" walks, each under the name it goes by elsewhere in
 * Prism. The Library's come from the database, off the main thread.
 */
private enum class EnhanceSet(val title: String) {
    LIBRARY(tr(R.string.library)),
    DOWNLOADED(tr(R.string.downloaded)),
    PHONE(tr(R.string.on_this_phone)),
    LIKED(tr(R.string.liked_songs));

    suspend fun songs(c: AppContainer): List<Song> = when (this) {
        // Room's IN list takes a few hundred at a time: the Library's ids are read in chunks, as elsewhere in Prism.
        LIBRARY -> c.smart.knownIds().chunked(500).flatMap { c.db.songs().getAll(it) }.map { it.toSong() }
        DOWNLOADED -> c.downloads.completedSongs()
        PHONE -> c.localMusic.tracks.value.map { it.song }
        LIKED -> c.db.songs().liked().map { it.toSong() }
    }
}

@Composable
private fun BatchChip(label: String, on: Boolean, toggle: () -> Unit) {
    PrismTextButton(onClick = toggle) {
        Icon(if (on) PrismIcons.CheckCircle else PrismIcons.Check, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
