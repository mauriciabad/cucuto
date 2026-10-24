package app.mauri.cucuto.ui.creator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.graphics.get
import app.mauri.cucuto.core.audio.AudioFileDecoder
import app.mauri.cucuto.core.audio.ClipEncoder
import app.mauri.cucuto.core.audio.DecodedAudio
import app.mauri.cucuto.core.audio.MicRecorder
import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.core.audio.dsp.ClipEdit
import app.mauri.cucuto.core.audio.dsp.ClipProcessor
import app.mauri.cucuto.core.creator.CreatorDraft
import app.mauri.cucuto.core.creator.DraftClip
import app.mauri.cucuto.core.creator.DraftStore
import app.mauri.cucuto.core.creator.Duplicate
import app.mauri.cucuto.core.creator.PackDuplicator
import app.mauri.cucuto.core.creator.PackWriter
import app.mauri.cucuto.core.creator.RingtoneImport
import app.mauri.cucuto.core.creator.SpriteCrop
import app.mauri.cucuto.core.creator.SpriteImages
import app.mauri.cucuto.core.creator.WriteResult
import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.authoring.Authoring
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why a clip could not be kept. */
sealed interface ClipResult {
    data class Saved(
        val clip: DraftClip,
    ) : ClipResult

    /** The finished clip (after silence trimming) is outside the format's 0.2 to 30 s. */
    data class OutOfRange(
        val durationMs: Long,
    ) : ClipResult

    /** Nothing in the selection rises above the silence threshold. */
    data object Silent : ClipResult
}

/**
 * Everything the creator does with files, the microphone and the platform codecs, off the main thread. The screen's
 * view model holds the state; this holds the IO. Files live in `files/creator/` (the draft) and `cache/creator/`
 * (sources being edited, built packs).
 */
@Singleton
class CreatorTools
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val drafts = DraftStore.inFilesDir(context.filesDir)
        private val duplicator = PackDuplicator(PackStore.inFilesDir(context.filesDir), drafts)
        private val encoder = ClipEncoder()
        private val writer = PackWriter()
        private val work get() = File(context.cacheDir, "creator")

        /** Saves outlive the screen: leaving the creator right after a change still keeps it. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val saves = Channel<CreatorDraft?>(Channel.CONFLATED)
        private val lock = Any()

        init {
            scope.launch {
                // Latest wins: a burst of keystrokes is one write. Null cancels a pending write.
                saves.receiveAsFlow().collectLatest { draft ->
                    if (draft == null) return@collectLatest
                    delay(SAVE_DELAY_MS)
                    runCatching { synchronized(lock) { drafts.save(draft) } }
                }
            }
        }

        /** The saved draft (unused media pruned), or null. */
        suspend fun loadDraft(): CreatorDraft? =
            io {
                synchronized(lock) { drafts.load()?.also { drafts.prune(it) } }
            }

        /** Saves [draft] shortly, replacing any save still pending. */
        fun saveSoon(draft: CreatorDraft) {
            saves.trySend(draft)
        }

        /** Deletes the draft and returns a new one, prefilled with the last author. */
        suspend fun newDraft(): CreatorDraft {
            saves.trySend(null)
            return io {
                synchronized(lock) {
                    drafts.clear()
                    CreatorDraft(author = drafts.lastAuthor())
                }
            }
        }

        suspend fun duplicate(
            packId: String,
            name: (String) -> String,
        ): Duplicate {
            saves.trySend(null)
            return io { synchronized(lock) { duplicator.duplicate(packId, name) } }
        }

        fun media(name: String): File = drafts.media(name)

        /** Decodes a picked picture for the cropper. */
        suspend fun decodePicture(uri: Uri): Bitmap = io { SpriteImages.decode(context.contentResolver, uri) }

        /** Crops, scales and writes a sprite or cover; returns its media name and seed colors for it. */
        suspend fun savePicture(
            bitmap: Bitmap,
            crop: SpriteCrop,
            prefix: String,
        ): Pair<String, List<Int>> =
            withContext(Dispatchers.Default) {
                val square = SpriteImages.render(bitmap, crop)
                val file = drafts.newMediaFile(prefix, "webp")
                SpriteImages.writeWebp(square, file)
                val seeds = seedColors(square)
                square.recycle()
                file.name to seeds
            }

        /** Up to [count] seed colors (ARGB) for a picture, best first: Material Color Utilities, like the app's theme. */
        suspend fun seedColors(
            bitmap: Bitmap,
            count: Int = SEED_SUGGESTIONS,
        ): List<Int> =
            withContext(Dispatchers.Default) {
                val sample = sampled(bitmap)
                val opaque = sample.filter { (it ushr ALPHA_SHIFT) >= MIN_ALPHA }.map { it or OPAQUE }.toIntArray()
                if (opaque.isEmpty()) return@withContext emptyList()
                Score.score(QuantizerCelebi.quantize(opaque, MAX_COLORS), desired = count, fallbackColorArgb = null)
            }

        /** A sprite already in the draft, decoded for color suggestions; null for formats ImageDecoder cannot read. */
        suspend fun spriteBitmap(name: String): Bitmap? =
            io {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(drafts.media(name))) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
            }

        /**
         * Copies a picked audio file to the cache and decodes it (at most six minutes, the longest audio a pack holds).
         *
         * @throws IOException when it cannot be read or decoded, or is larger than [Limits.AUDIO_FILE_BYTES].
         */
        suspend fun decodeAudio(
            uri: Uri,
            onProgress: (Float) -> Unit,
        ): DecodedAudio {
            val copy =
                io {
                    work.mkdirs()
                    val file = File(work, "source")
                    val input =
                        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
                    input.use { stream ->
                        file.outputStream().use { out ->
                            val buffer = ByteArray(COPY_BUFFER)
                            var total = 0L
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > Limits.AUDIO_FILE_BYTES) throw SourceTooLargeException()
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    file
                }
            return try {
                AudioFileDecoder().decode(copy, onProgress)
            } finally {
                io { copy.delete() }
            }
        }

        /**
         * "Add a ringtone": copies a picked song into the draft as it is, if a pack can hold it (#322).
         *
         * @throws SourceTooLargeException over [Limits.AUDIO_FILE_BYTES]; [IOException] when it cannot be read.
         */
        suspend fun importRingtone(uri: Uri): RingtoneImport.Result =
            io {
                val name = displayName(uri)
                work.mkdirs()
                val copy = File(work, "ringtone-source")
                try {
                    copyLimited(uri, copy)
                    synchronized(lock) { RingtoneImport.import(drafts, copy, name) }
                } finally {
                    copy.delete()
                }
            }

        /** The picked file's name, or one made from its MIME type (the extension says the format). */
        private fun displayName(uri: Uri): String {
            val name =
                runCatching {
                    context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
            if (name != null && name.contains('.')) return name
            val ext = AUDIO_EXTENSIONS[context.contentResolver.getType(uri)].orEmpty()
            return (name ?: "ringtone") + "." + ext
        }

        private fun copyLimited(
            uri: Uri,
            file: File,
        ) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
            input.use { stream ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > Limits.AUDIO_FILE_BYTES) throw SourceTooLargeException()
                        out.write(buffer, 0, read)
                    }
                }
            }
        }

        fun hasMicPermission(): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun recorder(): MicRecorder = MicRecorder(context)

        /** Runs the clip pipeline on [source] and encodes the result into the draft's media. */
        suspend fun saveClip(
            source: PcmAudio,
            edit: ClipEdit,
            label: String,
        ): ClipResult {
            val processed = withContext(Dispatchers.Default) { ClipProcessor.process(source, edit) }
            if (processed.isSilent) return ClipResult.Silent
            if (processed.durationMs !in
                Limits.CLIP_MIN_MS..Limits.CLIP_MAX_MS
            ) {
                return ClipResult.OutOfRange(processed.durationMs)
            }
            val name = drafts.newMediaFile("clip", "tmp").nameWithoutExtension
            val encoded = encoder.encode(processed, drafts.mediaDir, name)
            return ClipResult.Saved(
                DraftClip(file = encoded.file.name, label = label.trim(), durationMs = encoded.durationMs),
            )
        }

        /** Builds the draft into `cache/creator/<name>.soundpack.zip` and validates it. */
        suspend fun build(draft: CreatorDraft): WriteResult =
            io {
                val out = File(work, "pack.soundpack.zip")
                writer.write(draft.toPackDraft(drafts.mediaDir), out)
            }

        /** Copies a built pack to a document the user picked (the Storage Access Framework). */
        suspend fun export(
            file: File,
            target: Uri,
        ) = io {
            val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
        }

        /** A content URI for the share sheet, readable by the app the user picks (FileProvider, per-URI grant). */
        suspend fun shareUri(
            file: File,
            fileName: String,
        ): Uri =
            io {
                val shared = File(work, "shared").apply { mkdirs() }
                shared.listFiles().orEmpty().forEach { it.delete() }
                val copy = File(shared, fileName)
                file.copyTo(copy, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}$SHARE_AUTHORITY_SUFFIX", copy)
            }

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun sampled(bitmap: Bitmap): IntArray {
            val source =
                if (bitmap.config ==
                    Bitmap.Config.HARDWARE
                ) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
            val step = maxOf(1, maxOf(source.width, source.height) / SAMPLE_EDGE)
            val w = source.width / step
            val h = source.height / step
            return IntArray(w * h) { i -> source[(i % w) * step, (i / w) * step] }
        }

        class SourceTooLargeException : IOException("The file is larger than ${Limits.AUDIO_FILE_BYTES} bytes")

        companion object {
            /** FileProvider authority, after the application ID (AndroidManifest.xml). */
            const val SHARE_AUTHORITY_SUFFIX = ".creator"
            const val SEED_SUGGESTIONS = 4
            private const val SAVE_DELAY_MS = 300L
            private const val COPY_BUFFER = 256 * 1024
            private const val SAMPLE_EDGE = 112
            private const val MAX_COLORS = 128
            private const val ALPHA_SHIFT = 24
            private const val MIN_ALPHA = 128
            private const val OPAQUE = 0xFF000000.toInt()

            /** File extensions for audio MIME types, for picked files without a name. */
            private val AUDIO_EXTENSIONS =
                mapOf(
                    "audio/ogg" to "ogg",
                    "audio/opus" to "opus",
                    "audio/mpeg" to "mp3",
                    "audio/mp4" to "m4a",
                    "audio/x-m4a" to "m4a",
                    "audio/aac" to "aac",
                    "audio/flac" to "flac",
                    "audio/x-flac" to "flac",
                    "audio/wav" to "wav",
                    "audio/x-wav" to "wav",
                )
        }
    }

/** The longest recording and the clip limits the editor checks against. */
internal object ClipLimits {
    const val MIN_MS = Limits.CLIP_MIN_MS
    const val MAX_MS = Limits.CLIP_MAX_MS
    const val HINT_MS = Limits.LONG_CLIP_HINT_MS
    const val RECORDING_MS = Authoring.Audio.MAX_RECORDING_MS
}
