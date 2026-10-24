package app.mauri.cucuto.xposed.integration

import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.UserHandle
import android.os.VibrationEffect
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import app.mauri.cucuto.xposed.hook.AlertHooks
import app.mauri.cucuto.xposed.route.Targets
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Stand-in for `com.android.server.notification.NotificationRecord`: the module reads it by reflection through
 * `getSbn()` and `getShortcutInfo()` only. Everything those return is a real framework object (Robolectric).
 */
@Suppress("unused") // Called by reflection, like the framework's own class.
internal class FakeNotificationRecord(
    private val sbn: StatusBarNotification,
    private val shortcutInfo: ShortcutInfo?,
) {
    fun getSbn(): StatusBarNotification = sbn

    fun getShortcutInfo(): ShortcutInfo? = shortcutInfo
}

/**
 * Stand-in for `NotificationAttentionHelper`: the two methods the module hooks, with the framework's signatures.
 * They record what the framework would have played.
 */
@Suppress("unused", "UNUSED_PARAMETER")
internal class FakeAttentionHelper {
    var playedSound: Uri? = null
    var playedVibration: VibrationEffect? = null

    fun playSound(
        record: FakeNotificationRecord,
        soundUri: Uri,
    ): Boolean {
        playedSound = soundUri
        return true
    }

    fun playVibration(
        record: FakeNotificationRecord,
        effect: VibrationEffect,
        delayVibForSound: Boolean,
    ): Boolean {
        playedVibration = effect
        return true
    }
}

/** What the framework ended up doing for one alert, and what the module reported. */
internal data class Alert(
    val sound: Uri?,
    val vibration: VibrationEffect?,
    /** `playVibration` returned without vibrating (a per-contact `Off`). */
    val vibrationSuppressed: Boolean,
)

/**
 * The edge of `system_server`: a libxposed [XposedInterface] that records hooks instead of installing them, a class
 * loader that serves [FakeAttentionHelper] and [FakeNotificationRecord] under the framework's names, and
 * [alert], which calls the hooked methods the way `NotificationAttentionHelper.buzzBeepBlinkLocked` does once it has
 * decided that a record alerts.
 */
internal class FakeFramework {
    private val hookers = HashMap<String, XposedInterface.Hooker>()
    val exceptionModes = HashMap<String, XposedInterface.ExceptionMode>()

    val classLoader: ClassLoader =
        object : ClassLoader(FakeFramework::class.java.classLoader) {
            override fun loadClass(
                name: String,
                resolve: Boolean,
            ): Class<*> =
                when (name) {
                    AlertHooks.ATTENTION_HELPER -> FakeAttentionHelper::class.java
                    AlertHooks.NOTIFICATION_RECORD -> FakeNotificationRecord::class.java
                    else -> super.loadClass(name, resolve)
                }
        }

    val xposed: XposedInterface =
        proxy(XposedInterface::class.java) { method, args ->
            when (method.name) {
                "hook" -> hookBuilder(args!![0] as Method)
                "getApiVersion" -> XposedInterface.API_102
                else -> throw UnsupportedOperationException("XposedInterface.${method.name}")
            }
        }

    val hooked: Set<String> get() = hookers.keys

    private fun hookBuilder(method: Method): XposedInterface.HookBuilder {
        lateinit var builder: XposedInterface.HookBuilder
        builder =
            proxy(XposedInterface.HookBuilder::class.java) { call, args ->
                when (call.name) {
                    "intercept" -> {
                        hookers[method.name] = args!![0] as XposedInterface.Hooker
                        proxy(XposedInterface.HookHandle::class.java) { _, _ -> null }
                    }

                    "setExceptionMode" -> {
                        exceptionModes[method.name] = args!![0] as XposedInterface.ExceptionMode
                        builder
                    }

                    else -> {
                        builder
                    }
                }
            }
        return builder
    }

    /**
     * One alerting record: `playSound` when the channel has a sound, then `playVibration` when it vibrates, both on
     * the same record, as `buzzBeepBlinkLocked` does.
     */
    fun alert(
        record: FakeNotificationRecord,
        sound: Uri? = WHATSAPP_TONE,
        vibration: VibrationEffect? = WHATSAPP_VIBRATION,
    ): Alert {
        val helper = FakeAttentionHelper()
        if (sound != null) call(helper, AlertHooks.PLAY_SOUND, listOf(record, sound))
        var suppressed = false
        if (vibration != null) {
            val vibrated = call(helper, AlertHooks.PLAY_VIBRATION, listOf(record, vibration, false))
            suppressed = vibrated == false && helper.playedVibration == null
        }
        return Alert(helper.playedSound, helper.playedVibration, suppressed)
    }

    private fun call(
        helper: FakeAttentionHelper,
        name: String,
        args: List<Any?>,
    ): Any? {
        val method = FakeAttentionHelper::class.java.declaredMethods.single { it.name == name }
        val hooker = hookers[name] ?: return method.invoke(helper, *args.toTypedArray())
        return hooker.intercept(Chain(helper, method, args))
    }

    /** The libxposed chain for one call: [proceed] runs the original method, with the arguments it is given. */
    private class Chain(
        private val helper: FakeAttentionHelper,
        private val method: Method,
        private val args: List<Any?>,
    ) : XposedInterface.Chain {
        override fun getExecutable(): Executable = method

        override fun getThisObject(): Any = helper

        override fun getArgs(): List<Any?> = args

        override fun getArg(index: Int): Any? = args[index]

        override fun proceed(): Any? = invoke(args.toTypedArray())

        override fun proceed(args: Array<out Any?>): Any? = invoke(args)

        override fun proceedWith(thisObject: Any): Any? = invoke(args.toTypedArray())

        override fun proceedWith(
            thisObject: Any,
            args: Array<out Any?>,
        ): Any? = invoke(args)

        private fun invoke(args: Array<out Any?>): Any? =
            try {
                method.invoke(helper, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    companion object {
        val WHATSAPP_TONE: Uri = Uri.parse("content://media/internal/audio/media/42")
        val WHATSAPP_VIBRATION: VibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 250, 250, 250), -1)

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(
            type: Class<T>,
            handler: (Method, Array<out Any?>?) -> Any?,
        ): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                when (method.name) {
                    "toString" -> type.simpleName
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    else -> handler(method, args)
                }
            } as T
    }
}

/** Notifications as WhatsApp, WhatsApp Business and Cucuto itself post them, read back through real framework code. */
internal object Notifications {
    const val WHATSAPP = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"
    private const val INDIVIDUAL_CHANNEL = "individual_chat_defaults_5"
    private const val GROUP_CHANNEL = "group_chat_defaults_6"
    private var nextId = 1

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A 1:1 chat: the shortcut ID is the person (`…@lid` or a phone JID). */
    fun oneToOne(
        key: String,
        name: String,
        pkg: String = WHATSAPP,
        channel: String = INDIVIDUAL_CHANNEL,
    ): FakeNotificationRecord {
        val me = Person.Builder().setName("Me").build()
        val them =
            Person
                .Builder()
                .setName(name)
                .setKey(key)
                .build()
        val notification =
            builder(pkg, channel, key, name)
                .setStyle(Notification.MessagingStyle(me).addMessage("text", 1L, them))
                .build()
        return record(pkg, notification, shortcut(key, name))
    }

    /**
     * WhatsApp's group summary, the record that alerts (M0 report): its shortcut ID is the group, and it carries no
     * message, so the module cannot tell who wrote.
     */
    fun groupSummary(
        groupKey: String,
        groupName: String,
        pkg: String = WHATSAPP,
    ): FakeNotificationRecord {
        val notification =
            builder(pkg, GROUP_CHANNEL, groupKey, groupName)
                .setGroup("group_key_messages")
                .setGroupSummary(true)
                .build()
        return record(pkg, notification, shortcut(groupKey, groupName))
    }

    /** A group message whose newest message names its sender (a MessagingStyle `Person` with a key). */
    fun groupMessage(
        groupKey: String,
        groupName: String,
        senderKey: String,
        senderName: String,
        pkg: String = WHATSAPP,
    ): FakeNotificationRecord {
        val me = Person.Builder().setName("Me").build()
        val sender =
            Person
                .Builder()
                .setName(senderName)
                .setKey(senderKey)
                .build()
        val notification =
            builder(pkg, GROUP_CHANNEL, groupKey, groupName)
                .setStyle(
                    Notification
                        .MessagingStyle(me)
                        .setConversationTitle(groupName)
                        .setGroupConversation(true)
                        .addMessage("text", 2L, sender),
                ).build()
        return record(pkg, notification, shortcut(groupKey, groupName))
    }

    /** The Diagnostics screen's test notification. */
    fun selfTest(): FakeNotificationRecord =
        record(
            Targets.SELF_PACKAGE,
            Notification
                .Builder(context, Targets.SELF_TEST_CHANNEL)
                .setContentTitle("Cucuto self-test")
                .build(),
            null,
        )

    /** "Simulate message" for the chat [key] of [pkg] (contact detail). */
    fun simulate(
        pkg: String,
        key: String,
    ): FakeNotificationRecord =
        record(
            Targets.SELF_PACKAGE,
            Notification
                .Builder(context, Targets.SIMULATE_CHANNEL)
                .setContentTitle("Simulated message")
                .addExtras(
                    android.os.Bundle().apply {
                        putString(Targets.EXTRA_SIMULATE_PACKAGE, pkg)
                        putString(Targets.EXTRA_SIMULATE_KEY, key)
                    },
                ).build(),
            null,
        )

    /** Some other app on the phone. */
    fun otherApp(pkg: String = "org.telegram.messenger"): FakeNotificationRecord =
        record(pkg, Notification.Builder(context, "chats").setShortcutId("123@lid").build(), null)

    private fun builder(
        pkg: String,
        channel: String,
        shortcutId: String,
        title: String,
    ): Notification.Builder =
        Notification
            .Builder(context, channel)
            .setShortcutId(shortcutId)
            .setContentTitle(title)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .also { check(pkg.isNotEmpty()) }

    private fun shortcut(
        id: String,
        label: String,
    ): ShortcutInfo =
        ShortcutInfo
            .Builder(context, id)
            .setShortLabel(label)
            .setLongLived(true)
            .build()

    private fun record(
        pkg: String,
        notification: Notification,
        shortcut: ShortcutInfo?,
    ): FakeNotificationRecord {
        val id = nextId++
        val sbn =
            StatusBarNotification(
                pkg,
                pkg,
                id,
                null,
                10_000 + id,
                0,
                0,
                notification,
                UserHandle.getUserHandleForUid(0),
                System.currentTimeMillis(),
            )
        return FakeNotificationRecord(sbn, shortcut)
    }
}
