package app.mauri.cucuto.core.repo

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.File
import java.net.InetAddress

/** spec/ at the repository root (set by the Gradle test task). */
val specDir: File = File(System.getProperty("cucuto.specDir") ?: error("cucuto.specDir is not set"))

val fixturesDir: File = File(specDir, "fixtures")

/**
 * A throwaway certificate valid for both loopback names, so tests run real TLS (the client is HTTPS only) and can
 * still tell two hosts apart: `localhost` and `127.0.0.1` are different origins.
 */
object TestTls {
    private val certificate: HeldCertificate =
        HeldCertificate
            .Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()

    val server: HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()

    val client: HandshakeCertificates =
        HandshakeCertificates
            .Builder()
            .addTrustedCertificate(
                certificate.certificate,
            ).build()
}

private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

/**
 * A started HTTPS [MockWebServer] on 127.0.0.1. It negotiates HTTP/2 like real hosts; [http1Only] forces HTTP/1.1,
 * where chunked transfer encoding exists.
 */
fun httpsServer(http1Only: Boolean = false): MockWebServer =
    MockWebServer().apply {
        useHttps(TestTls.server.sslSocketFactory())
        if (http1Only) protocols = listOf(Protocol.HTTP_1_1)
        start(loopback, 0)
    }

/** A started plain HTTP server, to prove the client never talks to it. */
fun plainServer(): MockWebServer = MockWebServer().apply { start(loopback, 0) }

/** [path] on [this] server, addressed by [host] (`localhost` or `127.0.0.1`). */
fun MockWebServer.at(
    path: String,
    host: String = "localhost",
): HttpUrl = url(path).newBuilder().host(host).build()

fun testClient(limits: RepoLimits = RepoLimits()): RepositoryClient =
    RepositoryClient(
        httpClient =
            RepositoryClient
                .defaultHttpClient()
                .newBuilder()
                .sslSocketFactory(TestTls.client.sslSocketFactory(), TestTls.client.trustManager)
                .build(),
        limits = limits,
    )

fun ok(body: String): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .body(body)
        .build()

fun ok(bytes: ByteArray): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .body(Buffer().write(bytes))
        .build()

fun redirect(
    location: String,
    code: Int = 302,
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .addHeader("Location", location)
        .build()

/** The golden minimal pack archive and its real size and hash. */
val minimalPack: File = File(fixturesDir, "valid/minimal-pack.soundpack.zip")

fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .toHex()

/**
 * The golden repository index with its first entry pointing at [downloadUrl] with [size] and [sha] (defaults: the real
 * minimal pack).
 */
fun indexJson(
    downloadUrl: String = "packs/app.mauri.fixtures.minimal-1.0.0.soundpack.zip",
    size: Long = minimalPack.length(),
    sha: String = sha256(minimalPack.readBytes()),
): String =
    File(fixturesDir, "valid/repository.json")
        .readText()
        .replace("\"packs/app.mauri.fixtures.minimal-1.0.0.soundpack.zip\"", "\"$downloadUrl\"")
        .replace("\"size\": 1834", "\"size\": $size")
        .replace("\"0000000000000000000000000000000000000000000000000000000000000000\"", "\"$sha\"")
