# Japanese signalling API fit

## Scope

This note tests the proposed SimpleATO signal-package and JavaScript policy boundary against representative Japanese main-line signalling behavior. It is an architecture fit check, not a claim of certification-grade or operator-specific fidelity.

## Primary sources

- Japan Ministry of Land, Infrastructure, Transport and Tourism, railway technical-standard interpretation (2024-03-15): https://www.mlit.go.jp/tetudo/content/20240315interpretation.pdf
  - Defines main-signal indications including stop, warning/restricted, caution, reduced speed, speed restriction, proceed, and high-speed proceed.
  - Defines guided and shunting signals separately.
  - Defines distant, passing, and repeating signals as dependent signals.
  - Gives operational speed semantics: for example, warning and guided indications are normally at or below 25 km/h, while caution/reduced-speed/speed-restriction indications are related to the speed required at a following indication.
- MLIT notice concerning route indicators: https://www.mlit.go.jp/notice/noticedata/sgml/1987/62048000/62048000.html
  - Describes route indication using lamp patterns, numbers, simple signs, or characters attached to a home signal.
- JR East train-control overview: https://www.jreast.co.jp/recruit/ebook/traincontrol/pageindices/index4.html
  - Describes interlocking as checking train absence on the route and turnout alignment before displaying a proceed condition.
- JR Central Safety Report 2024: https://company.jr-central.co.jp/others/report/_pdf/report2024.pdf
  - Describes ATC permitted-speed display and automatic braking, and ATS-PT target-distance speed patterns for stop signals, turnouts, curves, and dead ends.
- MLIT Chubu Transport Bureau railway glossary: https://wwwtb.mlit.go.jp/chubu/tetsudou/yougo.html
  - Defines fixed block as allowing one train to occupy a section, interlocking as coupling signals and turnouts, and ATC as continuous speed supervision from leading-train and line-condition information.

## Fit result

The proposed mechanism covers ordinary Japanese automatic-block, home, starting, and route-indicator behavior if it receives host-authoritative occupancy, route grant, turnout alignment, exit clearance, and target-speed facts. Line/operator differences in lamp combinations and fixed indication speeds can remain package YAML plus JavaScript mapping.

Three changes are required compared with the first API draft:

1. Use named indication channels (`main`, `route`, `speed`, `auxiliary`) instead of one indivisible `aspectId`.
2. Allow declared dependent-signal inputs through a validated acyclic graph for distant, passing, and repeating signals.
3. Add host-issued typed authority modes and enforced target-speed profiles. Guided/call-on and shunting permission cannot be created by a restrictive-only display script.

ATS/ATC safety belongs in SimpleATO's constraint/authority pipeline. A script may render a wayside or cab indication from the target-speed profile, but must not be the only producer or enforcer of the braking curve.

## Recommended first-release boundary

Implement main, distant/repeating, automatic block, home/starting, route, and speed indication packages first. Reserve stable authority-mode values for restricted/guided/shunting operation, but defer the operator workflow and special authorization lifecycle unless it is explicitly included in the MVP. This preserves future expressiveness without putting exceptional movement permission into JavaScript.
