# Research: fixed-block Signal Wand vertical slice

- Query: 在 JS-only `signalType` 合同下，如何安全地实现第一个固定闭塞主信号放置工具切片。
- Scope: internal
- Date: 2026-10-08
- Status: implemented in the current working batch

## Identity and catalog result

- `signalId` remains the identity of one placed world device.
- Kotlin/world code calls the reusable catalog identity `signalType`; world JSON writes it as `type`.
- The command never exposes filesystem package paths, `modelId` or `logicId`. It reads an immutable, sorted
  player-facing view of `(signalType, displayName)` from the prepared SimpleSignals catalog.
- The settings choice and Wand PDC are intent only. The authoritative manager transaction captures the exact catalog,
  runtime publication and topology, then recompiles the candidate against that catalog. Unknown or replaced
  types fail without changing disk, signal sources, models or the live definition.
- There is no `packageSignalId`, provider union, built-in Kotlin signal rule, or package-origin field in the
  world model.

## Implemented interaction

`/simplesignals wand signal` gives one generic plugin-owned blaze rod when the catalog is ready and non-empty; it
does not accept a type argument. A stable marker identifies the tool separately from its encoded
`(itemId, stateRevision, signalType)` state, and the first sorted catalog type is only the deterministic initial
selection. Shift-right-click opens the tool-owned Paper settings dialog to choose from the current sorted catalog.
The existing `simplesignals.admin` permission is checked when issuing the tool, applying a setting, starting a click
and receiving the target result.

Settings callbacks require the same main-hand item identity and revision, a still-current catalog member and a live
plugin/permission before advancing the revision with `Math.addExact`. Ordinary edits capture and compare the complete
item state, so selecting away and back cannot revive an older callback. Corrupt item state fails closed, and a stored
type that disappeared from the catalog remains visibly unavailable instead of being silently replaced.

While the tool is in the main hand, one player entity-scheduler task refreshes an action bar containing its fixed-block
mode and current type. Held-slot, swap, inventory, drop, pickup, join and interaction events reconcile that task;
permission/plugin/player loss, scheduler retirement, quit and handler close stop it. A delayed retired callback is
bound to its own registration and cannot remove a newer task. This is a SimpleSignals-owned status display, not a
general BukkitPlugin action-bar arbitration API.

One click uses the established player-owner -> target completion -> player-owner -> worker -> player-owner flow.
Quit, world change, held-slot change, hand swap, inventory edit, drop, world unload and plugin shutdown invalidate
the session. Duplicate clicks coalesce while one edit is active; late callbacks must still match the same world,
complete item state, permission and session.

The pure entrance resolver restricts candidates through the fixed-block rail index, then accepts only a position
within 0.75 metres of an authored entrance while the player looks into that entrance. It distinguishes FORWARD
and REVERSE from the compiled traversal, rejects reverse entry on a one-way block, rejects body clicks and rejects
shared-boundary ambiguity rather than selecting by collection order.

## Authoritative edit

This slice only updates an existing primary signal for the unique `(blockId, entryDirection)`. It preserves
`signalId`, controlled block, entry direction and inspection lead time; advances the device revision with
`Math.addExact`; replaces `signalType`; and writes a rail-attached placement at the selected point. The backend
submits exactly one `SimpleSignalsDefinitionPatch` through `applyDefinitionPatch`.

That existing transaction performs candidate geometry validation, exact topology/runtime/catalog checks, JS and
model preparation from the same type, the common edit permit, staged persistence and atomic publication/source/model
replacement. A denial or stale capture retains the complete old state.

## Deliberate boundary

The tool does not create a new primary because `inspectionLeadTimeSeconds` authoring has not been decided. It also
does not delete signals, edit placement presets/styles, create ground placements, edit protection or attached
signals, hot-reload the catalog, change schema version, or add a compatibility reader. The current unpublished
world boundary remains strict schema 1. Live Folia interaction and client rendering remain deployment checks.
