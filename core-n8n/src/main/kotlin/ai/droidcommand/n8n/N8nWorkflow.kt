package ai.droidcommand.n8n

data class N8nWorkflow(
    val id: String,
    val name: String,
    val active: Boolean,
    val webhook: N8nWebhook?,
) {
    val canTrigger: Boolean get() = webhook != null
}

data class N8nWebhook(
    val path: String,
    val httpMethod: String,
)
