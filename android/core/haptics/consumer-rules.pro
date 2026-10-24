# BuilderEffects is the only class that touches VibrationEffect.Builder, Envelope and Preset: hidden API for apps on
# API 37.1, usable from system_server only (issue #139, ADR 0026). Keep it a class of its own so R8 never merges or
# inlines it into the public-API classes the app process loads.
-keep,allowobfuscation class app.mauri.cucuto.core.haptics.BuilderEffects { *; }
