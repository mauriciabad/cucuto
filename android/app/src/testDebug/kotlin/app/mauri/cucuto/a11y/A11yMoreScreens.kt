package app.mauri.cucuto.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.mauri.cucuto.core.backup.BackupPackReference
import app.mauri.cucuto.core.backup.BackupPackSource
import app.mauri.cucuto.core.backup.BackupPackSourceType
import app.mauri.cucuto.core.backup.BackupPackType
import app.mauri.cucuto.core.backup.PackRestoreAction
import app.mauri.cucuto.core.backup.PackRestoreItem
import app.mauri.cucuto.core.conditions.Place
import app.mauri.cucuto.core.conditions.SunTimes
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.routing.PublishState
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.daynight.DayNightContent
import app.mauri.cucuto.daynight.DayNightUiState
import app.mauri.cucuto.daynight.LocateStatus
import app.mauri.cucuto.diagnostics.DiagnosticsContent
import app.mauri.cucuto.diagnostics.DiagnosticsMessage
import app.mauri.cucuto.diagnostics.DiagnosticsState
import app.mauri.cucuto.packspec.authoring.ConditionForm
import app.mauri.cucuto.packspec.authoring.DateForm
import app.mauri.cucuto.packspec.authoring.LoopMode
import app.mauri.cucuto.packspec.authoring.Moment
import app.mauri.cucuto.packspec.authoring.RingtoneForm
import app.mauri.cucuto.packspec.authoring.TimeOfDay
import app.mauri.cucuto.provider.ReceivedStatus
import app.mauri.cucuto.sample.SampleChatContent
import app.mauri.cucuto.screenshots.demoSampleChatState
import app.mauri.cucuto.ui.creator.ClipConditionsPage
import app.mauri.cucuto.ui.creator.ConditionEditorUi
import app.mauri.cucuto.ui.creator.CropperPage
import app.mauri.cucuto.ui.creator.CropperUi
import app.mauri.cucuto.ui.creator.PictureTarget
import app.mauri.cucuto.ui.creator.RingtoneEditorPage
import app.mauri.cucuto.ui.creator.RingtoneEditorUi
import app.mauri.cucuto.ui.settings.restore.ConflictRow
import app.mauri.cucuto.ui.settings.restore.PackRow
import app.mauri.cucuto.ui.settings.restore.PackStatus
import app.mauri.cucuto.ui.settings.restore.RestoreActions
import app.mauri.cucuto.ui.settings.restore.RestoreContent
import app.mauri.cucuto.ui.settings.restore.RestoreStep
import app.mauri.cucuto.ui.settings.restore.VoiceLabel
import app.mauri.cucuto.xposed.events.ModuleStatus
import java.time.Instant
import java.time.ZoneId

/**
 * The screens added to the accessibility checks in #226, with made-up content: Diagnostics, Day and night, the
 * creator's cropper, and the restore steps before the summary ([A11yPushedScreens] has the summary). [A11yScreens.all]
 * lists them with the others.
 */
internal object A11yMoreScreens {
    val all: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "diagnostics" to { Diagnostics() },
            "day-night" to { DayNight() },
            "creator-cropper" to { CreatorCropper() },
            "creator-clip-conditions" to { CreatorClipConditions() },
            "creator-ringtone" to { CreatorRingtone() },
            "restore-passphrase" to { Restore(RestoreStep.Passphrase(wrong = true)) },
            "restore-packs" to { Restore(restorePacks) },
            "restore-conflicts" to { Restore(restoreConflicts) },
            "sample-chat" to { SampleChat() },
        )

    /** The sample chat (ADR 0075) with the demo packs: Bleep the frog's voice and pond song. */
    @Composable
    private fun SampleChat() {
        SampleChatContent(
            state = demoSampleChatState(),
            ringing = null,
            onBack = {},
            onChooseVoice = {},
            onChooseRingtone = {},
            onPlayMessage = {},
            onToggleCall = {},
            onGetDemoPacks = {},
        )
    }

    @Composable
    private fun Diagnostics() {
        // Relative to now, so "2 minutes ago" and "same table as the app" read the same on every run.
        val now = remember { System.currentTimeMillis() }
        val module =
            ModuleStatus(
                bootTimeMillis = now - SystemClock.elapsedRealtime(),
                hooks = listOf("buzzBeepBlinkLocked", "playSound", "playVibration"),
                missingHooks = emptyList(),
                tableGeneration = GENERATION,
                tableRoutes = ROUTES,
                decisions = 37,
                slowDecisions = 0,
                maxHookMicros = 850,
                dropped = 0,
            )
        DiagnosticsContent(
            state =
                DiagnosticsState(
                    connection = SinkConnection.Connected("LSPosed", 102),
                    publish =
                        PublishState.Published(
                            generation = GENERATION,
                            routes = ROUTES,
                            voices = 9,
                            encodedChars = 3_200,
                        ),
                    moduleStatus = ReceivedStatus(receivedAtMillis = now - TWO_MINUTES, status = module),
                    decisions =
                        listOf(
                            DecisionLogEntity(
                                id = 2,
                                timestamp = DECISION_TIME,
                                packageName = "com.whatsapp",
                                conversationKey = "100000000000001@lid",
                                outcome = DecisionOutcome.SWAPPED,
                                clipUri = "content://app.mauri.cucuto.clips/critters/frog/croak.ogg",
                                hookMicros = 420,
                            ),
                            DecisionLogEntity(
                                id = 1,
                                timestamp = DECISION_TIME - TWO_MINUTES,
                                packageName = "com.whatsapp",
                                conversationKey = "100000000000002@lid",
                                outcome = DecisionOutcome.UNASSIGNED,
                                hookMicros = 180,
                            ),
                        ),
                ),
            message = DiagnosticsMessage.None,
            testVoiceAvailable = true,
            onBack = {},
            onEnabledChange = {},
            onPostTest = {},
            onAssignTestVoice = {},
        )
    }

    @Composable
    private fun DayNight() {
        DayNightContent(
            state =
                DayNightUiState(
                    place = Place(latitude = 41.39, longitude = 2.17, label = "Barcelona"),
                    today =
                        SunTimes.Regular(
                            sunrise = Instant.parse("2026-10-24T06:21:00Z"),
                            sunset = Instant.parse("2026-10-24T17:02:00Z"),
                        ),
                    isDay = true,
                    zone = ZoneId.of("Europe/Madrid"),
                ),
            locate = LocateStatus.Idle,
            onBack = {},
            onForget = {},
            onChoose = {},
            onSaveCoordinates = { _, _ -> null },
            onUseLocation = {},
        )
    }

    @Composable
    private fun CreatorCropper() {
        val picture = remember { stripes() }
        CropperPage(
            cropper = CropperUi(target = PictureTarget.Sprite("frog"), picture = picture),
            saving = false,
            onBack = {},
            onCrop = {},
            onDone = {},
        )
    }

    /** "When it plays" for a good-morning clip that is also rare (#322). */
    @Composable
    private fun CreatorClipConditions() {
        ClipConditionsPage(
            editor =
                ConditionEditorUi(
                    characterKey = "frog",
                    clipKey = "a",
                    clipLabel = "Good morning",
                    form =
                        ConditionForm(
                            moment = Moment.FIRST_OF_DAY,
                            time = TimeOfDay.HOURS,
                            from = "05:00",
                            to = "12:00",
                            weekdays = listOf("sat", "sun"),
                            dates = listOf(DateForm("12-24", "01-06")),
                            oneIn = 25,
                        ),
                ),
            onBack = {},
            onChange = {},
            onSave = {},
        )
    }

    /** A night version of a song: an intro, then a part that loops, with a fade-in (#322). */
    @Composable
    private fun CreatorRingtone() {
        RingtoneEditorPage(
            editor =
                RingtoneEditorUi(
                    characterKey = "frog",
                    ringtoneKey = "night",
                    file = "pond-song.ogg",
                    durationMs = 32_000,
                    form =
                        RingtoneForm(
                            label = "Pond song (night)",
                            condition = ConditionForm(time = TimeOfDay.NIGHT, seasons = listOf("autumn", "winter")),
                            loop = LoopMode.SECTION,
                            loopStartMs = 4_000,
                            loopEndMs = 28_000,
                            fadeInMs = 1_500,
                        ),
                ),
            playing = false,
            onBack = {},
            onChange = {},
            onPlay = {},
            onSave = {},
        )
    }

    @Composable
    private fun Restore(step: RestoreStep) {
        RestoreContent(step = step, actions = RestoreActions())
    }

    private fun reference(
        id: String,
        name: String,
        source: BackupPackSourceType,
    ) = BackupPackReference(
        id = id,
        type = BackupPackType.PACK,
        version = "1.0.0",
        name = name,
        sha256 = "0".repeat(SHA_CHARS),
        source = BackupPackSource(type = source, repositoryUrl = "https://packs.example.com/repository.json"),
    )

    private val restorePacks =
        RestoreStep.Packs(
            rows =
                listOf(
                    PackRow(
                        PackRestoreItem(
                            reference("app.example.critters", "Chiptune critters", BackupPackSourceType.REPOSITORY),
                            PackRestoreAction.DOWNLOAD_FROM_REPOSITORY,
                            listOf("app.example.critters"),
                        ),
                        PackStatus.Done,
                    ),
                    PackRow(
                        PackRestoreItem(
                            reference("app.example.kitchen", "Kitchen orchestra", BackupPackSourceType.REPOSITORY),
                            PackRestoreAction.DOWNLOAD_FROM_REPOSITORY,
                            listOf("app.example.kitchen"),
                        ),
                        PackStatus.Working(fraction = 0.4f),
                    ),
                    PackRow(
                        PackRestoreItem(
                            reference("app.example.mine", "My recordings", BackupPackSourceType.LOCAL),
                            PackRestoreAction.PICK_FILE,
                            listOf("app.example.mine"),
                        ),
                        PackStatus.Pending,
                    ),
                ),
            createdAt = "2026-10-24T09:30:00Z",
            contacts = 12,
        )

    private val restoreConflicts =
        RestoreStep.Conflicts(
            rows =
                listOf(
                    ConflictRow(
                        "1",
                        "Ada",
                        VoiceLabel("Bleep the frog", "Chiptune critters", null),
                        VoiceLabel("Hoot the owl", "Chiptune critters", null),
                    ),
                    ConflictRow(
                        "2",
                        null,
                        VoiceLabel(null, null, GroupPolicy.GROUP),
                        VoiceLabel("Kettle", "Kitchen orchestra", GroupPolicy.PER_SENDER),
                    ),
                ),
            selected = setOf("1"),
        )

    /** A made-up picture to frame: green and yellow stripes. */
    private fun stripes(): Bitmap {
        val bitmap = Bitmap.createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF3A7D44.toInt())
        val paint = Paint().apply { color = 0xFFF2C14E.toInt() }
        for (x in -PICTURE_HEIGHT until PICTURE_WIDTH step STRIPE * 2) {
            canvas.drawRect(x.toFloat(), 0f, (x + STRIPE).toFloat(), PICTURE_HEIGHT.toFloat(), paint)
        }
        return bitmap
    }

    private const val GENERATION = 42L
    private const val ROUTES = 12
    private const val TWO_MINUTES = 120_000L

    /** 2026-10-24 09:30 in Madrid. */
    private const val DECISION_TIME = 1_792_827_000_000L
    private const val SHA_CHARS = 64
    private const val PICTURE_WIDTH = 480
    private const val PICTURE_HEIGHT = 320
    private const val STRIPE = 40
}
