#!/usr/bin/env python3
"""Proves the shell-route guard rejects each bypass and passes the real tree. Run: python3 -I scripts/ci/test_check_app_shell_routes.py"""
import importlib.util
import pathlib
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("guard", HERE / "check_app_shell_routes.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

BAD = {
    "process_builder_executor": "val e = ProcessBuilderShellExecutor(policy)",
    "local_target": "val t = LocalProcessExecutionTarget()",
    "shellbacked_msf": "MetasploitTool(ShellBackedMetasploitExecutor(x))",
    "adb_root": "val r = AdbRootExecutor(adb)",
    "shizuku_all": "ShizukuTool.all(shell).forEach { register(it) }",
    "shizuku_exec": "ShizukuTool(shell, ShizukuToolKind.EXEC)",
    "root_tool_real": "register(RootTool(MagiskRoot()))",
    "process_builder": 'ProcessBuilder("sh", "-c", cmd).start()',
    "runtime_exec": 'Runtime.getRuntime().exec("sh")',
    "allowlist_without_termux": "AllowlistShellExecutor { setOf(\"ls\") }",
}


class GuardTest(unittest.TestCase):
    def tree(self, body, name="kotlin/x/Wiring.kt"):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        f = pathlib.Path(d.name) / "app" / "src" / "main" / name
        f.parent.mkdir(parents=True)
        f.write_text("package x\n" + body + "\n")
        return pathlib.Path(d.name)

    def test_each_bypass_is_rejected(self):
        for label, line in BAD.items():
            with self.subTest(label):
                self.assertTrue(guard.scan(self.tree(line)), label)
                self.assertEqual(guard.main(["x", str(self.tree(line))]), 1)

    def test_comments_and_the_null_root_tool_pass(self):
        root = self.tree("// ProcessBuilderShellExecutor in a comment\n * ShizukuTool.all(shell)\nregister(RootTool(NullRootExecutor()))\n"
                         "register(AutomationShellTool(AllowlistShellExecutor(termux) { advanced.shellAllowlist() }))")
        self.assertEqual(guard.scan(root), [])

    def test_allowlisted_file_only_allows_its_own_pattern(self):
        rel = "kotlin/ai/droidcommand/app/shizuku/ToolboxRuntime.kt"
        self.assertEqual(guard.scan(self.tree("ProcessBuilder(argv).start()", rel)), [])
        self.assertTrue(guard.scan(self.tree("ProcessBuilderShellExecutor(p)", rel)))

    def test_the_real_app_tree_passes(self):
        root = HERE.parent.parent
        self.assertEqual(guard.scan(root), [])


if __name__ == "__main__":
    unittest.main()
