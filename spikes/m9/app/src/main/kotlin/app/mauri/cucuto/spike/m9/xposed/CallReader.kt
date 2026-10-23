package app.mauri.cucuto.spike.m9.xposed

import android.net.Uri
import android.telecom.PhoneAccountHandle
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Fail-soft reflective reader for Telecom's `com.android.server.telecom.Call`.
 *
 * Every read returns null on any failure. Names are from the device's own `service-telecom.jar`
 * (`CP3A.261005.005`); see docs/m9-report.md §2.
 *
 * The ringtone is read from the `mCallerInfo.contactRingtoneUri` field, not through
 * `Call.getRingtone()`, because the spike hooks that getter and must not observe its own swap.
 */
internal class CallReader {

    private val methods = ConcurrentHashMap<String, Method?>()
    private val fields = ConcurrentHashMap<String, Field?>()

    fun id(call: Any): String? = invoke(call, "getId") as? String
    fun state(call: Any): Int? = invoke(call, "getState") as? Int
    fun isIncoming(call: Any): Boolean? = invoke(call, "isIncoming") as? Boolean
    fun isSelfManaged(call: Any): Boolean? = invoke(call, "isSelfManaged") as? Boolean
    fun isTransactional(call: Any): Boolean? = invoke(call, "isTransactionalCall") as? Boolean
    fun handle(call: Any): Uri? = invoke(call, "getHandle") as? Uri
    fun hasDisplayName(call: Any): Boolean = !(invoke(call, "getCallerDisplayName") as? String).isNullOrEmpty()

    /** Package of the ConnectionService that owns the call: the SIM's telephony stack, or `com.whatsapp`. */
    fun accountPackage(call: Any): String? =
        (invoke(call, "getTargetPhoneAccount") as? PhoneAccountHandle)?.componentName?.packageName

    /** `CallerInfo` for this call; null until the contact lookup finished. */
    private fun callerInfo(call: Any): Any? = field(call, "mCallerInfo")

    fun contactExists(call: Any): Boolean? = callerInfo(call)?.let { field(it, "contactExists") as? Boolean }

    /** `CUSTOM_RINGTONE` of the matched contact, exactly as Telecom read it (null: none, EMPTY: silent). */
    fun customRingtone(call: Any): Uri? = callerInfo(call)?.let { field(it, "contactRingtoneUri") as? Uri }

    fun describe(call: Any): String = buildString {
        append("call=").append(Redact.token(id(call)))
        append(" state=").append(state(call))
        append(" incoming=").append(isIncoming(call))
        append(" selfManaged=").append(isSelfManaged(call))
        append(" transactional=").append(isTransactional(call))
        append(" account=").append(accountPackage(call))
        append(" handle=").append(Redact.handle(handle(call)))
        append(" displayName=").append(hasDisplayName(call))
        append(" contact=").append(contactExists(call))
        append(" customRingtone=").append(Redact.uri(customRingtone(call)))
    }

    private fun invoke(target: Any, name: String): Any? = try {
        methods.computeIfAbsent("${target.javaClass.name}#$name") {
            findMethod(target.javaClass, name)?.apply { isAccessible = true }
        }?.invoke(target)
    } catch (_: Throwable) {
        null
    }

    private fun field(target: Any, name: String): Any? = try {
        fields.computeIfAbsent("${target.javaClass.name}.$name") {
            findField(target.javaClass, name)?.apply { isAccessible = true }
        }?.get(target)
    } catch (_: Throwable) {
        null
    }

    private fun findMethod(start: Class<*>, name: String): Method? {
        var c: Class<*>? = start
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { return it }
            c = c.superclass
        }
        return null
    }

    private fun findField(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let { return it }
            c = c.superclass
        }
        return null
    }
}
