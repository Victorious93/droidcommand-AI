package ai.droidforge.build

interface BuildEnvironmentDetector {
    fun check(tool: EnvironmentTool): ToolCheckResult

    fun checkAll(tools: Set<EnvironmentTool>): List<ToolCheckResult> = tools.map { check(it) }
}
