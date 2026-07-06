function forDoorSide(door_side, callback) {
  const side = String(door_side || "left").toLowerCase()
  if (side === "both" || side === "all") {
    callback("left")
    callback("right")
  } else {
    callback(side)
  }
}

function onStation(time, door_side) {
  const t = Number(time)
  forDoorSide(door_side, function(side) {
    if (t === 0) action(`door-${side}-open`)
    if (t >= 20) action(`door-${side}-close`)
  })
}

function door(side, scene) {
  const normalizedScene = String(scene || "open").toLowerCase()
  const suffix = normalizedScene === "closed" || normalizedScene === "close" ? "close" : "open"
  forDoorSide(side, function(doorSide) {
    action(`door-${doorSide}-${suffix}`)
  })
}

function horn() {
  sound("minecraft:entity.minecart.riding", 0.8, 1.0)
}
