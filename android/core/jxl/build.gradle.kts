import app.mauri.cucuto.buildlogic.CargoNdkTask

plugins {
    alias(libs.plugins.cucuto.android.library)
}

// JPEG XL decoding with jxl-rs (ADR 0006): the Rust crate in rust/ is built for arm64-v8a by cargo-ndk and packaged
// as libcucuto_jxl.so. Only arm64-v8a: the app targets Android 17 phones (see README.md).
val jxlAbi = "arm64-v8a"
val ndkVersionPinned: String = libs.versions.ndk.get()

android {
    ndkVersion = ndkVersionPinned
}

val cargoNdk =
    tasks.register<CargoNdkTask>("cargoNdk") {
        description = "Builds rust/ (libcucuto_jxl.so) for $jxlAbi with cargo-ndk."
        val crate = layout.projectDirectory.dir("rust")
        crateDir.set(crate)
        crateFiles.from(
            crate.dir("src"),
            crate.file("Cargo.toml"),
            crate.file("Cargo.lock"),
            crate.file("rust-toolchain.toml"),
        )
        abi.set(jxlAbi)
        apiLevel.set(
            libs.versions.sdk.min
                .get()
                .toInt(),
        )
        ndkVersion.set(ndkVersionPinned)
        sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
        outputDir.set(layout.buildDirectory.dir("generated/jniLibs/cargoNdk"))
    }

androidComponents {
    onVariants { variant ->
        // One release build of the crate serves every variant: a debug build of jxl-rs is too slow to be useful.
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdkTask::outputDir)
    }
}

dependencies {
    api(libs.coil.core)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
