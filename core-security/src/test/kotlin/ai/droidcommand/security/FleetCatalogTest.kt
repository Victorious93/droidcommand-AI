package ai.droidcommand.security

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetCatalogTest {
    @Test
    fun `the room fleet is the thirteen members in wiring order`() {
        assertEquals(
            listOf(
                "pentestswarm",
                "rootforge",
                "victorsuite",
                "edl-commander",
                "kyc-auditlab",
                "file-manager",
                "keyboard",
                "mesh",
                "browser",
                "victorsms",
                "victorspoof",
                "victorious-flash",
                "victorlaunch",
            ),
            KnownFleet.ALL.map { it.id },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), KnownFleet.ALL.map { it.wiringOrder })
    }

    @Test
    fun `none of the thirteen is LIVE and the static checker never reports AVAILABLE`() {
        val checker = StaticFleetHealthChecker()
        for (member in KnownFleet.ALL) {
            assertNotEquals(FleetHonestyStatus.LIVE, member.status, member.id)
            val meta = checker.verify(member.capabilityId, member.providerId)
            assertNotEquals(CapabilityState.AVAILABLE, meta.state, member.id)
            assertEquals(member.capabilityId, meta.id)
            assertEquals(member.refusalMessage, meta.lastError)
        }
    }

    @Test
    fun `latest consensus classes and DependencyGuard rules hold`() {
        assertEquals(FleetIntegrationClass.BOUND_COMPANION, KnownFleet.PENTEST_SWARM.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.PENTEST_SWARM.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.ROOTFORGE.integrationClass)
        assertEquals(FleetIntegrationClass.TERMUX_IPC, KnownFleet.VICTORSUITE.integrationClass)
        assertEquals(FleetIntegrationClass.BOUND_COMPANION, KnownFleet.EDL_COMMANDER.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.EDL_COMMANDER.status)
        assertEquals(FleetIntegrationClass.HOST_PROCESS_TOOL, KnownFleet.KYC_AUDITLAB.integrationClass)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.FILE_MANAGER.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.FILE_MANAGER.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.KEYBOARD.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.KEYBOARD.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.MESH.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.MESH.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.BROWSER.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.BROWSER.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.VICTOR_SMS.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.VICTOR_SMS.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.VICTOR_SPOOF.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.VICTOR_SPOOF.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.VICTORIOUS_FLASH.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.VICTORIOUS_FLASH.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.VICTOR_LAUNCH.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.VICTOR_LAUNCH.status)

        assertTrue(KnownFleet.PENTEST_SWARM.usesAidl)
        assertTrue(KnownFleet.PENTEST_SWARM.usesDependencyGuard)
        assertTrue(KnownFleet.EDL_COMMANDER.usesAidl)
        assertTrue(KnownFleet.EDL_COMMANDER.usesDependencyGuard)
        for (member in listOf(
            KnownFleet.ROOTFORGE,
            KnownFleet.VICTORSUITE,
            KnownFleet.KYC_AUDITLAB,
            KnownFleet.FILE_MANAGER,
            KnownFleet.KEYBOARD,
            KnownFleet.MESH,
            KnownFleet.BROWSER,
            KnownFleet.VICTOR_SMS,
            KnownFleet.VICTOR_SPOOF,
            KnownFleet.VICTORIOUS_FLASH,
            KnownFleet.VICTOR_LAUNCH,
        )) {
            assertFalse(member.usesAidl, member.id)
            assertFalse(member.usesDependencyGuard, member.id)
        }
    }

    @Test
    fun `descriptor construction rejects mixed AIDL flags`() {
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.KYC_AUDITLAB.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.ROOTFORGE.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.FILE_MANAGER.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.KEYBOARD.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.MESH.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.MESH.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.BROWSER.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.BROWSER.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTOR_SMS.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTOR_SMS.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTOR_SPOOF.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTOR_SPOOF.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTORIOUS_FLASH.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTORIOUS_FLASH.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTOR_LAUNCH.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTOR_LAUNCH.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.EDL_COMMANDER.copy(usesAidl = false, usesDependencyGuard = false)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.PENTEST_SWARM.copy(usesDependencyGuard = false)
        }
    }

    @Test
    fun `capability states stay honest`() {
        val checker = StaticFleetHealthChecker()
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.PENTEST_SWARM.capabilityId, "pentestswarm").state)
        assertEquals(CapabilityState.REQUIRES_CONFIGURATION, checker.verify(KnownFleet.ROOTFORGE.capabilityId, "rootforge").state)
        assertEquals(CapabilityState.REQUIRES_TERMUX, checker.verify(KnownFleet.VICTORSUITE.capabilityId, "termux").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.EDL_COMMANDER.capabilityId, "edl-commander").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.KYC_AUDITLAB.capabilityId, "kyc-auditlab").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.FILE_MANAGER.capabilityId, "file-manager").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.KEYBOARD.capabilityId, "keyboard").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.MESH.capabilityId, "mesh").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.BROWSER.capabilityId, "browser").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.VICTOR_SMS.capabilityId, "victorsms").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.VICTOR_SPOOF.capabilityId, "victorspoof").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.VICTORIOUS_FLASH.capabilityId, "victorious-flash").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.VICTOR_LAUNCH.capabilityId, "victorlaunch").state)
    }

    @Test
    fun `registerCapabilities fills the existing CapabilityRegistry`() {
        val registry = InMemoryCapabilityRegistry(StaticFleetHealthChecker())
        KnownFleet.registerCapabilities(registry, now = 1_000L)
        assertEquals(13, registry.listCapabilities().size)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, registry.getCapability(KnownFleet.PENTEST_SWARM.capabilityId)?.state)
        val reverified = registry.reverify(KnownFleet.KYC_AUDITLAB.capabilityId)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, reverified.state)
        assertFailsWith<UnknownCapabilityException> {
            StaticFleetHealthChecker().verify(CapabilityId("fleet.unknown"), "nope")
        }
    }

    @Test
    fun `planned stubs are Pentest EDL KYC File Manager Keyboard Mesh Browser VictorSMS VictorSpoof Flash and VictorLaunch and never succeed`() {
        val tools = KnownFleet.plannedStubs()
        assertEquals(
            listOf(
                "pentest_swarm_status",
                "edl_commander_status",
                "kyc_auditlab_status",
                "filemanager_status",
                "keyboard_status",
                "mesh_status",
                "browser_status",
                "victorsms_status",
                "victorspoof_status",
                "victorious_flash_status",
                "victorlaunch_status",
            ),
            tools.map { it.spec.name },
        )
        for (tool in tools) {
            val result = tool.execute(emptyMap())
            assertTrue(result is ToolResult.Failure, tool.spec.name)
        }
        val edl = tools.first { it.spec.name == "edl_commander_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(edl.reason.contains("never been bound"))
        assertTrue(edl.reason.contains("does not bind, flash"))
        val kyc = tools.first { it.spec.name == "kyc_auditlab_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(kyc.reason.contains("/scope admin"))
        assertTrue(kyc.reason.contains("[provisional/pending-stable-cert]"))
        assertTrue(kyc.reason.contains("remoteAllowed:false"))
        val pentest = tools.first { it.spec.name == "pentest_swarm_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(pentest.reason.contains("awaiting the base companion repo"))
        val fileManager = tools.first { it.spec.name == "filemanager_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(fileManager.reason.contains("does not bind Messenger"))
        assertTrue(fileManager.reason.contains("ai.victorious.filemanager.action.BRIDGE"))
        val keyboard = tools.first { it.spec.name == "keyboard_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(keyboard.reason.contains("does not bind Messenger"))
        assertTrue(keyboard.reason.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(keyboard.reason.contains("Never forwards typed text"))
        val mesh = tools.first { it.spec.name == "mesh_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(mesh.reason.contains("does not bind Messenger"))
        assertTrue(mesh.reason.contains("com.victorious.mesh.action.BRIDGE"))
        assertTrue(mesh.reason.contains("mesh.capabilities.get"))
        val browser = tools.first { it.spec.name == "browser_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(browser.reason.contains("does not bind Messenger"))
        assertTrue(browser.reason.contains("com.victorious.browser.action.BRIDGE"))
        assertTrue(browser.reason.contains("browser.capabilities.get"))
        assertTrue(browser.reason.contains("browser.proxy.status"))
        assertTrue(browser.reason.contains("Message.sendingUid"))
        val victorSms = tools.first { it.spec.name == "victorsms_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(victorSms.reason.contains("does not bind Messenger"))
        assertTrue(victorSms.reason.contains("com.victorious.sms.action.BRIDGE"))
        assertTrue(victorSms.reason.contains("sms.remote_command.forward"))
        assertTrue(victorSms.reason.contains("fail-open"))
        assertTrue(victorSms.reason.contains("[planned/pending-merge]"))
        assertTrue(victorSms.reason.contains("Isolated from"))
        val victorSpoof = tools.first { it.spec.name == "victorspoof_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(victorSpoof.reason.contains("does not bind Messenger"))
        assertTrue(victorSpoof.reason.contains("com.victorious.spoof.action.BRIDGE"))
        assertTrue(victorSpoof.reason.contains("fail-open"))
        assertTrue(victorSpoof.reason.contains("Isolated from"))
        val flash = tools.first { it.spec.name == "victorious_flash_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(flash.reason.contains("does not bind Messenger"))
        assertTrue(flash.reason.contains("com.victorious.flash.android.FLEET_BRIDGE"))
        assertTrue(flash.reason.contains("gadget.expose"))
        assertTrue(flash.reason.contains("ERASE"))
        assertTrue(flash.reason.contains("enforced"))
        assertTrue(
            flash.reason.contains(
                "2C:52:0F:7A:A4:BE:72:6D:EB:F9:18:3D:04:3D:56:C5:58:E5:67:F7:77:BD:10:BE:3C:98:86:38:1C:6A:F6:ED",
            ),
        )
        val victorLaunch = tools.first { it.spec.name == "victorlaunch_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(victorLaunch.reason.contains("does not bind Messenger"))
        assertTrue(victorLaunch.reason.contains("com.victorious.launch.action.BRIDGE"))
        assertTrue(victorLaunch.reason.contains("fail-open"))
        assertTrue(victorLaunch.reason.contains("[planned/pending-merge]"))
        assertTrue(victorLaunch.reason.contains("ACTION OPS OFF BY DEFAULT"))
        assertTrue(
            victorLaunch.reason.contains(
                "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA",
            ),
        )
    }

    @Test
    fun `planned stub refuses to wrap a member that is not PLANNED`() {
        assertFailsWith<IllegalArgumentException> {
            PlannedFleetTool(KnownFleet.ROOTFORGE)
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFleetTool(KnownFleet.VICTORSUITE)
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFleetTool(KnownFleet.KYC_AUDITLAB.copy(status = FleetHonestyStatus.LIVE))
        }
    }

    @Test
    fun `RootForge is not SSH-only and VictorSuite stays on Termux`() {
        assertTrue(KnownFleet.ROOTFORGE.transports.any { it.contains("ai.rootforge.os.action.BRIDGE") })
        assertTrue(KnownFleet.ROOTFORGE.transports.any { it.contains("localhost") })
        assertTrue(KnownFleet.ROOTFORGE.transports.any { it.contains("fallback") })
        assertTrue(KnownFleet.ROOTFORGE.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.ROOTFORGE.notes.contains("never shared signing key"))
        assertEquals("com.termux", KnownFleet.VICTORSUITE.packageOrBinary)
        assertTrue(KnownFleet.VICTORSUITE.notes.contains("PR #15"))
        assertNull(KnownFleet.KYC_AUDITLAB.repo)
        assertTrue(KnownFleet.KYC_AUDITLAB.packageOrBinary.contains("com.identityshield.companion"))
        assertTrue(KnownFleet.KYC_AUDITLAB.notes.contains("[provisional/pending-stable-cert]"))
        assertTrue(KnownFleet.KYC_AUDITLAB.notes.contains("fail-open"))
        assertTrue(KnownFleet.KYC_AUDITLAB.notes.contains("remoteAllowed:false"))
        assertTrue(KnownFleet.KYC_AUDITLAB.notes.contains("per-machine default debug.keystore"))
        assertTrue(KnownFleet.KYC_AUDITLAB.notes.contains("KYC PR #29"))
        assertTrue(
            KnownFleet.KYC_AUDITLAB.notes.contains(
                "6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D",
            ),
        )
        assertTrue(
            KnownFleet.KYC_AUDITLAB.notes.contains(
                "22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D",
            ),
        )
        assertEquals(listOf("com.identityshield.companion"), KnownFleet.KYC_AUDITLAB.androidPackages)
        assertTrue(KnownFleet.KYC_AUDITLAB.expectedSigningCertSha256.isEmpty())
        assertEquals("Victorious93/EDL-Commander", KnownFleet.EDL_COMMANDER.repo)
        assertEquals("Victorious93/File-Manager", KnownFleet.FILE_MANAGER.repo)
        assertTrue(KnownFleet.FILE_MANAGER.packageOrBinary.contains("ai.victorious.filemanager"))
        assertTrue(KnownFleet.FILE_MANAGER.transports.any { it.contains("ai.victorious.filemanager.action.BRIDGE") })
        assertTrue(KnownFleet.FILE_MANAGER.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.FILE_MANAGER.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.FILE_MANAGER.notes.contains("dca_required:false"))
        assertEquals("Victorious93/Victorious-Keyboard", KnownFleet.KEYBOARD.repo)
        assertTrue(KnownFleet.KEYBOARD.packageOrBinary.contains("com.victorious.keyboard"))
        assertTrue(KnownFleet.KEYBOARD.packageOrBinary.contains("com.victorious.keyboard.debug"))
        assertTrue(KnownFleet.KEYBOARD.packageOrBinary.contains("com.victorious.keyboard.bench"))
        assertTrue(KnownFleet.KEYBOARD.transports.any { it.contains("com.victorious.keyboard.action.BRIDGE") })
        assertTrue(KnownFleet.KEYBOARD.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.KEYBOARD.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.KEYBOARD.notes.contains("Never forward typed text"))
        assertTrue(KnownFleet.KEYBOARD.notes.contains("undecided by Don"))
        assertEquals("Victorious93/victorious-mesh", KnownFleet.MESH.repo)
        assertTrue(KnownFleet.MESH.packageOrBinary.contains("com.victorious.mesh"))
        assertFalse(KnownFleet.MESH.packageOrBinary.contains("com.victorious.mesh.debug"))
        assertTrue(KnownFleet.MESH.packageOrBinary.contains("no applicationIdSuffix"))
        assertTrue(KnownFleet.MESH.transports.any { it.contains("com.victorious.mesh.action.BRIDGE") })
        assertTrue(KnownFleet.MESH.transports.any { it.contains("^mesh-[0-9a-f]{32}$") })
        assertTrue(KnownFleet.MESH.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.MESH.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.MESH.notes.contains("own release key"))
        assertEquals(
            listOf("mesh_capabilities", "mesh_status", "mesh_up", "mesh_down", "mesh_get_ip"),
            KnownFleet.MESH.existingToolNames,
        )
        assertNull(KnownFleet.BROWSER.repo)
        assertTrue(KnownFleet.BROWSER.packageOrBinary.contains("com.victorious.browser"))
        assertFalse(KnownFleet.BROWSER.packageOrBinary.contains("com.victorious.browser.debug"))
        assertTrue(KnownFleet.BROWSER.transports.any { it.contains("com.victorious.browser.action.BRIDGE") })
        assertTrue(KnownFleet.BROWSER.transports.any { it.contains("^browser-[0-9a-f]{32}$") })
        assertTrue(KnownFleet.BROWSER.transports.any { it.contains("Message.sendingUid") })
        assertTrue(KnownFleet.BROWSER.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.BROWSER.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.BROWSER.notes.contains("own release key"))
        assertTrue(KnownFleet.BROWSER.notes.contains("KnownNodeSigningCatalog"))
        assertTrue(
            KnownFleet.BROWSER.refusalMessage.contains(
                "2D:5D:9E:8B:53:8E:78:51:3D:B5:41:F4:DF:FD:67:BE:27:FF:EC:3F:37:EB:1A:D4:8D:77:83:AE:59:27:C3:E1",
            ),
        )
        assertEquals(listOf("browser_status"), KnownFleet.BROWSER.existingToolNames)
        assertTrue(KnownFleet.BROWSER.id.matches(Regex("""^[a-z0-9][a-z0-9._-]*$""")))
        assertEquals(listOf("com.victorious.browser"), KnownFleet.BROWSER.androidPackages)
        assertNull(KnownFleet.VICTOR_SMS.repo)
        assertTrue(KnownFleet.VICTOR_SMS.packageOrBinary.contains("com.victorious.sms"))
        assertTrue(KnownFleet.VICTOR_SMS.packageOrBinary.contains("debug==release"))
        assertTrue(KnownFleet.VICTOR_SMS.transports.any { it.contains("com.victorious.sms.action.BRIDGE") })
        assertTrue(KnownFleet.VICTOR_SMS.transports.any { it.contains("sms.remote_command.forward") })
        assertTrue(KnownFleet.VICTOR_SMS.transports.any { it.contains("remote.command") })
        assertTrue(KnownFleet.VICTOR_SMS.transports.any { it.contains("^victorsms-[0-9a-f]{32}$") })
        assertTrue(KnownFleet.VICTOR_SMS.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.VICTOR_SMS.notes.contains("fail-open"))
        assertTrue(KnownFleet.VICTOR_SMS.notes.contains("[planned/pending-merge]"))
        assertTrue(KnownFleet.VICTOR_SMS.notes.contains("sms.remote_command.forward"))
        assertTrue(KnownFleet.VICTOR_SMS.notes.contains("Isolated from privileged/destructive"))
        assertTrue(
            KnownFleet.VICTOR_SMS.notes.contains(
                "BA:81:F5:C7:6E:2C:59:04:E8:A0:E5:0C:80:67:A2:A1:6C:23:14:86:2F:13:DA:7F:49:67:94:8A:53:E7:D1:7A",
            ),
        )
        assertTrue(KnownFleet.VICTOR_SMS.notes.contains("androiddebugkey"))
        assertTrue(KnownFleet.VICTOR_SMS.notes.contains("not enforced"))
        assertEquals(listOf("victorsms_status"), KnownFleet.VICTOR_SMS.existingToolNames)
        assertEquals(listOf("com.victorious.sms"), KnownFleet.VICTOR_SMS.androidPackages)
        assertTrue(KnownFleet.VICTOR_SMS.expectedSigningCertSha256.isEmpty())
        assertNull(KnownFleet.VICTOR_SPOOF.repo)
        assertTrue(KnownFleet.VICTOR_SPOOF.packageOrBinary.contains("com.victorious.spoof"))
        assertTrue(KnownFleet.VICTOR_SPOOF.transports.any { it.contains("com.victorious.spoof.action.BRIDGE") })
        assertTrue(KnownFleet.VICTOR_SPOOF.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.VICTOR_SPOOF.notes.contains("fail-open"))
        assertTrue(KnownFleet.VICTOR_SPOOF.notes.contains("isolated from privileged/destructive"))
        assertEquals(listOf("victorspoof_status"), KnownFleet.VICTOR_SPOOF.existingToolNames)
        assertEquals(listOf("com.victorious.spoof"), KnownFleet.VICTOR_SPOOF.androidPackages)
        assertTrue(KnownFleet.VICTOR_SPOOF.expectedSigningCertSha256.isEmpty())
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256),
            KnownFleet.BROWSER.expectedSigningCertSha256,
        )
        assertEquals("Victorious93/victorious-flash", KnownFleet.VICTORIOUS_FLASH.repo)
        assertEquals("com.victorious.flash.android", KnownFleet.VICTORIOUS_FLASH.packageOrBinary)
        assertTrue(KnownFleet.VICTORIOUS_FLASH.transports.any { it.contains("com.victorious.flash.android.FLEET_BRIDGE") })
        assertTrue(KnownFleet.VICTORIOUS_FLASH.transports.any { it.contains("^vfa-[0-9a-f]{32}$") })
        assertTrue(KnownFleet.VICTORIOUS_FLASH.transports.any { it.contains("gadget.expose") })
        assertTrue(KnownFleet.VICTORIOUS_FLASH.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.VICTORIOUS_FLASH.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.VICTORIOUS_FLASH.notes.contains("gadget.expose is DESTRUCTIVE"))
        assertTrue(KnownFleet.VICTORIOUS_FLASH.notes.contains("on-phone ERASE"))
        assertTrue(KnownFleet.VICTORIOUS_FLASH.notes.contains("enforced"))
        assertTrue(KnownFleet.VICTORIOUS_FLASH.notes.contains("KnownNodeSigningCatalog"))
        assertTrue(
            KnownFleet.VICTORIOUS_FLASH.refusalMessage.contains(
                "2C:52:0F:7A:A4:BE:72:6D:EB:F9:18:3D:04:3D:56:C5:58:E5:67:F7:77:BD:10:BE:3C:98:86:38:1C:6A:F6:ED",
            ),
        )
        assertEquals(listOf("victorious_flash_status"), KnownFleet.VICTORIOUS_FLASH.existingToolNames)
        assertEquals(listOf("com.victorious.flash.android"), KnownFleet.VICTORIOUS_FLASH.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256),
            KnownFleet.VICTORIOUS_FLASH.expectedSigningCertSha256,
        )
        assertEquals(RiskTier.DESTRUCTIVE, KnownFleet.VICTORIOUS_FLASH.riskTier)
        assertEquals(12, KnownFleet.VICTORIOUS_FLASH.wiringOrder)
        assertNull(KnownFleet.VICTOR_LAUNCH.repo)
        assertEquals("com.victorious.launch", KnownFleet.VICTOR_LAUNCH.packageOrBinary)
        assertTrue(KnownFleet.VICTOR_LAUNCH.transports.any { it.contains("com.victorious.launch.action.BRIDGE") })
        assertTrue(KnownFleet.VICTOR_LAUNCH.transports.any { it.contains("^victorlaunch-[0-9a-f]{32}$") })
        assertTrue(KnownFleet.VICTOR_LAUNCH.transports.any { it.contains("config.export/import") })
        assertTrue(KnownFleet.VICTOR_LAUNCH.transports.any { it.contains("ACTION OPS OFF BY DEFAULT") })
        assertTrue(KnownFleet.VICTOR_LAUNCH.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.VICTOR_LAUNCH.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.VICTOR_LAUNCH.notes.contains("fail-open"))
        assertTrue(KnownFleet.VICTOR_LAUNCH.notes.contains("[planned/pending-merge]"))
        assertTrue(KnownFleet.VICTOR_LAUNCH.notes.contains("status-tray consumer"))
        assertTrue(KnownFleet.VICTOR_LAUNCH.notes.contains("KnownNodeSigningCatalog"))
        assertTrue(KnownFleet.VICTOR_LAUNCH.notes.contains("not enforced"))
        assertTrue(
            KnownFleet.VICTOR_LAUNCH.notes.contains(
                "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA",
            ),
        )
        assertTrue(
            KnownFleet.VICTOR_LAUNCH.refusalMessage.contains(
                "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA",
            ),
        )
        assertEquals(listOf("victorlaunch_status"), KnownFleet.VICTOR_LAUNCH.existingToolNames)
        assertEquals(listOf("com.victorious.launch"), KnownFleet.VICTOR_LAUNCH.androidPackages)
        assertTrue(KnownFleet.VICTOR_LAUNCH.expectedSigningCertSha256.isEmpty())
        assertEquals(RiskTier.READ_ONLY, KnownFleet.VICTOR_LAUNCH.riskTier)
        assertEquals(13, KnownFleet.VICTOR_LAUNCH.wiringOrder)
    }

    @Test
    fun `listing names every member wiring order and the companion rule`() {
        val text = KnownFleet.formatListing()
        for (id in listOf(
            "rootforge",
            "victorsuite",
            "kyc-auditlab",
            "edl-commander",
            "pentestswarm",
            "file-manager",
            "keyboard",
            "mesh",
            "browser",
            "victorsms",
            "victorspoof",
            "victorious-flash",
            "victorlaunch",
        )) {
            assertTrue(text.contains(id), id)
        }
        assertTrue(text.contains("13 members"))
        assertFalse(text.contains("12 members"))
        assertFalse(text.contains("victorious-flash / BakerPi are not catalogued"))
        assertTrue(text.contains("None is LIVE"))
        assertTrue(text.contains("Pentest (when repo exists)"))
        assertTrue(text.contains("RootForge/File Manager/Keyboard/Mesh/Browser/VictorSMS/VictorSpoof/Flash/VictorLaunch/KYC companion Messenger is not that"))
        assertTrue(text.contains("ai.victorious.filemanager.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.mesh.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.browser.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.sms.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.spoof.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.flash.android.FLEET_BRIDGE"))
        assertTrue(text.contains("com.victorious.flash.android"))
        assertTrue(text.contains("com.victorious.launch.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.launch"))
        assertTrue(text.contains("HackerAI"))
        assertTrue(text.contains("signingCertSha256: [Unknown]"))
        assertTrue(text.contains("Controller-side node signature check"))
        assertTrue(text.contains("com.victorious.mesh"))
        assertTrue(text.contains("com.victorious.browser"))
        assertTrue(text.contains("ai.rootforge.os.debug"))
        assertTrue(text.contains("[unstable/CI-secrets]"))
        assertTrue(text.contains("[planned/unverified]"))
        assertTrue(text.contains("[planned/pending-merge]"))
        assertTrue(text.contains("[provisional/pending-stable-cert]"))
        assertTrue(text.contains("com.identityshield.companion.action.BRIDGE"))
        assertTrue(text.contains("remoteAllowed:false"))
        assertTrue(text.contains("KYC PR #29"))
        assertTrue(text.contains("22:4F:"))
    }

    @Test
    fun `fleet members expose android packages and the catalogued expected certs`() {
        assertEquals(listOf("ai.pentestswarm.companion"), KnownFleet.PENTEST_SWARM.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.PENTEST_SWARM_DEBUG_SHA256),
            KnownFleet.PENTEST_SWARM.expectedSigningCertSha256,
        )
        assertEquals(listOf(EdlCompanionIds.PACKAGE), KnownFleet.EDL_COMMANDER.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256),
            KnownFleet.EDL_COMMANDER.expectedSigningCertSha256,
        )
        assertEquals(listOf("ai.rootforge.os.debug"), KnownFleet.ROOTFORGE.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.ROOTFORGE_DEBUG_SHA256),
            KnownFleet.ROOTFORGE.expectedSigningCertSha256,
        )
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
            ),
            KnownFleet.VICTORSUITE.expectedSigningCertSha256,
        )
        assertEquals(
            listOf(KnownNodeSigningCatalog.KEYBOARD_DEBUG_SHA256),
            KnownFleet.KEYBOARD.expectedSigningCertSha256,
        )
        assertTrue(KnownFleet.FILE_MANAGER.expectedSigningCertSha256.isEmpty())
        assertEquals(listOf("com.victorious.mesh"), KnownFleet.MESH.androidPackages)
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORIOUS_MESH_RELEASE_SHA256,
            ),
            KnownFleet.MESH.expectedSigningCertSha256,
        )
        assertEquals(listOf("com.victorious.browser"), KnownFleet.BROWSER.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256),
            KnownFleet.BROWSER.expectedSigningCertSha256,
        )
        assertEquals(listOf("com.victorious.sms"), KnownFleet.VICTOR_SMS.androidPackages)
        assertTrue(KnownFleet.VICTOR_SMS.expectedSigningCertSha256.isEmpty())
        assertEquals(listOf("com.victorious.spoof"), KnownFleet.VICTOR_SPOOF.androidPackages)
        assertTrue(KnownFleet.VICTOR_SPOOF.expectedSigningCertSha256.isEmpty())
        assertEquals(listOf("com.victorious.flash.android"), KnownFleet.VICTORIOUS_FLASH.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256),
            KnownFleet.VICTORIOUS_FLASH.expectedSigningCertSha256,
        )
        assertEquals(listOf("com.victorious.launch"), KnownFleet.VICTOR_LAUNCH.androidPackages)
        assertTrue(KnownFleet.VICTOR_LAUNCH.expectedSigningCertSha256.isEmpty())
        val catalog = KnownNodeSigningCatalog.all()
        val meshCerts = catalog.first { it.packageName == "com.victorious.mesh" }.expectedCerts.map { it.canonical }
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORIOUS_MESH_RELEASE_SHA256,
            ),
            meshCerts,
        )
        assertTrue(catalog.any { it.packageName == "com.victorious.browser" && it.expectedCerts.isNotEmpty() })
        assertTrue(catalog.any { it.packageName == "com.victorious.flash.android" && it.expectedCerts.isNotEmpty() })
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256),
            catalog.first { it.packageName == "com.victorious.flash.android" }.expectedCerts.map { it.canonical },
        )
        assertTrue(
            catalog.first { it.packageName == "com.victorious.keyboard.bench" }.expectedCerts.isEmpty(),
        )
        assertTrue(
            catalog.first { it.packageName == "ai.victorious.filemanager" }.expectedCerts.isEmpty(),
        )
        assertTrue(
            catalog.first { it.packageName == "com.victorious.sms" }.expectedCerts.isEmpty(),
        )
        assertTrue(
            catalog.first { it.packageName == "com.victorious.spoof" }.expectedCerts.isEmpty(),
        )
        assertTrue(
            catalog.first { it.packageName == "com.victorious.launch" }.expectedCerts.isEmpty(),
        )
        assertTrue(KnownFleet.KYC_AUDITLAB.expectedSigningCertSha256.isEmpty())
        assertTrue(
            catalog.first { it.packageName == "com.identityshield.companion" }.expectedCerts.isEmpty(),
        )
    }
}
