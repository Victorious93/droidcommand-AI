package ai.droidcommand.app.ui.chat

import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.SavedChat

/**
 * The state after one send finished, as a pure function so it runs in a JVM test and so [ChatViewModel] can run its side
 * effects (speaking the reply) after the state is published. `MutableStateFlow.update` may call its lambda more than once,
 * so nothing with a side effect belongs inside it.
 *
 * A failed turn changes nothing in the stored conversation (see `ChatSession.send`: "so the caller can retry with the same
 * text"). The input box was already cleared when the user tapped send, so the text goes back through
 * [ChatUiState.pendingInput] and lands in the box again, ready to retry or edit.
 *
 * [sentText] is what the user typed. [refreshedChats] is the saved-chat list re-read after a reply, or null to keep the old one.
 */
internal fun ChatUiState.afterTurn(
    result: ChatResult,
    sentText: String,
    localNotice: String?,
    refreshedChats: List<SavedChat>?,
): ChatUiState = when (result) {
    is ChatResult.Reply -> copy(
        lines = lines + ChatLine(true, sentText.trim()) + ChatLine(false, result.text),
        streaming = "",
        sending = false,
        notice = result.notice ?: localNotice,
        savedChats = refreshedChats ?: savedChats,
    )
    is ChatResult.Failure -> copy(streaming = "", sending = false, error = result.message, pendingInput = sentText)
}
