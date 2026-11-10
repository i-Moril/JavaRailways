# S100 display resources

Run `python3 scripts/display-assets/package.py` from any directory (Python standard library only). It generates deterministic bitmap glyphs and three local archives:

- `models/s100.zip`: current train package, including its own front/exterior XML and screen parts.
- `models/cabin-route-style.zip`: independent 4:1 cabin style. Extract into `plugins/Decorations/styles/`, yielding `styles/cabin-route/displays.yml`. Restart Decorations to capture the installed style source. This does not modify the world or train package.
- `models/resourcepack-s100-displays-1.21.11-26.2.zip`: complete client resource pack derived from the existing transparency pack, retaining its version overlays and only changing display texture references, adding the black backing texture and display font. Use this client pack for both exterior and cabin templates.

The independent style exports `cabin-route.route`. S100 references it explicitly; missing or invalid styles are reported, rather than silently choosing a different template. Host parts/transforms still load without Decorations/SimpleDisplays. Source is captured once on Decorations enable; editing files does not update active sessions.

The generated `javarailways:display_geometry` bitmap font contains E000 square, E001 8.5:1 rectangle, E002 2:1 rectangle, E003 4:1 rectangle, E004 ring, E005 filled circle, E006 8:1 rail and E007 8:3 rectangle. White glyphs accept XML text color. All have logical ink height 9, ascent 7, and balanced horizontal padding for centered TextDisplay output. Their geometry comes from deterministic raster math, with no browser shapes or unsupported XML drawing primitives.

XML order maps to slightly increasing physical depth in Decorations, bounded to 0.01 of the smaller screen dimension, so backgrounds precede labels and markers. This supports painter ordering across separate text entities. Glyph loading, final text fit, coplanar depth precision and physical screen orientation still require client acceptance.

Building these archives does not deploy plugins, install the style on a server, replace a server resource pack or execute tests. Unified tests and Paper/Folia/client acceptance remain deferred per the task decision.
