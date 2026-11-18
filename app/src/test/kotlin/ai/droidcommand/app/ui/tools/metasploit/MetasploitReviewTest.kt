package ai.droidcommand.app.ui.tools.metasploit

import ai.droidcommand.metasploit.MetasploitModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetasploitReviewTest {
    private val full = MetasploitFormState(
        moduleType = MetasploitModuleType.AUXILIARY,
        modulePath = "auxiliary/scanner/portscan/tcp",
        targetHost = "10.0.0.5",
        targetPort = "443",
        payload = "",
        options = listOf("THREADS" to "4"),
        scopeFilePath = "/data/scope.yaml",
        targetsAuthorized = true,
    )

    @Test
    fun `a blank target is not nagged about and a single host is accepted`() {
        assertNull(MetasploitReview.targetProblem(""))
        assertNull(MetasploitReview.targetProblem("   "))
        assertNull(MetasploitReview.targetProblem("10.0.0.5"))
        assertNull(MetasploitReview.targetProblem("lab.example.com"))
    }

    @Test
    fun `ranges, subnets, lists and wildcards are refused with a reason`() {
        for (bad in listOf("10.0.0.0/24", "10.0.0.1-20", "10.0.0.1, 10.0.0.2", "10.0.*.*", "0.0.0.0", "10.0.0.1 10.0.0.2")) {
            assertNotNull(MetasploitReview.targetProblem(bad), "expected a refusal for '$bad'")
        }
    }

    @Test
    fun `the scan runs only with a target, a scope file and the authorization ticked, and not while running`() {
        assertTrue(MetasploitReview.canRunScan(full, running = false))
        assertFalse(MetasploitReview.canRunScan(full, running = true))
        assertFalse(MetasploitReview.canRunScan(full.copy(targetHost = ""), running = false))
        assertFalse(MetasploitReview.canRunScan(full.copy(scopeFilePath = ""), running = false))
        assertFalse(MetasploitReview.canRunScan(full.copy(targetsAuthorized = false), running = false))
    }

    @Test
    fun `a module runs only with a target and a module, and not while running`() {
        assertTrue(MetasploitReview.canRunModule(full, running = false))
        assertFalse(MetasploitReview.canRunModule(full, running = true))
        assertFalse(MetasploitReview.canRunModule(full.copy(targetHost = ""), running = false))
        assertFalse(MetasploitReview.canRunModule(full.copy(modulePath = ""), running = false))
    }

    @Test
    fun `a module that is not authorized for scanning still does not depend on the scan boxes`() {
        val module = full.copy(scopeFilePath = "", targetsAuthorized = false)
        assertTrue(MetasploitReview.canRunModule(module, running = false))
    }

    @Test
    fun `missing lists name what is left, in the order to do it`() {
        val empty = MetasploitFormState()
        assertEquals(listOf("a target host", "a scope file", "the authorization box ticked"), MetasploitReview.scanMissing(empty))
        assertEquals(listOf("a target host", "a module"), MetasploitReview.moduleMissing(empty))
        assertEquals(emptyList(), MetasploitReview.scanMissing(full))
        assertEquals(emptyList(), MetasploitReview.moduleMissing(full))
    }

    @Test
    fun `the hint is absent when nothing is missing`() {
        assertNull(MetasploitReview.missingHint(emptyList()))
        assertEquals("Still needed: a target host, a module.", MetasploitReview.missingHint(listOf("a target host", "a module")))
    }

    @Test
    fun `the module summary shows host and port together and only the parts that were filled in`() {
        val lines = MetasploitReview.moduleSummary(full).toMap()
        assertEquals("auxiliary", lines["Type"])
        assertEquals("10.0.0.5:443", lines["Target"])
        assertEquals("THREADS=4", lines["Options"])
        assertFalse(lines.containsKey("Payload"))
        val withPayload = MetasploitReview.moduleSummary(full.copy(payload = "payload/generic/shell_reverse_tcp", targetPort = "")).toMap()
        assertEquals("10.0.0.5", withPayload["Target"])
        assertEquals("payload/generic/shell_reverse_tcp", withPayload["Payload"])
    }

    @Test
    fun `the summary says plainly what is not set yet`() {
        val lines = MetasploitReview.moduleSummary(MetasploitFormState()).toMap()
        assertEquals("not set", lines["Target"])
        assertEquals("not chosen", lines["Module"])
        val scan = MetasploitReview.scanSummary(MetasploitFormState()).toMap()
        assertEquals("not confirmed", scan["Authorization"])
        assertEquals("not set", scan["Scope file"])
    }

    @Test
    fun `the scan summary shows the authorization as confirmed only when it was ticked`() {
        assertEquals("confirmed by you", MetasploitReview.scanSummary(full).toMap()["Authorization"])
    }

    @Test
    fun `every suggested module and payload has a path and a description`() {
        for (item in MetasploitReview.COMMON_MODULES + MetasploitReview.COMMON_PAYLOADS) {
            assertTrue(item.path.contains('/'))
            assertTrue(item.what.isNotBlank())
        }
        assertTrue(MetasploitReview.COMMON_PAYLOADS.all { it.path.startsWith("payload/") })
    }
}
