# Developer-assistance features: validated edits, git checkpoints, repository map

Three small, independent `Tool`-shaped modules that give the agent the
code-change workflow a coding assistant needs. Each is pure Kotlin/JVM,
follows the fail-closed style of `core-shell`/`core-security`, and adds
**no third-party dependency**.

| Module | Tool(s) | Security level | Depends on |
|---|---|---|---|
| `core-code-edit` | `edit_file` | `SENSITIVE` (writes a file) | `core-agent` |
| `core-git` | `git_undo`, plus the `CheckpointingTool` wrapper | `SENSITIVE` (writes to a repository) | `core-agent`, `core-shell` |
| `core-repo-map` | `repo_map` | `NORMAL` (read-only) | `core-agent` |

All three are gated exactly like every other tool: registered in a
`ToolRegistry` and invoked through `core-security.SecureToolExecutor`.
None of them bypasses approval, and none is enabled by default anywhere
(no wiring into `cli`/`app` is part of this change).

## Architecture fit

- `Tool`, `ToolSpec`, `ToolResult`, `SecurityLevel`, `PermissionCategory`
  come from `core-agent` (`Tool.kt`). New tools use `PermissionCategory.FILES`
  (`edit_file`, `repo_map`) and `PermissionCategory.TERMINAL` (`git_undo`,
  because it spawns `git`).
- Process spawning is **only** via `core-shell.ShellExecutor` (argument
  vector, never `sh -c`, executable allow-list). `core-git` therefore works
  only if the caller's `ShellSecurityPolicy` allows the `git` executable and
  the repository directory. This is the same delegation `core-build-local`
  uses, and it means git inherits the fail-closed default.
- Path containment is checked against explicit `authorizedRoots`, resolved
  through `toRealPath()` so a symlink inside a root cannot point outside it.
  An empty root list authorizes nothing.

## Dependencies and licensing

| Need | Choice | Why |
|---|---|---|
| Source parsing | In-repo lexer (`LexicalSourceParser`) behind a `SourceParser` interface | See "Parser decision" below |
| Diff | In-repo unified-diff writer (common prefix/suffix trim + LCS on the middle) | The unified format is a public convention; no library needed |
| Ranking | In-repo PageRank power iteration (Brin & Page, 1998, public) | ~40 lines; no library needed |
| Git | The `git` binary through `ShellExecutor` | Real git semantics; no JGit (EDL-licensed, large, unnecessary) |
| Tests | `kotlin("test")` (already used everywhere) | Consistency |

Runtime requirement for `core-git`: a `git` executable on `PATH`
(developed against 2.43). Every git feature used predates 2.20 except where
noted in code.

### Parser decision (deliberate deviation from "use a parser")

A tree-sitter binding was considered. It was **not** chosen because:

1. It would be the repository's first native (JNI) dependency; `CLAUDE.md`
   reserves that slot for the planned llama.cpp bridge.
2. Android packaging of per-ABI native grammars is unverified, and this
   environment has no Android SDK to verify it.
3. The build environment this was written in cannot resolve any new Maven
   artifact, so an added dependency could not be compiled or tested.

Instead `SourceParser` is an interface and `LexicalSourceParser` is a real
tokenizer (comments, strings, raw strings, text blocks, template literals
are recognized and skipped) with light scope tracking. It is **not** an AST
parser: it cannot resolve overloads, imports, or types, so a reference is
"this identifier appears in code", not "this call resolves to that symbol".
A tree-sitter-backed `SourceParser` can be dropped in later without touching
the ranking or rendering code.

## Feature 1 — validated edits (`core-code-edit`)

Existing untracked code was reviewed first. Kept: the fenced
`SEARCH`/`REPLACE` block format, all-or-nothing semantics, `Tool` shape.
Changed: symlink-safe containment, strict UTF-8, per-block reporting, diff
output, dry-run, atomic write, original-content matching.

The block markers (`<<<<<<< SEARCH`, `=======`, `>>>>>>> REPLACE`) follow
git's merge-conflict marker convention, which the same format used by other
tools (e.g. Aider) also follows; that is deliberate for interoperability with
models already used to it. The implementation is original.

Acceptance criteria:

- **ED-1** A batch is applied only if every block's search text matches the
  file exactly once. Any other outcome leaves the file byte-identical.
- **ED-2** The result reports, per block, one of `MATCHED`, `NOT_FOUND`,
  `AMBIGUOUS`, `OVERLAP`, `EMPTY_SEARCH`. `AMBIGUOUS` lists the line number
  of every occurrence. `NOT_FOUND` says when a whitespace-insensitive match
  exists and where.
- **ED-3** All blocks are matched against the *original* content, never
  against the output of an earlier block; overlapping matches are rejected.
- **ED-4** A unified diff (3 lines of context) is returned for a successful
  edit.
- **ED-5** `dry_run` returns the diff and report and never writes.
- **ED-6** A target that resolves (through symlinks) outside every
  authorized root is rejected; an empty root list rejects everything.
- **ED-7** Non-UTF-8 content is rejected rather than rewritten lossily. CRLF
  files match against LF-normalized text and are written back as CRLF; a file
  with mixed line endings is rejected.
- **ED-8** The write is atomic (temp file + move) and preserves POSIX
  permissions where supported.
- **ED-9** Malformed edit text is rejected with the offending line number;
  the `edit_file` tool maps applied → `Success(diff)` and any rejection →
  `Failure(report)`.

## Feature 2 — git checkpoints (`core-git`)

Design rule: **every git operation is limited by pathspec to the files the
agent touched.** Nothing uses `reset --hard`, `clean`, `stash`, `checkout .`,
`commit -a`, or history rewriting, so unrelated work cannot be lost by
construction.

Flow: `begin(paths)` (before the agent edit) → edit runs → `commit(message)`.
`undo()` later restores the checkpointed paths and records that as a new
commit.

Acceptance criteria:

- **GC-1** Auto-commit is opt-in: `CheckpointingTool` is a pass-through
  unless constructed enabled, and `GitCheckpointer` does nothing on its own.
- **GC-2** `begin` reports `Skipped(reason)` (edit still proceeds, just
  unchecked) for: not a git repo, no commits yet, merge/rebase/cherry-pick/
  revert in progress, path outside the repo, path git-ignored, or any path
  that already has uncommitted changes (so the user's work-in-progress is
  never swept into an agent commit).
- **GC-3** The checkpoint commit contains only the given paths. Unrelated
  staged changes stay staged, unrelated unstaged changes stay unstaged,
  untracked files stay untracked.
- **GC-4** Commits carry a `DroidCommand-Checkpoint` trailer and a
  configurable agent author. They do not require the user to have
  `user.name`/`user.email` set, do not prompt for GPG, and do **not** bypass
  hooks; a failing hook yields `Failed(reason)`, not an exception.
- **GC-5** No content change → `NoChanges`, no empty commit.
- **GC-6** `undo` restores the checkpoint's paths to their pre-checkpoint
  state and records it as a new commit with a `DroidCommand-Undo: <sha>`
  trailer. History is never rewritten.
- **GC-7** `undo` preserves unrelated user changes: staged, unstaged,
  untracked, and commits the user made after the checkpoint to other files.
- **GC-8** `undo` refuses, changing nothing, when any checkpoint path has
  uncommitted changes or was modified by a later commit; the message lists
  those paths.
- **GC-9** `undo` handles files the checkpoint added (removed), deleted
  (restored), and modified (restored).
- **GC-10** `undo` with no argument targets the most recent checkpoint that
  has not already been undone; an undone checkpoint is not offered again.
- **GC-11** git runs only through `ShellExecutor` as an argument vector;
  every failure surfaces as a result value.
- **GC-12** `CheckpointingTool` wraps any `Tool`: it commits only when the
  wrapped tool returns `Success`/`Partial`, and never changes the wrapped
  tool's result (checkpoint outcome is appended to the output text).

## Feature 3 — repository map (`core-repo-map`)

Extract definitions and references per file, build a file-level reference
graph, rank with personalized PageRank, render within a size budget.

Acceptance criteria:

- **RM-1** `SourceParser` yields definitions (`kind`, `name`, `line`) and
  identifier references for Kotlin, Java, Python, JavaScript/TypeScript.
- **RM-2** Text inside comments and string literals never produces a
  definition or reference.
- **RM-3** Kotlin nested block comments and raw strings, Java text blocks,
  Python triple-quoted strings, and JS template literals are skipped
  correctly.
- **RM-4** Function-local declarations (`val`/`var`/`fun`, and in Kotlin local
  `class`/`interface`/`object`/`typealias`) are not reported as definitions:
  they cannot be referenced from another file, so reporting them would only
  add noise and false graph edges.
- **RM-5** Ranking is deterministic, converges, handles files with no edges,
  ignores self-references, and damps identifiers defined in many files.
- **RM-6** Task relevance: words in the task text (split on camelCase and
  snake_case) and explicit `focus_files` raise the rank of matching files and
  of files connected to them. The same repo yields a different top file for
  different tasks.
- **RM-7** Rendered output never exceeds the character budget, and an
  incomplete map always says how many files were left out (trailing file
  blocks are given up before the note is).
- **RM-8** Containment uses real paths; symlinks are not followed out of the
  root; build/vendor directories, oversized files, and binary files are
  skipped; there is a file-count cap.
- **RM-9** Unchanged files (same size and mtime) are not re-parsed on a
  repeat build.
- **RM-10** `repo_map` validates its inputs and returns `Failure`, not an
  exception, for bad ones.

## Explicitly out of scope

- Type-aware reference resolution, cross-language references.
- Fuzzy/line-number edit matching, multi-file edit transactions, file
  creation via `edit_file`.
- Three-way merge on undo (undo refuses on conflict instead).
- Wiring these tools into `cli` or `app` (registration is one line each and
  is left to the owner, since approval defaults are a policy decision).

## Known limitations of the repository map

The built-in parser is lexical (tokens plus a brace/indent scope pass), not a
full grammar. It is deliberately conservative about *false definitions* and
accepts these imprecisions:

- References are matched by name, never resolved by type, so an identifier
  declared in several files is split between them (and ignored beyond eight).
- Kotlin: a `"` inside a `${...}` template expression ends the string early;
  the damage is limited to the tokens on that expression, not the rest of the
  file, but it can add or drop a reference.
- JS/TS: regex literals are not recognised; a quote inside one is handled by
  strings ending at end of line.
- Java/JS method detection is heuristic (`javaMethodContext`/`jsMethodContext`).
- `TASK_NAME_BOOST` (edge-weight multiplier for task-matching names) is a
  tuning constant; no test pins its magnitude.

A tree-sitter or compiler-backed `SourceParser` can replace the lexical one
without touching ranking or rendering.

## Verification status

Recorded 2026-09-29. **Read the caveats first.**

**Caveat — Gradle was not usable in the environment this was built in.**
Nothing here has been run by `./gradlew test`. Instead the real, unmodified
sources and tests were compiled with Kotlin **2.0.21** (the compiler bundled
with Gradle 8.14.3) and run with **JUnit 4.13.2** through a small
`kotlin.test` shim, not with Kotlin 2.4.10 / JUnit Platform / ktlint as the
build files specify. The first `./gradlew test --continue` in a normal
environment is the real acceptance run and may find compile differences.

What was run, all passing:

| Scope | Tests |
|---|---|
| `core-code-edit` | 60 |
| `core-git` (real `git` 2.43.0 in temp repos) | 56 |
| `core-repo-map` | 79 |
| `core-integration-tests` `DevAssistIntegrationTest` (edit → checkpoint → undo) | 5 |

The integration test ran compiled inside a scratch stand-in module, because
`core-integration-tests` also depends on modules the harness cannot build;
its two new `testImplementation` lines in `build.gradle.kts` are unverified by
Gradle.

Independent checks beyond the unit tests:

- Unified diffs were checked against GNU `patch` and `git apply` as oracles.
- Safety-critical lines in `core-code-edit`/`core-git` were mutation-tested
  in scratch copies (each mutant had to make a test fail).
- `core-repo-map`: 38 mutants. 33 were killed on the first pass; the 5
  survivors led to three new or strengthened tests (path-term bonus, mtime
  cache invalidation, line numbers after multi-line comments) that now kill
  them; one survivor is an equivalent mutant (removing an explicit path
  tie-break from an already-stable sort); one — the magnitude of
  `TASK_NAME_BOOST` — is untested by design (see limitations).

Not verified: ktlint (only approximated with a script for unused/wildcard
imports, trailing whitespace, tabs and final newlines; one pre-existing unused
import in `SearchReplaceEditorTest` was removed), Windows path behaviour,
git versions other than 2.43.0, and the "file changed on disk between read
and write" race check in `SearchReplaceEditor`.
