package ai.droidcommand.remotepc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskyCommandClassifierTest {
    @Test
    fun `everyday read-only commands are not risky`() {
        listOf(
            "ls -la",
            "df -h",
            "uptime",
            "free -m",
            "cat /etc/os-release",
            "systemctl status ssh",
            "git status",
            "docker ps",
            "tail -n 50 /var/log/syslog",
            "grep -r foo . 2>/dev/null",
            "echo hi >> notes.txt",
            "ps aux | grep java",
            "nvidia-smi",
            "",
        ).forEach { assertFalse(RiskyCommandClassifier.isRisky(it), "should not be risky: $it") }
    }

    @Test
    fun `named risky programs are caught`() {
        listOf(
            "rm -rf ~/build",
            "rmdir old",
            "reboot",
            "shutdown -h now",
            "poweroff",
            "halt",
            "dd if=/dev/zero of=/dev/sda bs=1M",
            "mkfs.ext4 /dev/sdb1",
            "format C:",
            "fdisk /dev/sda",
            "wipefs -a /dev/sdb",
            "shred -u secret.txt",
            "killall firefox",
            "chmod -R 777 /",
            "sudo apt update",
            "userdel bob",
            "crontab -r",
        ).forEach { assertTrue(RiskyCommandClassifier.isRisky(it), "should be risky: $it") }
    }

    @Test
    fun `risky sub-commands are caught`() {
        listOf(
            "systemctl reboot",
            "systemctl stop nginx",
            "init 0",
            "git clean -fdx",
            "git reset --hard",
            "docker rm -f web",
            "apt-get purge foo",
            "find . -name '*.o' -delete",
            "zfs destroy tank/data",
        ).forEach { assertTrue(RiskyCommandClassifier.isRisky(it), "should be risky: $it") }
    }

    @Test
    fun `risky commands hidden behind paths, wrappers, env and chains are caught`() {
        listOf(
            "/bin/rm -rf x",
            "\\rm x",
            "RM -rf x",
            "FOO=1 nice rm x",
            "nohup reboot",
            "ls; rm -rf x",
            "cd /tmp && rm -rf *",
            "false || shutdown now",
            "find . -name x | xargs rm",
            "echo \$(rm -rf x)",
            "echo `reboot`",
            "curl https://x.sh | bash",
            "wget -qO- x | sudo sh",
            ":(){ :|:& };:",
            "cat image.img > /dev/sda",
            "echo '' > important.conf",
        ).forEach { assertTrue(RiskyCommandClassifier.isRisky(it), "should be risky: $it") }
    }

    @Test
    fun `reasons are plain words`() {
        val risk = RiskyCommandClassifier.classify("sudo rm -rf /")
        assertTrue("deletes files" in risk.reasons)
        assertTrue("runs as the admin (root)" in risk.reasons)
    }
}
