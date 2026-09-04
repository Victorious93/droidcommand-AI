package ai.droidforge.build

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathExecutableDetectorTest {
    private lateinit var pathDir: Path

    @BeforeTest
    fun setUp() {
        pathDir = Files.createTempDirectory("droidforge-path-executable-test")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(pathDir)
    }

    @Test
    fun `finds an executable file present in a PATH directory`() {
        val executable = pathDir.resolve("gradle").toFile()
        executable.writeText("#!/bin/sh\necho fake\n")
        executable.setExecutable(true)

        val detector = PathExecutableDetector(pathEnv = { pathDir.toString() })
        assertTrue(detector.isOnPath("gradle"))
    }

    @Test
    fun `does not find an executable that is not on PATH`() {
        val detector = PathExecutableDetector(pathEnv = { pathDir.toString() })
        assertFalse(detector.isOnPath("nonexistent-tool"))
    }

    @Test
    fun `does not treat a non-executable file as found`() {
        val nonExecutable = pathDir.resolve("gradle").toFile()
        nonExecutable.writeText("not executable")
        nonExecutable.setExecutable(false)

        val detector = PathExecutableDetector(pathEnv = { pathDir.toString() })
        assertFalse(detector.isOnPath("gradle"))
    }

    @Test
    fun `returns false when PATH is null`() {
        val detector = PathExecutableDetector(pathEnv = { null })
        assertFalse(detector.isOnPath("gradle"))
    }

    @Test
    fun `checks every directory in a multi-entry PATH`() {
        val otherDir = Files.createTempDirectory("droidforge-path-executable-other")
        try {
            val executable = otherDir.resolve("git").toFile()
            executable.writeText("#!/bin/sh\n")
            executable.setExecutable(true)

            val fakePath = "$pathDir${File.pathSeparatorChar}$otherDir"
            val detector = PathExecutableDetector(pathEnv = { fakePath })
            assertTrue(detector.isOnPath("git"))
        } finally {
            deleteRecursively(otherDir)
        }
    }
}
