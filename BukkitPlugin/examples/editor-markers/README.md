# Railway editor marker pack

Complete client resource pack for Minecraft **26.1.2 / resource format 84.0**.
It supplies the shared `javarailways:editor_markers` font used by the Bukkit editor API:

- U+E100: direction arrow; its tip points along the supplied rail tangent.
- U+E101: double transverse boundary bar.
- U+E102: solid coverage rectangle, scaled into short continuous centreline bars following the curve.

The white alpha masks are tinted by the label Component. They are displayed as fixed,
unshadowed, transparent-background, full-bright TextDisplays in the rail plane, not particles.
Readable captions continue to face the player and use the normal text font.
Section tools show only transverse boundary marks and the thin centreline, without direction arrows
or repeated captions inside the section. Direction arrows remain available to the rail direction tool.

Install the `resourcepack/` directory (or ZIP its **contents**, with `pack.mcmeta` at the root)
as a client resource pack. It can be enabled beside the train, rail and four-light signal packs.
To merge with an existing pack, copy `assets/javarailways/font/editor_markers.json` and
`assets/javarailways/textures/font/editor_markers.png`; keep the target pack's compatible metadata.
No `minecraft:default` font is replaced, and no existing model/item names are overridden.
Run `./gradlew :BukkitPlugin:editorMarkersResourcePack` to create
`BukkitPlugin/build/distributions/JavaRailways-editor-markers-26.1.2.zip` with reproducible entry order and timestamps.
The server does not install or host client packs automatically. Without this pack, these private
glyphs cannot be rendered correctly; the server does not substitute particles.

`generate-texture.mjs` is the small deterministic authoring source (Node built-ins only).
Run `node BukkitPlugin/examples/editor-markers/generate-texture.mjs` from the repository root
to regenerate the committed 48×16 RGBA PNG. The bitmap font splits it into three 16×16 cells,
each displayed at eight font pixels high with ascent seven and advance nine pixels.
The helper converts these font units to metre-scaled rail geometry and raises glyphs 0.08m
along the supplied normal to avoid overlapping the rail surface.
It compensates the 26.1.2 TextDisplay renderer's built-in pixel origin so the bitmap centre,
not the text baseline or advance box, coincides with that lifted rail position.

This pack does not change any persistent server configuration or schema version. Source tests
can validate the font-to-texture chain and transforms, but actual client appearance, visibility
from below and display culling require game verification.
