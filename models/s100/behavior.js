function forDoorSide(door_side, callback) {
  const side = String(door_side || "left").toLowerCase()
  if (side === "both" || side === "all") {
    callback("left")
    callback("right")
  } else {
    callback(side)
  }
}

function physicalDoorSide(semanticSide) {
  const side = String(semanticSide || "left").toLowerCase()
  if (side !== "left" && side !== "right") return side
  return operatingEnd() === "tail" ? (side === "left" ? "right" : "left") : side
}

function runDoorAction(side, suffix) {
  forDoorSide(side, function(doorSide) {
    action(`door-${physicalDoorSide(doorSide)}-${suffix}`)
  })
}

defineAction("door-left-open", { displayName: "左侧开门", executable: true, requiresControl: false }, function() {
  runDoorAction("left", "open")
})

defineAction("door-left-close", { displayName: "左侧关门", executable: true, requiresControl: false }, function() {
  runDoorAction("left", "close")
})

defineAction("door-right-open", { displayName: "右侧开门", executable: true, requiresControl: false }, function() {
  runDoorAction("right", "open")
})

defineAction("door-right-close", { displayName: "右侧关门", executable: true, requiresControl: false }, function() {
  runDoorAction("right", "close")
})

function onStation(time, door_side) {
  const t = Number(time)
  if (t === 0) runDoorAction(door_side, "open")
  if (t >= 20) runDoorAction(door_side, "close")
}

function door(side, scene) {
  const normalizedScene = String(scene || "open").toLowerCase()
  const suffix = normalizedScene === "closed" || normalizedScene === "close" ? "close" : "open"
  runDoorAction(side, suffix)
}

function refreshLights() {
  action(operatingEnd() === "tail" ? "lights-tail-running" : "lights-head-running")
}

defineAction("lights-refresh", { displayName: "刷新头尾灯", executable: true, requiresControl: false }, function() {
  refreshLights()
})

defineAction("end-lights-off", { displayName: "关闭头尾灯", executable: true, requiresControl: true }, function() {
  action("end-lights-off")
})

defineAction("cabin-lights-on", { displayName: "开启车内灯", executable: true, requiresControl: true }, function() {
  action("cabin-lights-on")
})

defineAction("cabin-lights-off", { displayName: "关闭车内灯", executable: true, requiresControl: true }, function() {
  action("cabin-lights-off")
})

function onReverse() {
  refreshLights()
}

function horn() {
  sound("minecraft:entity.minecart.riding", 0.8, 1.0)
}
