package app.mauri.cucuto.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import app.mauri.cucuto.provider.ClipProvider
import app.mauri.cucuto.xposed.route.Targets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: gives the most recent WhatsApp sender a generated test voice, so the supervised device session
 * can verify the real WhatsApp path (#15) before the pack importer and the assignment UI exist.
 *
 * It writes a tiny pack (`app.mauri.cucuto.devtest`, three synthesised chirps and a click-tick haptic) straight into
 * `files/packs/` in the importer's layout (ADR 0008), records it as installed, and assigns it through the normal
 * repositories, so the routing table is built and published exactly as in production. The `app.mauri.` prefix is
 * reserved for the project, so no real pack can collide.
 */
@Singleton
class TestVoice
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val packs: InstalledPackRepository,
        private val contacts: ContactRepository,
        private val assignments: AssignmentRepository,
        private val seenSenders: SeenSenderRepository,
    ) {
        val available: Boolean get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        /** Assigns the test voice to the newest WhatsApp conversation seen; returns its display name or key shape. */
        suspend fun assignToLatestSender(): Result =
            withContext(Dispatchers.IO) {
                if (!available) return@withContext Result.Unavailable
                val sender =
                    seenSenders.observeRecent(SCAN).first().firstOrNull { it.packageName in Targets.MESSENGERS }
                        ?: return@withContext Result.NoSender
                installPack()
                val contactId =
                    contacts.findOrCreate(
                        sender.packageName,
                        sender.conversationKey,
                        sender.displayName,
                        sender.isGroup,
                    )
                assignments.assign(contactId, PACK_ID, CHARACTER_ID)
                Result.Assigned(sender.displayName ?: sender.conversationKey.substringAfterLast('@', "?"))
            }

        private suspend fun installPack() {
            val dir = File(context.filesDir, "${ClipProvider.PACKS_DIR}/$PACK_ID/$VERSION")
            val clips = File(dir, "characters/$CHARACTER_ID/clips").apply { mkdirs() }
            TONES.forEach { (name, frequencies) -> File(clips, "$name.wav").writeBytes(SelfTestSound.wav(frequencies)) }
            File(dir, "pack.json").writeText(MANIFEST)
            File(context.filesDir, "${ClipProvider.PACKS_DIR}/$PACK_ID/active").writeText("$VERSION\n")
            packs.recordInstalled(
                InstalledPackEntity(
                    packId = PACK_ID,
                    version = VERSION,
                    name = "Cucuto test voice",
                    path = dir.path,
                    sha256 = "",
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    source = PackSource.LOCAL,
                    fileName = null,
                    installedAt = System.currentTimeMillis(),
                ),
            )
        }

        sealed interface Result {
            data object Unavailable : Result

            data object NoSender : Result

            data class Assigned(
                val label: String,
            ) : Result
        }

        private companion object {
            const val PACK_ID = "app.mauri.cucuto.devtest"
            const val VERSION = "1.0.0"
            const val CHARACTER_ID = "chirp"
            const val SCAN = 20

            /** Three distinct two-note chirps, so the shuffle bag is audible. */
            val TONES =
                listOf(
                    "up" to (660.0 to 990.0),
                    "down" to (990.0 to 660.0),
                    "high" to (880.0 to 1320.0),
                )

            val MANIFEST =
                """
                {
                  "formatVersion": 1,
                  "id": "$PACK_ID",
                  "version": "$VERSION",
                  "name": "Cucuto test voice",
                  "characters": [
                    {
                      "id": "$CHARACTER_ID",
                      "name": "Chirp",
                      "clips": [
                        { "file": "characters/$CHARACTER_ID/clips/up.wav" },
                        { "file": "characters/$CHARACTER_ID/clips/down.wav" },
                        { "file": "characters/$CHARACTER_ID/clips/high.wav" }
                      ],
                      "haptic": {
                        "mode": "custom",
                        "events": [
                          { "at": 0, "preset": "CLICK", "scale": 0.9 },
                          { "at": 130, "preset": "TICK", "scale": 0.5 }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()
        }
    }
