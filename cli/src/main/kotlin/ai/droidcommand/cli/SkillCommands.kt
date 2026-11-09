package ai.droidcommand.cli

import ai.droidcommand.config.ConfigSource
import ai.droidcommand.config.EnvConfigSource

/**
 * The `skills` subcommand: a thin, device-free manager so a user never has to hand-edit
 * `enabled-skills.txt` to approve or revoke a skill. It operates over the same
 * `DROIDCOMMAND_SKILLS_DIR` + state file that `forge` reads (see [resolveSkillEnvironment]), through
 * [ai.droidcommand.skills.SkillDiscovery]/[ai.droidcommand.skills.SkillRegistry]/[ai.droidcommand.skills.FileSkillStateStore]:
 *
 *   skills list              — discover every SKILL.md and print each with its enabled/disabled state
 *   skills enable <name>     — approve a skill (only one actually discovered on disk)
 *   skills disable <name>    — revoke a skill
 *
 * The disabled-until-approved posture is preserved: `enable`/`disable` only ever flip the flag of a
 * skill the discovery walk actually found and installed, and the registry still grants no capability
 * (an enabled skill reaches a tool only through the existing policy/approval/grant stack) — enabling
 * only makes a skill's instructions eligible to join a Forge run's system prompt.
 *
 * [configSource] defaults to the real process environment ([EnvConfigSource]) but is injectable, so
 * tests drive it with a `ConfigSource { k -> map[k] }` lambda over a temp directory, the same fixture
 * style [SkillWiringTest] uses. Output goes to stdout/stderr (captured in tests via `System.setOut`),
 * matching the other command handlers in this package.
 */
internal fun runSkills(rest: List<String>, configSource: ConfigSource = EnvConfigSource()): Int =
    when (val sub = rest.firstOrNull()) {
        "list" -> runSkillsList(configSource)
        "enable" -> runSkillsSetEnabled(rest.drop(1), configSource, enable = true)
        "disable" -> runSkillsSetEnabled(rest.drop(1), configSource, enable = false)
        null -> {
            System.err.println("Usage: skills <list|enable|disable> [name]")
            1
        }
        else -> {
            System.err.println("Unknown skills subcommand '$sub'")
            System.err.println("Usage: skills <list|enable|disable> [name]")
            1
        }
    }

private fun runSkillsList(configSource: ConfigSource): Int {
    val env = resolveSkillEnvironment(configSource) ?: run {
        printNoSkillsDir()
        return 1
    }
    val skills = env.registry.list().sortedBy { it.manifest.name }
    if (skills.isEmpty()) {
        println("No skills (SKILL.md files) found under DROIDCOMMAND_SKILLS_DIR.")
        return 0
    }
    val enabledCount = skills.count { it.enabled }
    println("Skills ($enabledCount enabled / ${skills.size} total):")
    for (skill in skills) {
        val state = if (skill.enabled) "enabled " else "disabled"
        println("  [$state] ${skill.manifest.name} — ${skill.manifest.description}")
    }
    return 0
}

private fun runSkillsSetEnabled(args: List<String>, configSource: ConfigSource, enable: Boolean): Int {
    val verb = if (enable) "enable" else "disable"
    val name = args.firstOrNull()
    if (name.isNullOrBlank()) {
        System.err.println("Usage: skills $verb <name>")
        return 1
    }

    val env = resolveSkillEnvironment(configSource) ?: run {
        printNoSkillsDir()
        return 1
    }

    if (env.registry.get(name) == null) {
        System.err.println("No skill named '$name' was discovered under the skills directory.")
        System.err.println("Run 'skills list' to see the available skills.")
        return 1
    }

    if (enable) env.registry.enable(name) else env.registry.disable(name)

    // Merge onto the decisions the store last held so an entry for a skill not currently on disk is
    // preserved rather than dropped; the currently-discovered skills' flags are overwritten with the
    // registry's up-to-date view. The write is atomic (FileSkillStateStore).
    val merged = env.persistedState.toMutableMap()
    merged.putAll(env.registry.enabledStateByName())
    env.stateStore.save(merged)

    println("Skill '$name' is now ${if (enable) "enabled" else "disabled"}.")
    return 0
}

private fun printNoSkillsDir() {
    System.err.println(
        "No skills directory configured. Set DROIDCOMMAND_SKILLS_DIR to a directory of SKILL.md files.",
    )
}
