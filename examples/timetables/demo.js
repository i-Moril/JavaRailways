// @ts-check
/// <reference path="../../types/timetable.d.ts" />
// MineRails Phase 5 timetable DSL example.
const depot = ["depot1", "depot2"]
const outbound = ["a0", "a1", "b0"]
const inbound = ["b0", "a1", "a0", "depot1"]

/** @param {MineRailsTimetableBuilder} timetable */
function main(timetable) {
  timetable.setPeriod(16000)

  timetable.service("001", depot, function(t) {
    t.at(1000).goto(outbound)
    t.check("b0")
    t.at(4000).stop(200)
    t.at(4500).goto(inbound)
  })

  timetable.service("002", depot, function(t) {
    t.at(3000).goto(outbound)
    t.check("b0")
    t.at(6000).stop(200)
    t.at(6500).goto(inbound)
  })
}
