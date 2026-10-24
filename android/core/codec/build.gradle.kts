// `:core:codec`: the platform audio decoding loop (MediaExtractor and MediaCodec) shared by the importer's
// test-decodes (`:core:pack`) and auto haptics at install (`:core:haptics`): batched input when the decoder takes it,
// and sessions that keep one decoder between files (ADR 0077). No dependencies, so either module can use it.
plugins {
    alias(libs.plugins.cucuto.android.library)
}
