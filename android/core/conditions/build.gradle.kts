// `:core:conditions`: when conditional clips and ringtone variants apply (a pack's `when`), worked out on the phone.
// Sunrise and sunset come from the NOAA solar algorithm in plain Kotlin: no library, no network (ADR 0037).
plugins {
    alias(libs.plugins.cucuto.kotlin.library)
}

dependencies {
    // Conditions are compiled into the routing table's TimeCondition instants.
    api(projects.core.routing)
}
