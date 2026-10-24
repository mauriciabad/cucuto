package app.mauri.cucuto.sample

import app.mauri.cucuto.testing.ClassGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample chat must never route a real contact or reach the module (ADR 0075): its code refers to nothing that
 * writes chats, contacts, assignments, phone ringtones or the routing table, posts notifications or talks to the
 * module. Checked on the compiled classes of the `sample` package, so a later change that wires one in fails here.
 */
class SampleChatIsolationTest {
    private val graph = ClassGraph()

    @Test
    fun theSampleChatTouchesNothingThatReachesTheModuleOrABackup() {
        val classes = graph.classesBeside(VIEW_MODEL).filter { it.startsWith(PACKAGE) }
        assertTrue("sample classes not found: $classes", classes.any { it == VIEW_MODEL })
        val problems =
            classes.flatMap { name ->
                val refs = graph.refs(name)?.classes.orEmpty()
                refs.filter { ref -> FORBIDDEN.any { ref == it || ref.startsWith("$it\$") } }.map { "$name uses $it" }
            }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    private companion object {
        const val PACKAGE = "app/mauri/cucuto/sample/"
        const val VIEW_MODEL = "${PACKAGE}SampleChatViewModel"

        val FORBIDDEN =
            listOf(
                "app/mauri/cucuto/core/data/chats/ChatDirectory",
                "app/mauri/cucuto/core/data/db/ContactDao",
                "app/mauri/cucuto/core/data/db/AssignmentDao",
                "app/mauri/cucuto/core/data/db/PhoneRingtoneDao",
                "app/mauri/cucuto/core/data/repository/ContactRepository",
                "app/mauri/cucuto/core/data/repository/AssignmentRepository",
                "app/mauri/cucuto/core/data/repository/SeenSenderRepository",
                "app/mauri/cucuto/core/data/routing/RoutingPublisher",
                "app/mauri/cucuto/core/data/routing/RoutingSink",
                "app/mauri/cucuto/core/data/routing/RoutingTableSource",
                "app/mauri/cucuto/core/ringtone/PhoneRingtones",
                "app/mauri/cucuto/contact/MessageSimulator",
                "android/app/NotificationManager",
                "android/app/Notification",
                "android/provider/ContactsContract",
            )
    }
}
