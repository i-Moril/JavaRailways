# S100 display resources

Run `python3 scripts/display-assets/package.py` from any directory (Python standard library only). It generates deterministic bitmap glyphs and three local archives:

- `models/s100.zip`: current train package, including its own front/exterior XML and screen parts.
- `models/cabin-route-style.zip`: independent 4:1 cabin style. Extract into `plugins/Decorations/styles/`, yielding `styles/cabin-route/displays.yml`. Restart Decorations to capture the installed style source. This does not modify the world or train package.
- `models/resourcepack-s100-displays-1.21.11-26.2.zip`: small standalone overlay containing only the display font, ring/dot textures, black screen backing and three required S100 model overrides (including its26.2 variant). Enable it **above the original S100 resource pack**; it does not include or replace the train pack.

The independent style exports `cabin-route.route`. S100 references it explicitly; missing or invalid styles are reported, rather than silently choosing a different template. Host parts/transforms still load without Decorations/SimpleDisplays. Source is captured once on Decorations enable; editing files does not update active sessions.

Rectangles now use SimpleDisplays `<rect>` backed by the vanilla `█` glyph in `minecraft:include/default`, with independently scaled X/Y and measured ink-origin compensation. No rectangle bitmap font assets are generated. See the task's `research/vanilla-rectangle-metrics.md` for verified26.1.2/26.2 client glyph dimensions and native transform.

The generated `javarailways:display_geometry` font now contains only E004 ring and E005 dot. These two existing textures retain logical ink height9/ascent7. Their actual ink center is one logical pixel to the right of the entity center; templates compensate by `x - size / 9`. The previous comment asserting that padding itself centers the ink was wrong.

XML order maps to slightly increasing physical depth in Decorations, bounded to 0.01 of the smaller screen dimension, so backgrounds precede labels and markers. This supports painter ordering across separate text entities. Glyph loading, final text fit, coplanar depth precision and physical screen orientation still require client acceptance.

Building these archives does not deploy plugins, install the style on a server, replace a server resource pack or execute tests. Unified tests and Paper/Folia/client acceptance remain deferred per the task decision.
