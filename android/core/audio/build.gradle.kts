// `:core:audio`: the pack creator's audio (#47, ADR 0034). Decoding any supported file and recording the microphone
// to mono PCM, the clip pipeline (cut, silence trimming, resampling to 48 kHz, EBU R128 loudness), Ogg Opus encoding
// with the platform's MediaCodec and MediaMuxer, and preview playback. The DSP in `dsp/` is plain Kotlin, tested on
// the JVM; the platform classes are thin wrappers around it.
plugins {
    alias(libs.plugins.cucuto.android.library)
}

dependencies {
    // The creators' shared targets (Authoring.Audio) and the container probe that checks every encoded clip.
    implementation("app.mauri.cucuto:pack-spec")
    implementation(libs.kotlinx.coroutines.android)
}
