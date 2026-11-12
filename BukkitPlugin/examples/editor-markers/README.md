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

The client resource pack and its generator are maintained in the independent
JavaRailwaysReources repository. Install its editor-markers pack or merge its
font and texture into a compatible pack. No `minecraft:default` font is replaced.
The server does not install or host client packs automatically. Without this pack, these private
glyphs cannot be rendered correctly; the server does not substitute particles.

The resource repository contains the deterministic texture generator for the
48×16 RGBA PNG. The bitmap font splits it into three 16×16 cells, each displayed
at eight font pixels high with ascent seven and advance nine pixels.
The helper converts these font units to metre-scaled rail geometry and raises glyphs 0.08m
along the supplied normal to avoid overlapping the rail surface.
It compensates the 26.1.2 TextDisplay renderer's built-in pixel origin so the bitmap centre,
not the text baseline or advance box, coincides with that lifted rail position.

This pack does not change any persistent server configuration or schema version. Source tests
can validate the font-to-texture chain and transforms, but actual client appearance, visibility
from below and display culling require game verification.
