# Cabin display layout — 2026-11-10

User-proposed template: line name + run.metadata.class.zh; next-station label/name; this-side-door label + next_station.metadata.transfer.zh. English page uses run.metadata.line.en and next_station.metadata.name.en, deliberately retaining class.zh, transfer.zh and Chinese door label as requested. Both pages target the same exact next-station occurrence. `run`/`next_station` are template-local aliases of operational.currentRun/nextStation, not new host APIs.

User refinements: left and right distinct backgrounds, right white and left selectable theme; special icons; route connectors use infrastructure line color; denser vertical station labels; support fewer/more stations than slots.

Interactive layout preview: thread visualization `cabin-route-layout.html`. This is a browser layout mock, not an existing SimpleDisplays/Minecraft frame. Theme default petrol teal (#125a69); fixture line color blue (#2574ba). Theme and line colors independent. Chinese upright vertical names; English sideways vertical with balanced word wrapping for long names. Preview has door/transfer/line icon placeholders backed by host Lucide, with text fallbacks. No resource pack glyphs or physical backing model installed yet. Door-side text remains TEST DATA: actual per-screen-side vs berth/formation door-side data must be connected before live claim.

Proposed slot behavior shown in preview:

- Default maximum8 slots; fit capacity to available width while preserving readable pitch.
- Fewer stops: fixed compact pitch, center actual nodes; no empty circles or rails beyond last station.
- More stops: contiguous occurrence window retaining at most two passed stations, then upcoming physical stations (including PASS calls). Short gray leading rail indicates omitted earlier stations once more than two have passed; short line-colored trailing rail indicates omitted later stations. Do not mark the last visible station as a terminus unless it is the actual final stop.
- One stop: circular stopping marker, no connecting rail. Zero: hide route/door/transfer and show no stop information. Completed simulated route retains the last two passed stations in gray, with no next-station blink.
- Repeated station names remain separate occurrences; future production window should anchor by exact serviceId/runId/callId, not name/stationId alone. Do not fabricate stops or truncate remainingStops domain data.

Local browser preview checks passed for0/1/3/8/15 stop counts, connector count, window advancement, language change and360px no-horizontal-overflow. These are preview-only checks, not repository suites/server/client acceptance. Backgrounds and custom glyph/icon resource integration remain before converting this browser design into a deployable train template. Existing SimpleDisplays IconFrame is Component-based and TextDraw currently carries text/position/size; no arbitrary browser shapes are implied by this preview.

## Refinements: history, stopping pattern and transfer logos

- Keep up to two passed station occurrences at the front. Passed outlines, center dots, names and rails are gray. PASS calls have hollow circles without center dots and gray names even when ahead; STOP calls have center dots.
- Blink only the center dot of the next STOP call; a preceding future PASS call must not become the next stopping station. Respect reduced-motion preference in the conversation preview.
- Lower the right diagram to reserve space above the rail for vertical stacks of other lines' colored logo badges. Fixture labels 2/7/4/8 etc. are illustrative; actual shared metadata/logo resources remain to be defined. Station names stay vertically below the rail.
- Default preview begins with two passed stations; advancing demonstrates omitted-history and omitted-future stubs. Preview-only browser checks cover 0/1/3/8/15 counts, two-station history, PASS/STOP markers, next STOP selection, both stubs, transfer positions, language changes, reduced motion and 360px no horizontal overflow. No repository suites or game acceptance performed.
- Runtime integration gap: current display inputs expose remainingStops only. Production must additionally expose exact ordered passed/physical station occurrences and stopping pattern/progress (including PASS), preserving occurrence identity; never infer completed occurrences from station names or fabricate history from remainingStops. This layout update does not claim that runtime data has been implemented.

## English layout and translation refinement

- Remove the right-side next-station text label; keep only the blinking center dot as the next STOP cue. Left-side next-station label remains.
- English station names use horizontal text rotated clockwise 45 degrees beneath the route, with space reserved at the right edge and a lower slot capacity when necessary. Chinese names remain upright vertical.
- User revised the earlier class.zh/Chinese-door-label decision: English page must use translated service class (run.metadata.class.en; fixture Limited Express) and door-side text (fixture Doors open on this side). Chinese page remains class.zh and 本侧开门. Door label still requires real screen/door-side input before live acceptance.
- Preview checks passed: no right-side Next label; class/door translations switch both ways; angled names stay within map bounds at desktop/mobile widths. Preview only; no runtime acceptance claimed.

## Destination and shared route geometry refinement

- Remove the textual omitted-station counts (前 N 站 / 后 N 站); retain leading/trailing rail stubs as continuation cues.
- Keep route width, station pitch, displayed occurrence window and capacity identical between Chinese and English at a given screen width. This supersedes the previous English-only reduced capacity/right margin.
- English uses compact 45-degree labels wrapping at word boundaries; particularly long labels occupy a lower tier with a thin leader from their station. Do not shrink the actual rail diagram when switching language.
- Left title now displays the destination: 开往 机场 / For Airport in the fixture. Next-station details remain below it. Production destination must follow the actual run destination, rather than a hardcoded station or infrastructure line name; last fixture STOP is used only for this mock.
- Local preview check: all simulated offsets 0–15 at widths 780/360/320; Chinese/English node positions identical, angled label bounds inside map, no page errors. Preview only; no repository/server/client acceptance performed.

## Current layout: equal screen bounds and restored line name

- Cancel 45-degree English labels and the lower-tier leader layout. Restore vertical columns; English is sideways within the vertical column and long names split into balanced word groups.
- Both language pages use identical fixed screen bounds at the same viewport: desktop 340px tall; narrow stacked preview 640px tall (300px info + 340px route). Same route width, pitch, capacity and node coordinates. Language content must not determine the panel size.
- Remove the right-side 线路图 / Route map heading. Keep station names and interchange logos.
- Restore infrastructure line name in the upper-left title alongside service class. Destination is a separate row (开往 机场 / For Airport); keep next-station information and door/transfer rows below.
- Preview validation passed for both languages, every simulated offset 0–15, and viewport widths 780/736/360/320: identical screen dimensions and node coordinates between languages, restored translated line name, no map heading or label transforms, no overflowing tested text or page errors. No runtime/server acceptance performed.
