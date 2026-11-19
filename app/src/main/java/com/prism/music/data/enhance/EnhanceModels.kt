package com.prism.music.data.enhance

import android.content.Context
import com.prism.music.R
import com.prism.music.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The two models the lyrics enhancement feature uses, kept apart from Sing's (and each other's) so either can be
 * downloaded, updated or removed on its own. They are never in the APK, and nothing is downloaded without the
 * listener asking for it.
 *
 * The aligner is Qwen3-ForcedAligner-0.6B's Q4 ONNX export: the embedding table stays float32 (~594 MB), the rest of
 * the graph is 4-bit, and the whole file is ~1.05 GB. The separator is the single-model FP16-weights HTDemucs export
 * (~166 MB), not the four-model fine-tuned bag. Both are Apache-2.0 / MIT respectively; their notices travel with the
 * app (assets/enhance).
 *
 * Files are checked by SHA-256 before they are used. A download that is cut short leaves its `.part` file behind and
 * carries on from there (HTTP Range) when asked again; a file that fails its check is thrown away rather than used.
 */
enum class EnhanceModel(
    val id: String,
    val version: String,
    val title: String,
    val notice: String,
    val files: List<RemoteFile>,
) {
    ALIGNER(
        "qwen3-forced-aligner-q4", "q4-2026-03-31",
        tr(R.string.enhance_aligner),
        tr(R.string.enhance_aligner_notice),
        listOf(
            RemoteFile(
                "model_q4.onnx",
                "https://huggingface.co/tonythethompson/Qwen3-ForcedAligner-0.6B-ONNX/resolve/main/onnx/model_q4.onnx",
                1_048_397_349, "59b528896d70b34e57838e160d16d5f7cfc02d86c7c6ad46cdc57c25c15497b7",
            ),
            RemoteFile(
                "vocab.json",
                "https://huggingface.co/jinhwan000/qwen3-aligner-0.6b-onnx/resolve/main/vocab.json",
                2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910",
            ),
            RemoteFile(
                "merges.txt",
                "https://huggingface.co/jinhwan000/qwen3-aligner-0.6b-onnx/resolve/main/merges.txt",
                1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5",
            ),
            RemoteFile(
                "config.json",
                "https://huggingface.co/tonythethompson/Qwen3-ForcedAligner-0.6B-ONNX/resolve/main/config.json",
                5_725, "91f38394bd8117ad2ccbdfc0942d9c100a7482d4c43688674ef2a40fe64eb061",
            ),
            RemoteFile(
                "added_tokens.json",
                "https://huggingface.co/tonythethompson/Qwen3-ForcedAligner-0.6B-ONNX/resolve/main/added_tokens.json",
                1_591, "85afd03b7a7a9b0a97edf2b0e5dd9b0bf8770e05b0323268c0f1a3e5190cfa6f",
            ),
            RemoteFile(
                "tokenizer_config.json",
                "https://huggingface.co/tonythethompson/Qwen3-ForcedAligner-0.6B-ONNX/resolve/main/tokenizer_config.json",
                12_696, "bee4786e25db33d3e8acb4c7a37c4ccf8d5a3d843b9cafb75a91d4ba774ad38f",
            ),
            // Whisper-compatible log-mel constants, from the reference ONNX export (Apache-2.0): the exact 400-point
            // Hann window and 128 x 201 Slaney mel filterbank the model was trained with.
            RemoteFile(
                "mel_filters.f32",
                "https://huggingface.co/jinhwan000/qwen3-aligner-0.6b-onnx/resolve/main/mel_filters.f32",
                102_912, "2a5f9822897750e047c85dea37cc268d3be0ecfd23c28a5f10da129d99d05afe",
            ),
            RemoteFile(
                "hann_400.f32",
                "https://huggingface.co/jinhwan000/qwen3-aligner-0.6b-onnx/resolve/main/hann_400.f32",
                1_600, "9336ce3c7c7a0263b32d468d98fcff67cfd06621c3e10a515875746caf4cdfc4",
            ),
        ),
    ),
    SEPARATOR(
        "htdemucs-fp16weights", "htdemucs-fp16weights-2026",
        tr(R.string.enhance_separator),
        tr(R.string.enhance_separator_notice),
        listOf(
            RemoteFile(
                HtDemucs.MODEL,
                "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx",
                165_612_636, "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a",
            ),
        ),
    ),
    ;

    /** Download size, and size on the phone (they're the same here: no zip to unpack). */
    val downloadBytes: Long get() = files.sumOf { it.bytes }
    val installedBytes: Long get() = downloadBytes

    companion object {
        fun sizeText(m: EnhanceModel) = tr(R.string.mb_download_mb_on_the_phone, m.downloadBytes / 1_000_000, m.installedBytes / 1_000_000)
    }
}

/** One file of a model, at a stable URL, with the size and SHA-256 its download is checked against. */
data class RemoteFile(val name: String, val url: String, val bytes: Long, val sha256: String)

/**
 * Downloads and keeps the enhancement models. Each file is written to `<name>.part` while it arrives, then checked and
 * renamed. The ready marker is written last and holds the model's version, so an interrupted install never looks ready
 * and an update can be offered when a newer version is published.
 */
class EnhanceModelStore(private val context: Context, private val http: OkHttpClient) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float, val bytes: Long, val total: Long) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    @Serializable
    private data class Marker(val version: String, val files: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true }
    private val root by lazy { File(context.filesDir, "enhance-models") }
    private val _states = MutableStateFlow(EnhanceModel.entries.associateWith { stateOf(it) })
    val states: StateFlow<Map<EnhanceModel, State>> = _states
    private val locks = EnhanceModel.entries.associateWith { Mutex() }

    fun dir(m: EnhanceModel) = File(root, m.id)
    fun file(m: EnhanceModel, name: String) = File(dir(m), name)

    /** The files are all there and checked, and the marker names this build's version. */
    fun ready(m: EnhanceModel): Boolean = marker(m)?.version == m.version && m.files.all { File(dir(m), it.name).isFile }

    /** The version on the phone, or null when there's none (or it's from an older release, see [updateAvailable]). */
    fun versionOnPhone(m: EnhanceModel): String? = marker(m)?.version

    fun updateAvailable(m: EnhanceModel): Boolean = marker(m) != null && marker(m)?.version != m.version

    fun sizeOnPhone(m: EnhanceModel): Long = dir(m).walkTopDown().filter { it.isFile && !it.name.endsWith(PART) }.sumOf { it.length() }

    /** Reads the marker again (a model put there or removed outside a download). */
    fun refresh() {
        dropRetired()
        _states.update { s -> s.mapValues { (m, st) -> if (st is State.Downloading) st else stateOf(m) } }
    }

    /**
     * Removes models this build no longer uses — a separator that was replaced, say. Their files are dead weight the
     * listener can't even see: no row points at them any more.
     */
    private fun dropRetired() {
        val known = EnhanceModel.entries.mapTo(HashSet()) { it.id }
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in known) runCatching { dir.deleteRecursively() }
        }
    }

    private fun stateOf(m: EnhanceModel): State = when {
        ready(m) -> State.Ready
        updateAvailable(m) -> State.Missing
        dir(m).listFiles()?.any { it.name.endsWith(PART) } == true -> State.Missing
        else -> State.Missing
    }

    private fun marker(m: EnhanceModel): Marker? =
        runCatching { json.decodeFromString<Marker>(File(dir(m), READY).readText()) }.getOrNull()

    /**
     * Downloads [m], one file at a time, resuming each file's `.part` from the bytes already there. Returns true when
     * every file is checked and the marker is written. Cancelling the caller stops the download and leaves the `.part`
     * files for a later try; nothing half-downloaded is ever treated as ready.
     */
    suspend fun download(m: EnhanceModel, onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        locks.getValue(m).withLock {
            if (ready(m)) { publish(m, State.Ready); return@withLock true }
            dir(m).mkdirs()
            val total = m.downloadBytes.coerceAtLeast(1)
            var doneBefore = 0L
            try {
                for (f in m.files) {
                    publish(m, State.Downloading((doneBefore.toFloat() / total).coerceIn(0f, 1f), doneBefore, total))
                    fetch(m, f, doneBefore, total, onProgress)
                    doneBefore += f.bytes
                }
                File(dir(m), READY).writeText(json.encodeToString(Marker.serializer(), Marker(m.version, m.files.associate { it.name to it.sha256 })))
                publish(m, State.Ready)
                true
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) { publish(m, stateOf(m)); throw t }
                publish(m, State.Failed(t.message ?: tr(R.string.download_failed)))
                throw t
            }
        }
    }

    /** Removes the model's files. Generated lyrics and instrumentals are elsewhere, so they stay. */
    fun delete(m: EnhanceModel) {
        dir(m).deleteRecursively()
        _states.update { it + (m to State.Missing) }
    }

    private fun publish(m: EnhanceModel, s: State) = _states.update { it + (m to s) }

    private fun fetch(m: EnhanceModel, f: RemoteFile, doneBefore: Long, total: Long, onProgress: ((Float) -> Unit)?) {
        val target = File(dir(m), f.name)
        if (target.isFile && target.length() == f.bytes && sha256(target) == f.sha256) return
        target.delete()
        val part = File(dir(m), f.name + PART)
        if (part.isFile && part.length() > f.bytes) part.delete()
        // A finished-but-unchecked part from an interrupted run: check it now rather than asking for a range past its end.
        if (part.isFile && part.length() == f.bytes) {
            if (sha256(part) == f.sha256) {
                if (!part.renameTo(target)) { target.delete(); part.copyTo(target, overwrite = true); part.delete() }
                return
            }
            part.delete()
        }
        var have = part.length()
        val builder = Request.Builder().url(f.url)
        if (have > 0) builder.header("Range", "bytes=$have-")
        val resp = http.newCall(builder.build()).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException(tr(R.string.the_download_answered_http, it.code))
            val append = have > 0 && it.code == 206
            if (!append) have = 0
            val body = it.body
            RandomAccessFile(part, "rw").use { out ->
                // A server that ignored the Range starts again: cut the old tail rather than leaving it after the new bytes.
                if (!append) out.setLength(0)
                out.seek(if (append) have else 0L)
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var got = have
                    var lastShown = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val fraction = ((doneBefore + got).toFloat() / total).coerceIn(0f, 1f)
                        if (fraction - lastShown >= 0.005f) { lastShown = fraction; publish(m, State.Downloading(fraction, doneBefore + got, total)); onProgress?.invoke(fraction) }
                    }
                }
            }
        }
        if (part.length() != f.bytes) throw IOException(tr(R.string.the_download_didn_t_check_out))
        val sha = sha256(part)
        if (sha != f.sha256) { part.delete(); throw IOException(tr(R.string.the_download_didn_t_check_out)) }
        if (!part.renameTo(target)) { target.delete(); part.copyTo(target, overwrite = true); part.delete() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val READY = ".ready"
        const val PART = ".part"
    }
}
