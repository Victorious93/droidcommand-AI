package ai.droidcommand.templates

import ai.droidcommand.llm.ProviderType

/** Built-in content, and first-launch seeding that never overwrites an existing entry (so user edits survive). */
object BundledContent {
    private fun t(id: String, name: String, c: TemplateCategory, body: String) = PromptTemplate("builtin.$id", name, c, body, builtIn = true)

    val templates: List<PromptTemplate> = listOf(
        t("code.explain", "Explain code", TemplateCategory.CODING, "Explain what this {{language}} code does, step by step:\n\n{{code}}"),
        t("code.review", "Review code", TemplateCategory.CODING, "Review this {{language}} code for bugs, security issues and unclear naming. List findings by severity:\n\n{{code}}"),
        t("code.debug", "Debug an error", TemplateCategory.CODING, "I get this error in {{language}}:\n\n{{error}}\n\nRelevant code:\n\n{{code}}\n\nWhat is the most likely cause and the fix?"),
        t("code.tests", "Write unit tests", TemplateCategory.CODING, "Write unit tests using {{framework}} for:\n\n{{code}}\n\nCover edge cases and failure paths."),
        t("code.refactor", "Refactor for clarity", TemplateCategory.CODING, "Refactor this {{language}} code for readability without changing behavior, and explain each change:\n\n{{code}}"),
        t("write.summarize", "Summarize text", TemplateCategory.WRITING, "Summarize the following in {{length}}:\n\n{{text}}"),
        t("write.email", "Draft an email", TemplateCategory.WRITING, "Write a {{tone}} email to {{recipient}} about: {{topic}}"),
        t("write.proofread", "Proofread", TemplateCategory.WRITING, "Proofread this text. Fix grammar and spelling, keep my voice, and list what you changed:\n\n{{text}}"),
        t("write.rewrite", "Rewrite in a tone", TemplateCategory.WRITING, "Rewrite this text in a {{tone}} tone:\n\n{{text}}"),
        t("write.outline", "Outline a document", TemplateCategory.WRITING, "Create an outline for a {{format}} about {{topic}} for {{audience}}."),
        t("analyze.compare", "Compare options", TemplateCategory.ANALYSIS, "Compare {{optionA}} and {{optionB}} for {{goal}}. Use a table, then give a recommendation and say what would change it."),
        t("analyze.proscons", "Pros and cons", TemplateCategory.ANALYSIS, "List the pros, cons and main risks of {{decision}}."),
        t("analyze.extract", "Extract key points", TemplateCategory.ANALYSIS, "Extract the key facts, decisions and action items from:\n\n{{text}}"),
        t("analyze.assumptions", "Challenge assumptions", TemplateCategory.ANALYSIS, "Here is my plan: {{plan}}\n\nWhat assumptions does it rely on, which are weakest, and what is a simpler alternative?"),
        t("analyze.explain", "Explain a concept", TemplateCategory.ANALYSIS, "Explain {{concept}} to someone who knows {{background}}. Use one concrete example."),
        t("creative.story", "Story starter", TemplateCategory.CREATIVE, "Write the opening scene of a {{genre}} story featuring {{character}} in {{setting}}."),
        t("creative.names", "Brainstorm names", TemplateCategory.CREATIVE, "Suggest 10 names for {{subject}}. Give a one-line rationale for each."),
        t("creative.poem", "Write a poem", TemplateCategory.CREATIVE, "Write a {{style}} poem about {{topic}}."),
        t("creative.ideas", "Brainstorm ideas", TemplateCategory.CREATIVE, "Give me 8 distinct ideas for {{goal}}, including two unconventional ones."),
        t("creative.dialogue", "Write a dialogue", TemplateCategory.CREATIVE, "Write a dialogue between {{personA}} and {{personB}} about {{topic}}."),
    )

    val skills: List<Skill> = listOf(
        Skill("builtin.code-expert", "Code Expert", "Precise, security-aware coding help.", "You are an expert software engineer. Give correct, minimal, idiomatic code. Point out bugs, edge cases and security issues. If a request is ambiguous in a way that changes the answer, say so.", builtIn = true),
        Skill("builtin.concise", "Concise Assistant", "Short, direct answers.", "Answer as briefly as accuracy allows. Lead with the answer. No filler, no restating the question.", builtIn = true),
        Skill("builtin.socratic", "Socratic Teacher", "Teaches by guiding questions.", "You are a Socratic teacher. Guide the learner with short questions and hints before giving answers. Give the full answer if they ask directly or remain stuck after a couple of hints.", builtIn = true),
        Skill("builtin.creative-writer", "Creative Writer", "Vivid, original prose.", "You are a creative writer. Favor concrete imagery, varied rhythm and original ideas over cliché. Match the requested genre and tone.", builtIn = true),
        Skill("builtin.research-analyst", "Research Analyst", "Careful, evidence-minded analysis.", "You are a research analyst. Separate established facts from inference and speculation, state uncertainty, and say what evidence would resolve it. Never invent sources.", preferredProviderType = ProviderType.CLOUD, builtIn = true),
    )

    /** Adds each bundled item only if its id is absent. Returns how many were added. */
    fun seed(templateStore: TemplateStore, skillStore: SkillStore): Int {
        var added = 0
        for (tpl in templates) if (templateStore.get(tpl.id) == null) {
            templateStore.save(tpl)
            added++
        }
        for (skill in skills) if (skillStore.get(skill.id) == null) {
            skillStore.save(skill)
            added++
        }
        return added
    }
}
