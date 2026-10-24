package app.mauri.cucuto.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.backup.AutoBackupControl
import app.mauri.cucuto.backup.AutoBackupResult
import app.mauri.cucuto.backup.BackupGateway
import app.mauri.cucuto.backup.ExportOutcome
import app.mauri.cucuto.backup.ExportRequest
import app.mauri.cucuto.core.data.settings.AutoBackupState
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.data.settings.UiFeedbackSettings
import app.mauri.cucuto.core.data.settings.UiSoundLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Settings screen shows. */
data class SettingsState(
    /** The global switch ("Cucuto voices"). */
    val enabled: Boolean = true,
    val soundLevel: UiSoundLevel = UiSoundLevel.Quiet,
    val haptics: Boolean = true,
    val removeAnimations: Boolean = false,
    val autoBackup: AutoBackupState = AutoBackupState(),
    /** A manual export or an automatic backup is running. */
    val backupBusy: Boolean = false,
)

/** The result of the last backup action, shown once as a snackbar. */
sealed interface BackupMessage {
    data class Exported(
        val outcome: ExportOutcome.Written,
    ) : BackupMessage

    data class ExportFailed(
        val message: String,
    ) : BackupMessage

    data class AutoResult(
        val result: AutoBackupResult,
    ) : BackupMessage

    data object FolderFailed : BackupMessage
}

/** The manual export dialog's fields. Pure, so its rules are unit-tested. */
data class ExportForm(
    val includePacks: Boolean = false,
    val encrypt: Boolean = false,
    val passphrase: String = "",
    val confirmation: String = "",
    val includeCredentials: Boolean = false,
) {
    val passphraseTooShort: Boolean get() = encrypt && passphrase.length < MIN_PASSPHRASE
    val mismatch: Boolean get() = encrypt && confirmation.isNotEmpty() && confirmation != passphrase
    val canExport: Boolean get() = !encrypt || (!passphraseTooShort && passphrase == confirmation)

    /** The request to run, or null while the form is not valid. Credentials only travel encrypted. */
    fun toRequest(): ExportRequest? =
        if (!canExport) {
            null
        } else {
            ExportRequest(
                includePacks = includePacks,
                passphrase = if (encrypt) passphrase.toCharArray() else null,
                includeCredentials = encrypt && includeCredentials,
            )
        }

    override fun toString(): String = "ExportForm(includePacks=$includePacks, encrypt=$encrypt)"

    companion object {
        /** Long enough that Argon2id's cost means something; the user can still pick a memorable phrase. */
        const val MIN_PASSPHRASE = 8
    }
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val routing: RoutingSettings,
        private val feedback: UiFeedbackSettings,
        private val autoBackup: AutoBackupControl,
        private val gateway: BackupGateway,
    ) : ViewModel() {
        private val busy = MutableStateFlow(false)

        val state: StateFlow<SettingsState> =
            combine(
                combine(
                    routing.enabled,
                    feedback.soundLevel,
                    feedback.hapticsEnabled,
                    feedback.removeAnimations,
                    ::Feel,
                ),
                autoBackup.state,
                busy,
            ) { feel, auto, isBusy ->
                SettingsState(feel.enabled, feel.sound, feel.haptics, feel.removeAnimations, auto, isBusy)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

        private val _message = MutableStateFlow<BackupMessage?>(null)
        val message: StateFlow<BackupMessage?> = _message.asStateFlow()

        /** The export the user confirmed, waiting for the document picker. Holds the passphrase only until then. */
        private var pendingExport: ExportRequest? = null

        fun setEnabled(enabled: Boolean) = save { routing.setEnabled(enabled) }

        fun setSoundLevel(level: UiSoundLevel) = save { feedback.setSoundLevel(level) }

        fun setHaptics(enabled: Boolean) = save { feedback.setHapticsEnabled(enabled) }

        fun setRemoveAnimations(remove: Boolean) = save { feedback.setRemoveAnimations(remove) }

        fun suggestedFileName(): String = gateway.suggestedFileName()

        /** Remembers the confirmed export form; returns false (nothing to pick) when the form is not valid. */
        fun prepareExport(form: ExportForm): Boolean {
            clearPending()
            pendingExport = form.toRequest()
            return pendingExport != null
        }

        /** The user picked where to save ([uri]), or cancelled the picker (null). */
        fun exportTo(uri: String?) {
            val request = pendingExport
            pendingExport = null
            if (uri == null || request == null) {
                request?.passphrase?.fill('\u0000')
                return
            }
            runBusy {
                try {
                    _message.value =
                        when (val outcome = gateway.export(uri, request)) {
                            is ExportOutcome.Written -> BackupMessage.Exported(outcome)
                            is ExportOutcome.Failed -> BackupMessage.ExportFailed(outcome.message)
                        }
                } finally {
                    request.passphrase?.fill('\u0000')
                }
            }
        }

        /** The user picked an automatic backup folder ([treeUri]), or cancelled (null). */
        fun chooseFolder(treeUri: String?) {
            if (treeUri == null) return
            save(onError = { BackupMessage.FolderFailed }) { autoBackup.choose(treeUri) }
        }

        fun turnOffAutoBackup() = save { autoBackup.turnOff() }

        fun setAutoIncludePacks(include: Boolean) = save { autoBackup.setIncludePacks(include) }

        fun backUpNow() = runBusy { _message.value = BackupMessage.AutoResult(autoBackup.backUpNow()) }

        fun messageShown() {
            _message.value = null
        }

        private fun clearPending() {
            pendingExport?.passphrase?.fill('\u0000')
            pendingExport = null
        }

        private fun runBusy(block: suspend () -> Unit) {
            if (busy.value) return
            busy.value = true
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = BackupMessage.ExportFailed(e.message ?: e.javaClass.simpleName)
                } finally {
                    busy.value = false
                }
            }
        }

        private fun save(
            onError: ((Exception) -> BackupMessage)? = null,
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (onError != null) _message.value = onError(e) else throw e
                }
            }
        }

        override fun onCleared() {
            clearPending()
        }

        private data class Feel(
            val enabled: Boolean,
            val sound: UiSoundLevel,
            val haptics: Boolean,
            val removeAnimations: Boolean,
        )
    }
