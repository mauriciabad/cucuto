package app.mauri.cucuto.video

import app.mauri.cucuto.video.Mix.CLIP_DB
import app.mauri.cucuto.video.Mix.PING_DB
import app.mauri.cucuto.video.Mix.UI_DB
import app.mauri.cucuto.video.scenes.DemoPacksScreen
import app.mauri.cucuto.video.scenes.EndCard
import app.mauri.cucuto.video.scenes.FirstVoiceScreen
import app.mauri.cucuto.video.scenes.LockScreen
import app.mauri.cucuto.video.scenes.LockScreenMessage
import app.mauri.cucuto.video.scenes.PickerScreen
import app.mauri.cucuto.video.scenes.RecentChatsScreen
import app.mauri.cucuto.video.scenes.SamePingStage
import app.mauri.cucuto.video.scenes.SetUpScreen
import app.mauri.cucuto.video.scenes.SimulateScreen
import app.mauri.cucuto.video.scenes.TheDoor

/** The plain ping of shot 1: `tap.ogg` an octave down, three times, identical. Declared first: top-level values
 * initialize in order. */
private val PING_TIMES = listOf(0.6, 1.6, 2.6)

/**
 * The explainer video (docs/explainer-video.md, shot list): ten shots, 58 s. This list is the project file: each shot
 * has its length, its picture, its sounds, its vibrations and its captions; voice lines are captions with a spoken
 * text. To add a feature, add a shot here (video/README.md). Times inside a shot are seconds from its start; the
 * comments give the brief's times on the whole video.
 */
internal val ExplainerVideo =
    Timeline(
        listOf(
            // 1 · 00:00–00:05 · Same ping: three messages, the same sound three times.
            Shot(
                id = "same-ping",
                duration = 5.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        sound(
                            "s1-ping",
                            0.5,
                            2.8,
                            "[The same plain ping, three times]",
                            "[El mismo aviso, tres veces]",
                        ),
                        voice(
                            "s1-vo",
                            2.8,
                            5.0,
                            "Same sound every time. Who was that?",
                            "Siempre el mismo sonido. ¿Quién era?",
                        ),
                    ),
                cues = PING_TIMES.map { Cue(it, BrandSounds.TAP, PING_DB, effect = "octave-down") },
                stage = { SamePingStage(PING_TIMES, questionAt = 2.9) },
            ) {
                LockScreen(
                    messages =
                        listOf(
                            LockScreenMessage(Cast.ada, PING_TIMES[0]),
                            LockScreenMessage(Cast.bea, PING_TIMES[1]),
                            LockScreenMessage(Cast.leo, PING_TIMES[2]),
                        ),
                )
            },
            // 2 · 00:05–00:09 · The door: the brand's signature, the cuckoo door pops open.
            Shot(
                id = "the-door",
                duration = 4.0,
                frame = ShotFrame.Full,
                captions =
                    listOf(
                        sound("s2-call", 0.3, 1.2, "[Cuckoo call: cu-cu]", "[Canto de cuco: cu-cu]"),
                        voice(
                            "s2-vo",
                            1.2,
                            4.0,
                            "Cucuto gives each WhatsApp\ncontact a voice.",
                            "Cucuto da voz a cada\ncontacto de WhatsApp.",
                        ),
                    ),
                cues = listOf(Cue(DOOR_OPENS, BrandSounds.ASSIGN, UI_DB)),
            ) { TheDoor(opensAt = DOOR_OPENS) },
            // 3 · 00:09–00:15 · Set up once: root, LSPosed, a self-test that turns green.
            Shot(
                id = "set-up",
                duration = 6.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        voice(
                            "s3-vo",
                            0.3,
                            4.6,
                            "Set it up once on a rooted\nAndroid 17 phone with LSPosed.",
                            "Se configura una vez, en un\nAndroid 17 con root y LSPosed.",
                        ),
                        sound("s3-chime", 4.8, 6.0, "[Success chime]", "[Sonido de éxito]"),
                    ),
                cues = listOf(Cue(4.8, BrandSounds.SUCCESS, UI_DB)),
            ) { SetUpScreen(testAt = 4.3, greenAt = 4.8) },
            // 4 · 00:15–00:21 · Get the demo packs: two packs, eight characters.
            Shot(
                id = "demo-packs",
                duration = 6.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        voice(
                            "s4-vo",
                            0.4,
                            4.8,
                            "Import a sound pack. The free demo\npacks bring eight characters.",
                            "Importa un pack de sonidos. Los de\ndemostración traen ocho personajes.",
                        ),
                        sound("s4-chime", 5.0, 6.0, "[Success chime]", "[Sonido de éxito]"),
                    ),
                cues = listOf(Cue(5.0, BrandSounds.SUCCESS, UI_DB)),
            ) { DemoPacksScreen(getAt = 1.3, installedAt = 5.0) },
            // 5 · 00:21–00:26 · Recent senders: Ada, Bea and Leo, no voice yet.
            Shot(
                id = "recent-chats",
                duration = 5.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        voice(
                            "s5-vo",
                            0.3,
                            4.6,
                            "Everyone who messaged you shows up here.\nLet's give Ada a voice.",
                            "Aquí aparece quien te ha escrito.\nVamos a darle voz a Ada.",
                        ),
                    ),
                cues = listOf(Cue(4.2, BrandSounds.TAP, UI_DB)),
            ) { RecentChatsScreen(highlightAt = 2.6, tapAt = 4.2) },
            // 6 · 00:26–00:36 · Tap to hear, tap to assign.
            Shot(
                id = "picker",
                duration = 10.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        voice("s6-vo-hear", 0.1, 1.7, "Tap a character to hear it.", "Toca un personaje para oírlo."),
                        sound("s6-cat", 1.8, 2.9, "[Cat mews]", "[El gato maúlla]"),
                        sound("s6-kettle", 2.9, 4.0, "[Kettle whistles]", "[La tetera silba]"),
                        sound("s6-frog", 4.0, 4.9, "[Frog ribbits]", "[La rana croa]"),
                        voice(
                            "s6-vo-assign",
                            4.9,
                            6.5,
                            "Tap it again, and it's Ada's.",
                            "Tócalo otra vez y es de Ada.",
                        ),
                        sound(
                            "s6-assign",
                            6.6,
                            8.4,
                            "[Cuckoo call, then the frog ribbits]",
                            "[Canto de cuco y la rana croa]",
                        ),
                    ),
                cues =
                    listOf(
                        Cue(1.8, Cast.cat.clip("mew"), CLIP_DB, pop = Cast.cat, side = Side.Left),
                        Cue(2.9, Cast.kettle.clip("whistle"), CLIP_DB, pop = Cast.kettle, side = Side.Right),
                        Cue(4.0, Cast.frog.clip("ribbit"), CLIP_DB, pop = Cast.frog, side = Side.Left),
                        Cue(ASSIGN_AT, BrandSounds.ASSIGN, UI_DB),
                        // The character starts on the call's second note (brand/sound-and-haptics.md).
                        Cue(ASSIGN_AT + SECOND_NOTE, Cast.frog.clip("ribbit"), CLIP_DB),
                    ),
            ) {
                PickerScreen(
                    taps = listOf(1.8 to Cast.cat, 2.9 to Cast.kettle, 4.0 to Cast.frog),
                    assignAt = ASSIGN_AT,
                    snackbarAt = 7.3,
                )
            },
            // 7 · 00:36–00:40 · The cuckoo door pops open on the frog: Ada has a voice.
            Shot(
                id = "first-voice",
                duration = 4.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        sound("s7-door", 0.4, 1.4, "[Cuckoo call, the frog croaks]", "[Canto de cuco, la rana croa]"),
                        voice(
                            "s7-vo",
                            1.4,
                            4.0,
                            "Next time Ada writes,\nyou hear Bleep the frog.",
                            "La próxima vez que Ada escriba,\noirás a la rana Bleep.",
                        ),
                    ),
                cues =
                    listOf(
                        Cue(DOOR_OPENS, BrandSounds.ASSIGN, UI_DB),
                        Cue(DOOR_OPENS + SECOND_NOTE, Cast.frog.clip("croak"), CLIP_DB),
                    ),
            ) { FirstVoiceScreen(opensAt = DOOR_OPENS) },
            // 8 · 00:40–00:46 · Simulate message: hear exactly what's coming, sound and vibration.
            Shot(
                id = "simulate",
                duration = 6.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        voice(
                            "s8-vo",
                            0.3,
                            3.0,
                            "Simulate a message to hear\nexactly what's coming.",
                            "Simula un mensaje y oye\njusto lo que llegará.",
                        ),
                        sound(
                            "s8-clip",
                            3.3,
                            5.5,
                            "[Frog ribbits, the phone vibrates twice]",
                            "[La rana croa y el móvil vibra dos veces]",
                        ),
                    ),
                cues = listOf(Cue(3.3, Cast.frog.clip("ribbit"), CLIP_DB, pop = Cast.frog, side = Side.Right)),
                vibrations = listOf(3.3),
            ) { SimulateScreen(tapAt = 1.6, playsAt = 3.3) },
            // 9 · 00:46–00:53 · The real thing: Ada writes (the frog), then Bea (the kettle).
            Shot(
                id = "the-real-thing",
                duration = 7.0,
                frame = ShotFrame.Phone,
                captions =
                    listOf(
                        sound(
                            "s9-ada",
                            0.8,
                            3.2,
                            "[WhatsApp message from Ada:\nthe frog ribbits]",
                            "[Mensaje de WhatsApp de Ada:\nla rana croa]",
                        ),
                        sound(
                            "s9-bea",
                            3.4,
                            4.5,
                            "[Message from Bea: the kettle whistles]",
                            "[Mensaje de Bea: la tetera silba]",
                        ),
                        voice(
                            "s9-vo",
                            4.6,
                            6.8,
                            "Now you know who wrote,\nwithout looking.",
                            "Ahora sabes quién te escribe,\nsin mirar.",
                        ),
                    ),
                cues =
                    listOf(
                        Cue(0.8, Cast.frog.clip("ribbit"), CLIP_DB, pop = Cast.frog, side = Side.Left),
                        Cue(3.4, Cast.kettle.clip("whistle"), CLIP_DB, pop = Cast.kettle, side = Side.Right),
                    ),
                vibrations = listOf(0.8, 3.4),
            ) {
                LockScreen(
                    messages =
                        listOf(
                            LockScreenMessage(Cast.ada, 0.8, voice = Cast.frog),
                            LockScreenMessage(Cast.bea, 3.4, voice = Cast.kettle),
                        ),
                )
            },
            // 10 · 00:53–00:58 · End card: the logo, the line, the address.
            Shot(
                id = "end-card",
                duration = 5.0,
                frame = ShotFrame.Full,
                captions =
                    listOf(
                        sound("s10-call", 0.2, 1.0, "[Cuckoo call: cu-cu]", "[Canto de cuco: cu-cu]"),
                        voice(
                            "s10-vo",
                            1.0,
                            4.85,
                            "Cucuto. Free and open source,\nat cucuto.mauri.app.",
                            "Cucuto. Gratis y de código abierto,\nen cucuto.mauri.app.",
                            spoken = "Cucuto. Free and open source, at cucuto dot mauri dot app.",
                        ),
                    ),
                cues = listOf(Cue(0.2, BrandSounds.ASSIGN, UI_DB)),
            ) { EndCard(logoAt = 0.2) },
        ),
    )

/** The door swings open 450 ms into its motion (brand/motion.md, the signature). */
private const val DOOR_OPENS = 0.45

/** The assign moment of shot 6 (00:32.6). */
private const val ASSIGN_AT = 6.6

/** The character's clip starts on the cuckoo call's second note, about 130 ms in. */
private const val SECOND_NOTE = 0.13

/** Video time of the poster frame: the burst around the frog tile (docs/explainer-video.md, 00:32.8). */
internal const val POSTER_AT = 32.8
