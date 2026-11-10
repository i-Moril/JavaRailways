# Cabin route style

This independent style bundle exports `cabin-route.route` through `displays.yml`. Its XML is `displays/cabin-route.xml`; use a 4:1 physical screen. S100 references the bundle instead of embedding it. Extract `models/cabin-route-style.zip` into `plugins/Decorations/styles/` (creating `styles/cabin-route/displays.yml`) and restart Decorations and include the accompanying JavaRailways display-geometry font in the client resource pack.

The fixed 800 × 200 canvas has a colored left information panel and a white right route panel. Both languages use the same geometry and rotate every 300 source ticks (15 seconds at normal simulation speed), independent of player locale. The left shows line, class, destination, current/next station, effective opening side and transfer caption; unknown facts stay blank.

Metadata conventions:

- Current run `metadata.line.{zh,en}` may override the line name; otherwise use current infrastructure line `metadata.name.{zh,en}`, then `displayName`.
- Run `metadata.class.{zh,en}` supplies the translated class.
- Explicit `operational.values.destination` is a string or `{zh,en}`. Destination is never inferred from the route's final known call.
- Station `metadata.name.{zh,en}` overrides `stationName`. Optional `metadata.transfer.{zh,en}` supplies the left transfer caption.
- Station `metadata.transfers` is an array of infrastructure line IDs. Each is looked up in `ctx.data.lines`; line `metadata.badge` is a string or `{zh,en}` (compact logo text), falling back to line name/ID. Line `metadata.themeColor` and `line.color` use `#RRGGBB`.
- Localized objects fall back from the active language to Chinese, then English. Unknown values do not manufacture names or destinations.

The route uses the full ordered `operational.stops`, authoritative `stationIndex` and `phase`; it never treats `completed` as physical departure. Up to two passed calls and eight total occurrences are shown, with no duplicate station-ID matching. Passed circles, labels and connections are gray; PASS calls have no center and gray labels. The next STOP/TERMINAL center alternates on/off every 10 source ticks. Unknown/finished progress leaves the route blank. Short routes are centered. Omitted history/future produce continuation stubs; unresolved future also keeps a continuation stub without inventing calls. An at-station cursor remains current until the domain reports departure.

Station names run vertically below the line; long names end with an ellipsis after seven Chinese-page or ten English-page characters. Transfer badges stack vertically above the line, up to three per call, followed by a `+` for further transfers. The left transfer caption can still describe the full set. Effective side comes only from `ctx.data.display.openingSide`, with translated this/opposite/both/none labels; missing installation/direction facts produce no side label.

Graphics use `javarailways:display_geometry` E000 square, E004 ring, E005 dot and E006 horizontal rail, through the actual supported span/font interface. These are real bitmap-font geometry, not unsupported CSS or implicit icon registration. Text uses a conservative width estimate because SimpleDisplays has no text-metrics API or clipping. Per-character route names and bounded transfers remain below the 1,024 draw limit. Exact pixel fitting, resource-pack font loading, overlap depth and in-game legibility need unified game acceptance; no test suites/server deployment were run for this resource batch.
