#!/usr/bin/env python3
"""One-time migration for automatic CURVE operating limits.

Reads JavaRailways *-infrastructure.json wrappers, recomputes automatic CURVE
operating and derail thresholds from final persisted geometry, and updates only
Basic rail metadata. Default mode is dry-run. --apply creates a timestamped
recovery copy and atomically replaces the source file.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def horizontal_unit(vector: dict) -> tuple[float, float] | None:
    x = float(vector["x"])
    z = float(vector["z"])
    length = math.hypot(x, z)
    return None if length < 1.0e-12 else (x / length, z / length)


def dot(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def normalize(v: tuple[float, float, float]) -> tuple[float, float, float] | None:
    length = math.sqrt(dot(v, v))
    return None if length < 1.0e-12 else tuple(value / length for value in v)


def base_normal(tangent: tuple[float, float, float]) -> tuple[float, float, float]:
    reference = (0.0, 0.0, 1.0) if abs(tangent[1]) > 0.99 else (0.0, 1.0, 0.0)
    projection = dot(reference, tangent)
    result = tuple(reference[index] - tangent[index] * projection for index in range(3))
    return normalize(result) or (0.0, 1.0, 0.0)


def calculate_limits(
    points: list[dict],
    allowable_cant_deficiency: float,
    derail_acceleration: float,
    gauge: float,
    gravity: float,
) -> tuple[float | None, float | None]:
    unique: list[dict] = []
    seen_arcs: set[float] = set()
    for point in points:
        arc = float(point["arcLength"])
        if arc not in seen_arcs:
            unique.append(point)
            seen_arcs.add(arc)
    if len(unique) < 2:
        return None, None

    operating_acceleration = gravity * allowable_cant_deficiency / gauge
    operating = math.inf
    derail = math.inf
    for index, point in enumerate(unique):
        previous = unique[0 if index == 0 else index - 1]
        following = unique[-1 if index == len(unique) - 1 else index + 1]
        delta_arc = float(following["arcLength"]) - float(previous["arcLength"])
        if delta_arc <= 1.0e-9:
            continue
        previous_tangent = horizontal_unit(previous["tangent"])
        following_tangent = horizontal_unit(following["tangent"])
        if previous_tangent is None or following_tangent is None:
            continue
        cross_y = previous_tangent[1] * following_tangent[0] - previous_tangent[0] * following_tangent[1]
        turn_angle = math.atan2(
            cross_y,
            previous_tangent[0] * following_tangent[0] + previous_tangent[1] * following_tangent[1],
        )
        signed_curvature = turn_angle / delta_arc
        curvature = abs(signed_curvature)
        if curvature < 1.0e-8:
            continue

        tangent_dict = point["tangent"]
        normal_dict = point["normal"]
        tangent = normalize((float(tangent_dict["x"]), float(tangent_dict["y"]), float(tangent_dict["z"])))
        normal = normalize((float(normal_dict["x"]), float(normal_dict["y"]), float(normal_dict["z"])))
        if tangent is None or normal is None:
            continue
        base = base_normal(tangent)
        roll = math.atan2(dot(cross(base, normal), tangent), dot(base, normal))
        supporting_cant_angle = -math.copysign(1.0, signed_curvature) * roll
        denominator = curvature * math.cos(supporting_cant_angle)
        if denominator <= 1.0e-12:
            continue
        cant_acceleration = gravity * math.sin(supporting_cant_angle)
        operating = min(
            operating,
            math.sqrt(max(0.0, (operating_acceleration + cant_acceleration) / denominator)),
        )
        derail = min(
            derail,
            math.sqrt(max(0.0, (derail_acceleration + cant_acceleration) / denominator)),
        )
    return (
        operating if math.isfinite(operating) else None,
        derail if math.isfinite(derail) else None,
    )


def migrate(path: Path, apply: bool, derail_acceleration: float, gauge: float, gravity: float) -> int:
    wrapper = json.loads(path.read_text(encoding="utf-8"))
    core = json.loads(wrapper["coreSnapshotJson"])
    basic = json.loads(wrapper["basicSnapshotJson"])
    rails = {rail["id"]: rail for rail in core.get("rails", [])}
    changed = 0

    for entry in basic.get("railMetadata", []):
        rail_id = entry["railId"]
        metadata = entry["metadata"]
        authoring = metadata.get("authoring") or {}
        if not authoring.get("curveSpeedLimitAutomatic", True):
            continue
        rail = rails.get(rail_id)
        if rail is None:
            continue
        geometry = authoring.get("geometry") or {}
        allowable = float(geometry.get("allowableCantDeficiencyMeters", 0.075))
        operating, derail = calculate_limits(
            rail["curveAB"]["pointList"], allowable, derail_acceleration, gauge, gravity
        )
        limits = metadata.setdefault("speedLimits", {}).setdefault("entries", [])
        before_curve = next((item.get("valueMetersPerSecond") for item in limits if item.get("type") == "CURVE"), None)
        limits[:] = [item for item in limits if item.get("type") != "CURVE"]
        if operating is not None:
            limits.append({"valueMetersPerSecond": operating, "type": "CURVE"})
        metadata.setdefault("curveSafety", {})["baseCurveDerailSpeedMetersPerSecond"] = derail
        if before_curve != operating:
            changed += 1
            print(f"{path.name}: {rail_id} CURVE {before_curve!r} -> {operating!r}")

    print(f"{path}: {changed} automatic rail(s) would change" if not apply else f"{path}: applying {changed} change(s)")
    if not apply or changed == 0:
        return changed

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    recovery = path.with_name(f"{path.name}.pre-curve-operating-limits-{timestamp}.bak")
    shutil.copy2(path, recovery)
    wrapper["basicSnapshotJson"] = json.dumps(basic, ensure_ascii=False, indent=4)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as temporary:
            json.dump(wrapper, temporary, ensure_ascii=False, separators=(",", ":"))
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    print(f"recovery point: {recovery}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--derail-acceleration", type=float, default=3.0)
    parser.add_argument("--gauge", type=float, default=1.435)
    parser.add_argument("--gravity", type=float, default=9.80665)
    args = parser.parse_args()
    for path in args.paths:
        migrate(path, args.apply, args.derail_acceleration, args.gauge, args.gravity)


if __name__ == "__main__":
    main()
