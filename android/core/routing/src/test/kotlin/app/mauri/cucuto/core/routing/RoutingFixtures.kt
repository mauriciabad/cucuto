package app.mauri.cucuto.core.routing

internal object RoutingFixtures {
    const val WHATSAPP = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    val frogCustom =
        Haptic.Custom(
            listOf(
                HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                HapticEvent.Preset(120, HapticPreset.TICK),
                HapticEvent.Envelope(
                    atMs = 220,
                    points = listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)),
                    milliInitialSharpness = 300,
                ),
                HapticEvent.Envelope(600, listOf(ControlPoint(0, 0, 50))),
                HapticEvent.Preset(900, HapticPreset.LOW_TICK, 0),
            ),
        )

    /**
     * An envelope shaped like an auto haptics analysis of a voiced clip (one event, up to 16 points, ending at zero
     * intensity), different for every [seed]. Values are deliberately off [HapticGrid].
     */
    fun autoEnvelope(
        seed: Int,
        points: Int = 16,
    ): Haptic.Custom {
        val random = java.util.Random(seed.toLong())
        val controlPoints =
            (1..points).map { i ->
                ControlPoint(
                    milliIntensity = if (i == points) 0 else 150 + random.nextInt(851),
                    milliSharpness = random.nextInt(1001),
                    durationMs = 20 * (1 + random.nextInt(8)) + if (i == points) 5 else 0,
                )
            }
        return Haptic.Custom(listOf(HapticEvent.Envelope(20 * random.nextInt(10), controlPoints, random.nextInt(1001))))
    }

    fun voice(
        packId: String,
        characterId: String,
        clips: Int,
        haptic: Haptic = Haptic.Auto,
    ): Voice =
        Voice(
            packId = packId,
            characterId = characterId,
            clips =
                (1..clips).map { i ->
                    Clip(
                        uri = RoutingContract.clipUri(packId, characterId, "characters/$characterId/clips/clip-$i.ogg"),
                        milliWeight = 1000 * i,
                        haptic =
                            if (i % 2 == 0) {
                                Haptic.Custom(
                                    listOf(
                                        HapticEvent.Envelope(
                                            0,
                                            listOf(ControlPoint(700, 500, 80), ControlPoint(0, 300, 40)),
                                        ),
                                    ),
                                )
                            } else {
                                null
                            },
                    )
                },
            haptic = haptic,
        )

    /** A small table touching every feature of the format. */
    val sample: RoutingTable =
        RoutingTable(
            generation = 1_761_000_000_123,
            enabled = true,
            voices =
                listOf(
                    voice("app.mauri.demo.critters", "frog", 3, frogCustom),
                    voice("app.mauri.demo.critters", "owl", 1, Haptic.Keep),
                    voice("io.github.someone.chimes", "bell", 2, Haptic.Off),
                    // One clip outside the common folder, to exercise the shared URI prefix.
                    Voice(
                        "io.github.someone.chimes",
                        "gong",
                        listOf(Clip("content://app.mauri.cucuto.clips/x/a.ogg"), Clip("content://other/b.ogg")),
                        Haptic.Auto,
                    ),
                ),
            routes =
                listOf(
                    Route(WHATSAPP, "34600000001@s.whatsapp.net", 0, seed = 42, flags = 0, contactId = 1),
                    Route(WHATSAPP, "123456789012345@lid", 0, seed = 42, flags = 0, contactId = 1),
                    Route(WHATSAPP, "34600000002@s.whatsapp.net", 1, seed = -7, flags = 0, contactId = 2),
                    Route(
                        WHATSAPP,
                        "120363000000000001@g.us",
                        Route.NO_VOICE,
                        9,
                        Route.FLAG_GROUP or Route.FLAG_PER_SENDER,
                        3,
                    ),
                    Route(WHATSAPP, "120363000000000002@g.us", 2, 10, Route.FLAG_GROUP, 4),
                    Route(BUSINESS, "34600000003@s.whatsapp.net", 3, Int.MIN_VALUE, 0, Long.MAX_VALUE),
                    // Fallback keys can be display names: anything but the escape characters is stored verbatim.
                    Route(BUSINESS, "Tab\there, 100% new\nline\r", 1, Int.MAX_VALUE, 1 shl 20, 6),
                ),
        )
}
