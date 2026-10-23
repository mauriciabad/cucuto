package app.mauri.cucuto.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Builds a Rust `cdylib` crate for Android with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) into
 * `<outputDir>/<abi>/lib<name>.so`, for a variant's `jniLibs` (wired with `addGeneratedSourceDirectory`).
 *
 * Reproducible: the crate pins its compiler in `rust-toolchain.toml` (rustup installs it on first use), its
 * dependencies in `Cargo.lock` (`--locked`), and the NDK version is an input. Cacheable: the inputs are the crate's
 * sources and manifests plus the ABI, API level and NDK version, so an unchanged crate comes from the Gradle build
 * cache and cargo does not run at all. Cargo keeps its own incremental state in the crate's `target/`.
 */
@CacheableTask
abstract class CargoNdkTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    /** The crate root (holds `Cargo.toml`); cargo runs here and writes `target/` here. */
    @get:Internal abstract val crateDir: DirectoryProperty

    /** Everything that changes the build: `src/`, `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val crateFiles: ConfigurableFileCollection

    /** Android ABI name, e.g. `arm64-v8a`. */
    @get:Input abstract val abi: Property<String>

    /** `minSdk`: the API level of the NDK sysroot to link against. */
    @get:Input abstract val apiLevel: Property<Int>

    /** NDK version (`ndk/<version>` in the SDK); the path is machine specific, so only the version is an input. */
    @get:Input abstract val ndkVersion: Property<String>

    @get:Internal abstract val sdkDirectory: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val ndk = sdkDirectory.get().dir("ndk/${ndkVersion.get()}").asFile
        check(ndk.isDirectory) {
            "NDK ${ndkVersion.get()} not found at $ndk. Install it with " +
                "`sdkmanager --install \"ndk;${ndkVersion.get()}\"` (or `android sdk install ndk/${ndkVersion.get()}`)."
        }
        // rustup's proxies (which honour rust-toolchain.toml) and `cargo install` binaries come first: the Gradle
        // daemon often starts without the login shell's PATH.
        val home = System.getProperty("user.home")
        val path =
            listOf("$home/.cargo/bin", "/opt/homebrew/opt/rustup/bin", "/usr/local/opt/rustup/bin") +
                System.getenv("PATH").orEmpty().split(File.pathSeparator)
        val cargo = path.map { File(it, "cargo") }.firstOrNull { it.canExecute() }?.path ?: "cargo"
        val out = outputDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = crateDir.get().asFile
            environment("ANDROID_NDK_HOME", ndk.path)
            environment("PATH", path.joinToString(File.pathSeparator))
            commandLine(
                cargo,
                "ndk",
                "--target",
                abi.get(),
                "--platform",
                apiLevel.get().toString(),
                "--output-dir",
                out.path,
                "build",
                "--release",
                "--locked",
                "--lib",
            )
        }
    }
}
