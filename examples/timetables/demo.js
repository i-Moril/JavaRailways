// @ts-check
/// <reference path="../../types/timetable.d.ts" />

/** @param {MineRailsTimetableBuilder} timetable */
function main(timetable) {
  timetable.setPeriodDays(1)
  const useExpress = true // ordinary JS is resolved while the timetable is built
  const platformOpen = timetable.predicate("demo:platform-open", { platform: "central-1" })

  timetable.service("demo-001", "train-001", function (t) {
    t.check("depot1")
    t.at(1000).goto(useExpress ? ["a0", "central-1"] : ["a0", "a1", "central-1"])
    t.at(2000).call("central", "1", { minimumDwellTicks: 100, doorSide: "LEFT" })
    t.at(2000).goto("central-1")
    t.at(2100).whenCondition(platformOpen,
      actions => actions.action("demo:chime", { text: "Departing" }),
      actions => actions.action("demo:chime", { text: "Held" }))
    t.at(3000).reverse()
    t.at(3100).goto("depot1")
    t.at(4000).halt()
  })
}
