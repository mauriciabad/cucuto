// `:core:backup`: backup export (plain, full and passphrase-encrypted), reading and idempotent restore (ADR 0013).
plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions {
        unitTests.all {
            // Robolectric's Android 16+ runtime touches FileDescriptor internals that JDK 21 hides by default.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

// spec/ is the cross-platform contract: tests check the backup fixtures and build full backups from pack fixtures.
val specDir: File = layout.projectDirectory.dir("../../../spec").asFile

dependencies {
    implementation(projects.core.data)
    // Installed pack files (full backups) and the import source of included packs; brings pack-spec (validation).
    implementation(projects.core.pack)
    // Repository credentials, for encrypted backups that include them.
    implementation(projects.core.repo)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.kotlinx.coroutines.core)
    // Argon2id and streaming AES-256-GCM (the platform's GCM buffers the whole ciphertext when decrypting).
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("cucuto.specDir", specDir.absolutePath)
    inputs.dir(specDir).withPropertyName("specDir").withPathSensitivity(PathSensitivity.RELATIVE)
}
