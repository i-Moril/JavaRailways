# S100 exterior displays

`displays.yml` supplies two package-local XML/document-JS templates: `s100_front_destination` uses 680 × 80 (8.5:1), and `s100_side_destination` uses 320 × 120 (8:3). Physical dimensions and transforms belong to the installed parts. The front template is mounted; the side template remains available for the existing `side_display` faces but is not yet mounted. There is no separate 2:1 screen: the corresponding model elements are halves of the 4:1 interior display housing.

Both show a colored service-class badge and a destination centered in the rest of the screen. As in the approved demo, horizontal side padding is 2% of the canvas width, the badge takes 30% of the padded content width, and its following gap is 3% of canvas width. Destination/class nominal sizes are 70%/60% of screen height (English class: 40%), reduced only when required by the available text width. Without a class the destination takes the entire width. Chinese and English share fixed dimensions and alternate every 300 source ticks (15 seconds at 20 ticks/s), independent of player language. The source clock follows the mounted session, including the existing hidden/pause behavior; this is not a wall-clock timer.

Input conventions:

- `operational.values.destination`: authored string or `{zh,en}`; never guessed from the last known stop.
- `operational.currentRun.metadata.class`: string or `{zh,en}`; absent/empty omits the badge.
- Optional run `metadata.classColor`: `#RRGGBB` badge fill.
- Current line `metadata.themeColor`, then `line.color`, then `#126EAA` provides the badge fallback.
- Localized objects use the active language, then Chinese, then English. Unknown content stays empty. Metadata is literal data and is never evaluated as code.

All backgrounds and class badges are explicit `<rect>` elements with independent left/top/width/height. The renderer stretches a measured vanilla Minecraft solid glyph; these templates do not depend on the custom rectangle font or tile square glyphs. Text uses supported centered spans and font/color/size declarations, not browser CSS, `<canvas>`, timers or undeclared icon maps. Text fits using a conservative character-width estimate; exact glyph metrics, font substitutions, physical readability, depth and model-frame insets require game acceptance. Nominal sizes are not a guarantee of actual visible ink height. Long labels reduce in size; no silent text clipping is assumed.

The reusable 4:1 cabin template is kept in `models/display-styles/cabin-route`, referenced as `cabin-route.route`; it is not embedded in this train package. No server deployment or client acceptance is implied by these source resources.
