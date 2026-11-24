package ai.droidcommand.fleet.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetUpdateTest {
    private val repo = "Victorious93/File-Manager"
    private val source = FleetAppSource("file-manager", "File Manager", "ai.victorious.filemanager", repo)
    private fun url(file: String, r: String = repo) = "https://github.com/$r/releases/download/v1/$file"
    private fun asset(file: String) = ReleaseAsset(file, url(file), 10, null)
    private fun release(tag: String, vararg files: String, pre: Boolean = false, draft: Boolean = false) =
        FleetRelease(tag, draft, pre, files.map(::asset))
    private val abis = listOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun `versions compare as numbers and ignore a v prefix and suffix`() {
        assertEquals(-1, VersionCompare.compare("0.1.9", "0.1.10")?.coerceIn(-1, 1))
        assertEquals(0, VersionCompare.compare("v0.2.0-debug", "0.2"))
        assertEquals(1, VersionCompare.compare("1.0", "0.9.9")?.coerceIn(-1, 1))
        assertNull(VersionCompare.compare("nightly", "1.0"))
        assertNull(VersionCompare.compare(null, "1.0"))
        assertNull(VersionCompare.compare("1..2", "1.0"))
    }

    @Test
    fun `release json parses, skips broken entries and keeps only well-formed digests`() {
        val good = "a".repeat(64)
        val text = """[
          {"tag_name":"v1.2.0","draft":false,"prerelease":false,"assets":[
            {"name":"app-arm64-v8a.apk","browser_download_url":"${url("app-arm64-v8a.apk")}","size":5,"digest":"sha256:$good"},
            {"name":"bad.apk","browser_download_url":"${url("bad.apk")}","digest":"sha256:xyz"},
            {"name":"","browser_download_url":"x"}]},
          {"no_tag":true},
          "junk"]"""
        val releases = GitHubReleases.parse(text)
        assertEquals(1, releases.size)
        assertEquals(good, releases[0].assets[0].sha256)
        assertNull(releases[0].assets[1].sha256)
        assertEquals(2, releases[0].assets.size)
        assertTrue(GitHubReleases.parse("not json").isEmpty())
        assertTrue(GitHubReleases.parse(null).isEmpty())
        assertEquals(1, GitHubReleases.parse("""{"tag_name":"v1"}""").size)
    }

    @Test
    fun `releases url refuses anything that is not owner slash name`() {
        assertTrue(GitHubReleases.releasesUrl(repo).startsWith("https://api.github.com/repos/$repo/"))
        assertTrue(runCatching { GitHubReleases.releasesUrl("../x?y=1") }.isFailure)
    }

    @Test
    fun `download policy accepts only https github release paths of that repo`() {
        assertTrue(DownloadPolicy.isAllowedAsset(url("a.apk"), repo))
        assertTrue(DownloadPolicy.isAllowedAsset(url("a.apk").replace("Victorious93/File-Manager", "victorious93/file-manager"), repo))
        assertFalse(DownloadPolicy.isAllowedAsset(url("a.apk", "Evil/File-Manager"), repo))
        assertFalse(DownloadPolicy.isAllowedAsset("http://github.com/$repo/releases/download/v1/a.apk", repo))
        assertFalse(DownloadPolicy.isAllowedAsset("https://github.com.evil.com/$repo/releases/download/v1/a.apk", repo))
        assertFalse(DownloadPolicy.isAllowedAsset("https://user@github.com/$repo/releases/download/v1/a.apk", repo))
        assertFalse(DownloadPolicy.isAllowedAsset("https://github.com/$repo/releases/download/../../../x/a.apk", repo))
        assertFalse(DownloadPolicy.isAllowedAsset("https://github.com/$repo/archive/main.zip", repo))
        assertTrue(DownloadPolicy.isAllowedRedirect("https://objects.githubusercontent.com/abc?x=1", repo))
        assertFalse(DownloadPolicy.isAllowedRedirect("https://evil.example/a.apk", repo))
        assertFalse(DownloadPolicy.isAllowedRedirect("http://objects.githubusercontent.com/abc", repo))
    }

    @Test
    fun `sha256 verification needs a real matching digest`() {
        val hex = ApkVerifier.sha256Hex("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        assertTrue(ApkVerifier.matches(hex.uppercase(), hex))
        assertFalse(ApkVerifier.matches(null, hex))
        assertFalse(ApkVerifier.matches("abc", hex))
        assertTrue(ApkVerifier.looksLikeApk(byteArrayOf(0x50, 0x4B, 3, 4, 0)))
        assertFalse(ApkVerifier.looksLikeApk("<html>".toByteArray()))
    }

    @Test
    fun `not installed gives Install, newer tag gives Update, same or older gives UpToDate`() {
        val rel = listOf(release("v1.2.0", "app-arm64-v8a.apk"))
        assertIs<UpdateDecision.Install>(UpdatePlanner.plan(source, null, false, rel, abis))
        assertIs<UpdateDecision.Update>(UpdatePlanner.plan(source, "1.1.0", true, rel, abis))
        assertIs<UpdateDecision.UpToDate>(UpdatePlanner.plan(source, "1.2.0", true, rel, abis))
        assertIs<UpdateDecision.UpToDate>(UpdatePlanner.plan(source, "1.3.0", true, rel, abis))
    }

    @Test
    fun `an uncomparable installed version never claims an update`() {
        val rel = listOf(release("v1.2.0", "app.apk"))
        assertIs<UpdateDecision.Unknown>(UpdatePlanner.plan(source, "weird", true, rel, abis))
        assertIs<UpdateDecision.Unknown>(UpdatePlanner.plan(source, null, true, rel, abis))
    }

    @Test
    fun `an unsigned apk is never chosen, the debug apk beside it is, and an unsigned-only release offers nothing`() {
        // ShizukuV publishes shizukuv-<v>-release-unsigned.apk next to shizukuv-<v>-debug.apk. Android refuses an unsigned APK, and
        // release-named builds rank first, so without the filter the unusable file would win.
        val both = release("v1", "shizukuv-1.0-release-unsigned.apk", "shizukuv-1.0-debug.apk")
        val choice = assertIs<UpdateDecision.Install>(UpdatePlanner.plan(source, null, false, listOf(both), abis))
        assertEquals("shizukuv-1.0-debug.apk", choice.asset.name)
        assertTrue(choice.isDebugBuild)
        val only = release("v1", "shizukuv-1.0-release-UNSIGNED.apk")
        assertIs<UpdateDecision.NothingPublished>(UpdatePlanner.plan(source, null, false, listOf(only), abis))
        // a signed release APK still beats the debug one
        val signed = release("v2", "app-release.apk", "app-release-unsigned.apk", "app-debug.apk")
        assertEquals("app-release.apk", assertIs<UpdateDecision.Install>(UpdatePlanner.plan(source, null, false, listOf(signed), abis)).asset.name)
    }

    @Test
    fun `pre-releases are offered only when the owner opts in and drafts never are`() {
        val rel = listOf(release("v3", "a.apk", draft = true), release("v2", "a.apk", pre = true), release("v1", "a.apk"))
        // default: newest stable release wins, the pre-release is skipped
        assertEquals("v1", assertIs<UpdateDecision.Install>(UpdatePlanner.plan(source, null, false, rel, abis)).release.tag)
        // opt in: the newest non-draft release wins, even a pre-release
        val opted = assertIs<UpdateDecision.Install>(UpdatePlanner.plan(source, null, false, rel, abis, includePrereleases = true))
        assertEquals("v2", opted.release.tag)
        assertTrue(opted.release.prerelease)
        // a draft alone is never offered, opted in or not
        val onlyDraft = listOf(release("v3", "a.apk", draft = true))
        assertIs<UpdateDecision.NothingPublished>(UpdatePlanner.plan(source, null, false, onlyDraft, abis, includePrereleases = true))
    }

    @Test
    fun `an app that publishes only pre-releases is not published by default but updates when opted in`() {
        val only = listOf(release("v0.2.0", "app-debug.apk", pre = true))
        assertIs<UpdateDecision.NothingPublished>(UpdatePlanner.plan(source, "0.1.0", true, only, abis))
        assertIs<UpdateDecision.Update>(UpdatePlanner.plan(source, "0.1.0", true, only, abis, includePrereleases = true))
        // a rolling tag such as debug-latest cannot be compared with a version name, so no update is claimed
        val rolling = listOf(release("debug-latest", "app-debug.apk", pre = true))
        assertIs<UpdateDecision.Unknown>(UpdatePlanner.plan(source, "0.1.0", true, rolling, abis, includePrereleases = true))
    }

    @Test
    fun `drafts, prereleases and releases without apks are not offered`() {
        val none = UpdatePlanner.plan(source, null, false, listOf(release("v2", "a.apk", pre = true), release("v3", "a.apk", draft = true)), abis)
        assertIs<UpdateDecision.NothingPublished>(none)
        val noApk = UpdatePlanner.plan(source, null, false, listOf(release("v2", "notes.txt")), abis)
        assertIs<UpdateDecision.NothingPublished>(noApk)
        // falls back to the next release that does have an APK
        val older = UpdatePlanner.plan(source, null, false, listOf(release("v2", "notes.txt"), release("v1", "a.apk")), abis)
        assertEquals("v1", assertIs<UpdateDecision.Install>(older).release.tag)
    }

    @Test
    fun `asset choice fits the phone's abi and prefers release over debug`() {
        fun pick(vararg files: String) = (UpdatePlanner.plan(source, null, false, listOf(release("v1", *files)), abis) as? UpdateDecision.Install)
        assertEquals("app-arm64-v8a.apk", pick("app-x86_64.apk", "app-arm64-v8a.apk", "app-armeabi-v7a.apk")?.asset?.name)
        assertEquals("app-universal.apk", pick("app-x86_64.apk", "app-universal.apk")?.asset?.name)
        assertNull(pick("app-x86_64.apk"))
        val rel = pick("app-debug-arm64-v8a.apk", "app-arm64-v8a.apk")
        assertEquals("app-arm64-v8a.apk", rel?.asset?.name)
        assertFalse(rel!!.isDebugBuild)
        val onlyDebug = pick("app-debug.apk")
        assertTrue(onlyDebug!!.isDebugBuild)
    }

    @Test
    fun `assets from another repo are ignored`() {
        val foreign = FleetRelease("v1", false, false, listOf(ReleaseAsset("a.apk", url("a.apk", "Evil/x"), 1, null)))
        assertIs<UpdateDecision.NothingPublished>(UpdatePlanner.plan(source, null, false, listOf(foreign), abis))
    }
}
