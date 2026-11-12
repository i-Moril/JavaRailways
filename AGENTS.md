# Repository agent rules
## Avoid unwanted actions
- Do not guess or speculate my requirements without checking with me first.
- Do not implement anything I didn't ask for without checking with me first.
- Do not add compatibility shims, legacy aliases, migrations, old-shape detection or rejection, fallback paths, dual behavior, or stale overrides. Unless explicitly required.
- Always try to reuse existing code. Do not repeatedly implement something that is already implemented.

## Avoid speculative defenses
- Do not add sanitization, redaction, validation, or fallback logic for hypothetical problems. State the concrete failure it fixes.
- When extending an existing command, preserve its output behavior. Do not introduce a new sanitization policy for names, paths, or status messages.
- Keep existing protections for credential access, storage, and configuration, unsafe paths, and scan integrity. Do not extend them to unrelated values without a demonstrated need.
- Do not invent a restriction and then add tests whose only purpose is to enforce that restriction.

## Commit Rules
- 必须向我明确当前修改的分支，必须检查是否是当前最新的分支
- 做完更改后必须提交

## Version Rules

涉及世界存档、配置文件、extension payload、WebUI 本地恢复数据或其他持久化格式时，必须先阅读[版本号与发布基线](.trellis/spec/javarailways/shared/versioning.md)，再遵守 [`ai_tasks/persistence_schema_evolution_rules.md`](ai_tasks/persistence_schema_evolution_rules.md)


<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

If you're using Codex or another agent-capable tool, additional project-scoped helpers may live in:
- `.agents/skills/` — reusable Trellis skills
- `.codex/agents/` — optional custom subagents

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update`.

<!-- TRELLIS:END -->