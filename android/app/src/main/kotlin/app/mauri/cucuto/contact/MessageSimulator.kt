package app.mauri.cucuto.contact

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.core.os.bundleOf
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.routing.RoutingSink
import app.mauri.cucuto.core.data.routing.RoutingTableSource
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.routing.XposedFramework
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.diagnostics.OwnAlertSpacing
import app.mauri.cucuto.provider.ClipPaths
import app.mauri.cucuto.provider.ClipProvider
import app.mauri.cucuto.provider.ClipTarget
import app.mauri.cucuto.provider.ModuleEventSink
import app.mauri.cucuto.voice.VoicePreview
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.route.AlertRouter
import app.mauri.cucuto.xposed.route.NotificationSnapshot
import app.mauri.cucuto.xposed.route.Targets
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/** "Simulate message" (contact detail, #26): plays what a chat's next WhatsApp message will do (ADR 0025). */
interface MessageSimulator {
    /** Which path a simulation takes right now. Asks LSPosed (Binder calls); call off the main thread. */
    suspend fun path(): SimulatePath

    /**
     * Posts Cucuto's simulate notification for [chat]. The module decides it like a real alert of that chat and
     * plays the clip and haptic through the system's notification sound and vibration.
     */
    suspend fun throughModule(
        chat: ChatKey,
        title: String,
    ): ModuleSimulation

    /** Decides like the module (same table, router and shuffle-bag rules) and plays the result in the app. */
    suspend fun inApp(chat: ChatKey): InAppSimulation

    /** Stops an in-app simulation that is playing. */
    fun stop()
}

/** Result of [MessageSimulator.throughModule]. */
sealed interface ModuleSimulation {
    /** Posted at [atMillis] (wall clock); the module reports its decision a few seconds later. */
    data class Posted(
        val atMillis: Long,
    ) : ModuleSimulation

    /** Notifications are blocked for Cucuto. */
    data object Blocked : ModuleSimulation

    /** Android would mute it (per-app cooldown); try again in [seconds]. */
    data class TooSoon(
        val seconds: Int,
    ) : ModuleSimulation
}

/** Result of [MessageSimulator.inApp]. */
sealed interface InAppSimulation {
    /** [clipName] played with [haptic] (what the module would do with the vibration). */
    data class Played(
        val clipName: String,
        val haptic: HapticPlayback,
        val durationMs: Long,
    ) : InAppSimulation

    /** Nothing to swap: a real message would play WhatsApp's own tone, for [outcome]'s reason. */
    data class WhatsAppTone(
        val outcome: Outcome,
    ) : InAppSimulation

    /** The clip could not be found or played. */
    data object Failed : InAppSimulation
}

/** What happens to the vibration of the simulated alert. */
enum class HapticPlayback {
    /** The contact's or character's own pattern plays. */
    Custom,

    /** WhatsApp's own vibration is kept (not reproduced by the in-app preview). */
    WhatsApp,

    /** No vibration at all (per-contact override). */
    Off,
}

/** The [HapticPlayback] for the haptic the router chose. */
internal fun Haptic?.playback(): HapticPlayback =
    when (this) {
        is Haptic.Custom -> HapticPlayback.Custom
        Haptic.Off -> HapticPlayback.Off
        else -> HapticPlayback.WhatsApp
    }

/**
 * The file a routing-table clip URI names: `content://app.mauri.cucuto.clips/…` resolved against the installed packs
 * with the clip provider's own rules, or a `file://` mirror copy that exists. Null for anything else.
 */
internal fun clipFile(
    uri: String,
    packsRoot: File,
): File? {
    val parsed =
        try {
            URI(uri)
        } catch (_: URISyntaxException) {
            return null
        }
    val path = parsed.path ?: return null
    return when (parsed.scheme) {
        "file" -> {
            File(path).takeIf { it.isFile }
        }

        "content" -> {
            if (parsed.authority != RoutingContract.CLIP_AUTHORITY) return null
            val target = ClipPaths.parse(path.split('/').filter { it.isNotEmpty() }) as? ClipTarget.PackClip
            target?.let { ClipPaths.resolve(packsRoot, it) }
        }

        else -> {
            null
        }
    }
}

/** The clip's file name without its extension, for the UI (`characters/frog/clips/ribbit.ogg` → `ribbit`). */
internal fun clipName(pathOrUri: String): String = pathOrUri.substringAfterLast('/').substringBeforeLast('.')

/**
 * [MessageSimulator] for the device. The module path posts on [Targets.SIMULATE_CHANNEL] with the chat named in
 * the extras; the in-app path runs [AlertRouter] on the table the app publishes.
 */
@Singleton
class SystemMessageSimulator
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val sink: RoutingSink,
        private val framework: XposedFramework,
        private val events: ModuleEventSink,
        private val tables: RoutingTableSource,
        private val preview: VoicePreview,
        private val spacing: OwnAlertSpacing,
    ) : MessageSimulator {
        /** The app's own router and shuffle bags: the same rules as the module's, with an independent rotation. */
        private val router = AlertRouter()

        override suspend fun path(): SimulatePath =
            withContext(Dispatchers.IO) {
                val connection = sink.connection.value
                val report =
                    if (connection is SinkConnection.Connected) {
                        try {
                            framework.report()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                SimulatePaths.choose(
                    SimulateInputs(
                        connection = connection,
                        framework = report,
                        moduleStatus = events.status.value,
                        bootTimeMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
                        appUpdatedAtMillis = appUpdatedAt(),
                    ),
                )
            }

        override suspend fun throughModule(
            chat: ChatKey,
            title: String,
        ): ModuleSimulation =
            withContext(Dispatchers.IO) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!manager.areNotificationsEnabled()) return@withContext ModuleSimulation.Blocked
                spacing.tryAcquire()?.let { return@withContext ModuleSimulation.TooSoon(it) }
                ensureChannel(manager)
                val notification =
                    Notification
                        .Builder(context, Targets.SIMULATE_CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_cucuto)
                        .setContentTitle(title)
                        .setContentText(context.getString(R.string.simulate_notification_text))
                        // Exempt from the post-boot "avalanche" muting, like the self-test (SelfTestNotifier).
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .setTimeoutAfter(NOTIFICATION_TIMEOUT_MILLIS)
                        .addExtras(
                            bundleOf(
                                Targets.EXTRA_SIMULATE_PACKAGE to chat.packageName,
                                Targets.EXTRA_SIMULATE_KEY to chat.conversationKey,
                            ),
                        ).build()
                val postedAt = System.currentTimeMillis()
                manager.notify(NOTIFICATION_ID, notification)
                ModuleSimulation.Posted(postedAt)
            }

        override suspend fun inApp(chat: ChatKey): InAppSimulation {
            val decision =
                withContext(Dispatchers.Default) {
                    val table = tables.tables.first()
                    router.decide(NotificationSnapshot(chat.packageName, shortcutId = chat.conversationKey), table)
                } ?: return InAppSimulation.Failed
            val uri = decision.clipUri ?: return InAppSimulation.WhatsAppTone(decision.outcome)
            val file =
                withContext(Dispatchers.IO) { clipFile(uri, File(context.filesDir, ClipProvider.PACKS_DIR)) }
                    ?: return InAppSimulation.Failed
            val duration = preview.playClip(file, decision.haptic) ?: return InAppSimulation.Failed
            return InAppSimulation.Played(clipName(uri), decision.haptic.playback(), duration)
        }

        override fun stop() = preview.stop()

        private fun appUpdatedAt(): Long =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            } catch (_: Exception) {
                Long.MAX_VALUE
            }

        private fun ensureChannel(manager: NotificationManager) {
            if (manager.getNotificationChannel(Targets.SIMULATE_CHANNEL) != null) return
            // Sound and vibration on, or the framework never reaches playSound/playVibration for the hook to swap.
            // The default vibration (no custom pattern) stands in for WhatsApp's when the contact keeps it.
            val channel =
                NotificationChannel(
                    Targets.SIMULATE_CHANNEL,
                    context.getString(R.string.simulate_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.simulate_channel_description)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                }
            manager.createNotificationChannel(channel)
        }

        private companion object {
            const val NOTIFICATION_ID = 0xC0C1

            /** The simulated notification clears itself; it only exists to be heard and felt. */
            const val NOTIFICATION_TIMEOUT_MILLIS = 30_000L
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface MessageSimulatorModule {
    @Binds fun messageSimulator(simulator: SystemMessageSimulator): MessageSimulator
}
