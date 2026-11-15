package ai.droidcommand.shizuku.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks DCA's copy of the contract against bridge-contract-v1.json, the file ShizukuV's tests also check. */
class BridgeContractFixtureTest {
    private val text = javaClass.getResource("/bridge-contract-v1.json")!!.readText()

    private fun str(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)!!.groupValues[1]
    private fun list(key: String) = Regex("\"$key\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]
        .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }

    @Test fun identityAndVersion() {
        assertEquals(BridgeContract.APPLICATION_ID, str("application_id"))
        assertEquals(BridgeContract.PROVIDER_AUTHORITY_SUFFIX, str("provider_authority_suffix"))
        assertEquals(BridgeContract.CLIENT_SUPPORT_META_DATA, str("client_support_meta_data"))
        assertEquals(BridgeContract.API_VERSION, Regex("\"bridge_api_version\"\\s*:\\s*(\\d+)").find(text)!!.groupValues[1].toInt())
    }

    @Test fun operationsAndCapabilities() {
        val block = Regex("\"operations\"\\s*:\\s*\\{(.*?)}", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]
        val fromFixture = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(fromFixture, BridgeOperation.entries.associate { it.id to it.capability.id })
    }

    @Test fun errorCodesAndBackends() {
        assertEquals(list("error_codes"), BridgeError.entries.map { it.name })
        assertEquals(list("backend_requirements"), BridgeBackend.entries.map { it.id })
    }
}
