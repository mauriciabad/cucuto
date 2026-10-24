package app.mauri.cucuto.core.drive

import android.content.IntentSender

/** The only Drive scope Cucuto asks for (ADR 0040): files the app created or the user picked, nothing else. */
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/** A short-lived OAuth access token (about an hour). Never logged and never stored on disk. */
class DriveToken(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is DriveToken && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "DriveToken(██)"
}

/** What asking for Drive access gave. */
sealed interface DriveAuthorization {
    data class Granted(
        val token: DriveToken,
    ) : DriveAuthorization

    /** The user has to agree first: the UI launches [consent] and reports the result to the authorizer. */
    class NeedsConsent(
        val consent: IntentSender,
    ) : DriveAuthorization

    /** No way to reach Drive on this build or phone, for example no OAuth client or no Google Play services. */
    data class Unavailable(
        val reason: Reason,
    ) : DriveAuthorization

    enum class Reason {
        /** This build has no OAuth client for Cucuto (#163: the owner creates it). */
        NOT_CONFIGURED,

        /** Google Play services is missing or too old (common on rooted phones with microG or none). */
        NO_PLAY_SERVICES,

        /** The user said no or removed access. */
        DENIED,
    }
}

/**
 * Gets Drive access tokens for [DRIVE_FILE_SCOPE] (ADR 0040).
 *
 * The real implementation uses Google Identity Services' `AuthorizationClient`
 * (developer.android.com/identity/authorization): Google Play services keeps the user's grant, so Cucuto never holds
 * a refresh token or a password. Access tokens live in memory only. Until the owner creates the OAuth client,
 * [NotConfiguredDriveAuthorizer] is used and every Drive feature stays hidden.
 */
interface DriveAuthorizer {
    /**
     * A token without any UI when the user already agreed, else [DriveAuthorization.NeedsConsent] (only shown when the
     * user acts; automatic backups record a failure instead).
     */
    suspend fun authorize(): DriveAuthorization

    /** Forgets [token] after Drive rejected it (401), so the next [authorize] fetches a fresh one. */
    suspend fun invalidate(token: DriveToken)
}

/** The authorizer of builds without an OAuth client: Drive is never offered. */
object NotConfiguredDriveAuthorizer : DriveAuthorizer {
    override suspend fun authorize(): DriveAuthorization =
        DriveAuthorization.Unavailable(DriveAuthorization.Reason.NOT_CONFIGURED)

    override suspend fun invalidate(token: DriveToken) = Unit
}
