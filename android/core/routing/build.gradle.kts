// `:core:routing`: the routing table the app publishes to the module, and its compact text codec.
// Plain Kotlin with no dependencies: the app writes it, and `:xposed` reads it inside system_server.
plugins {
    alias(libs.plugins.cucuto.kotlin.library)
}
