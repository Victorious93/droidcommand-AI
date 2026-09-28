package ai.droidcommand.app.ui.tools

/** Shared execution-status shape for every tool screen (Metasploit, SET, and future ones alike). */
sealed class ToolRunUiState {
    data object Idle : ToolRunUiState()
    data object Running : ToolRunUiState()
    data class Success(val output: String) : ToolRunUiState()
    data class Failed(val reason: String) : ToolRunUiState()
}
