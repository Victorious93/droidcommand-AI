package ai.droidcommand.fleet.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetReleaseOptionsTest {
    private val repo = "Victorious93/demo"
    private val source = FleetAppSource("demo", "Demo", "pkg.demo", repo)
    private val abis = listOf("arm64-v8a")
    private fun asset(file: String, r: String = repo) = ReleaseAsset(file, "https://github.com/$r/releases/download/v/$file", 1, null)
    private fun release(tag: String, vararg files: String, pre: Boolean = false, draft: Boolean = false) =
        FleetRelease(tag, draft, pre, files.map { asset(it) })

    private val releases = listOf(
        release("v0.4.0", "demo-release.apk"),
        release("v0.3.0-rc1", "demo-debug.apk", pre = true),
        release("v0.2.0", "demo-debug.apk"),
        release("v0.1.0", "notes.txt"),
        release("v9.9.9", "demo.apk", draft = true),
        release("debug-latest", "demo-debug.apk", pre = true),
    )

    private fun build(installed: String?) = FleetReleaseOptions.build(source, installed, installed != null, releases, abis)

    @Test
    fun `every non-draft release is listed whatever its channel and drafts never are`() {
        val options = build("0.3.0")
        assertEquals(listOf("v0.4.0", "v0.3.0-rc1", "v0.2.0", "v0.1.0", "debug-latest"), options.map { it.tag })
    }

    @Test
    fun `channel reads stable, debug, pre-release and pre-release debug`() {
        val byTag = build("0.3.0").associateBy { it.tag }
        assertEquals(FleetReleaseChannel.STABLE, byTag.getValue("v0.4.0").channel)
        assertEquals(FleetReleaseChannel.PRE_RELEASE_DEBUG, byTag.getValue("v0.3.0-rc1").channel)
        assertEquals(FleetReleaseChannel.DEBUG, byTag.getValue("v0.2.0").channel)
        assertEquals(FleetReleaseChannel.PRE_RELEASE, FleetReleaseChannel.of(prerelease = true, debug = false))
    }

    @Test
    fun `a release with no APK for this phone is listed but not installable`() {
        val none = build("0.3.0").single { it.tag == "v0.1.0" }
        assertFalse(none.installable)
        assertNull(none.asset)
        assertTrue("no APK for this phone" in none.label)
    }

    @Test
    fun `relation to the installed version is newer, same, older, or cannot be compared`() {
        val byTag = build("0.3.0").associateBy { it.tag }
        assertEquals(FleetVersionRelation.NEWER, byTag.getValue("v0.4.0").relation)
        assertEquals(FleetVersionRelation.SAME, byTag.getValue("v0.3.0-rc1").relation)
        assertEquals(FleetVersionRelation.OLDER, byTag.getValue("v0.2.0").relation)
        assertTrue(byTag.getValue("v0.2.0").isDowngrade)
        assertEquals(FleetVersionRelation.NOT_COMPARABLE, byTag.getValue("debug-latest").relation)
        assertEquals("Can't compare", FleetVersionRelation.NOT_COMPARABLE.label)
    }

    @Test
    fun `an app that is not installed compares with nothing`() {
        assertTrue(build(null).all { it.relation == FleetVersionRelation.NOT_INSTALLED })
        assertTrue(build(null).none { it.isDowngrade })
    }

    @Test
    fun `an app with no releases gets an empty menu and a none label`() {
        assertEquals(emptyList(), FleetReleaseOptions.build(source, null, false, emptyList(), abis))
        assertEquals("No releases published", FleetReleaseOptions.NONE_LABEL)
    }

    @Test
    fun `assets from another repo are never offered`() {
        val foreign = FleetRelease("v1", false, false, listOf(asset("evil.apk", "someone/else")))
        val option = FleetReleaseOptions.build(source, null, false, listOf(foreign), abis).single()
        assertFalse(option.installable)
    }

    @Test
    fun `label joins tag, channel and relation`() {
        val option = build("0.3.0").single { it.tag == "v0.4.0" }
        assertEquals("v0.4.0 · Stable · Newer than installed", option.label)
    }
}
