// Package policy, not a national signalling standard or an ATO brake command.
const CAUTION_SPEED = 45 / 3.6;
const SLOW_SPEED = 65 / 3.6;

function evaluate(input, output) {
    if (input.self.occupied) {
        output.setSpeedLimit(0);
        output.setModelVariant("stop");
        return;
    }

    const referenceSpeed = input.referenceSpeedMetersPerSecond;
    const deceleration = input.referenceDecelerationMetersPerSecondSquared;
    if (!Number.isFinite(referenceSpeed) || referenceSpeed <= 0 ||
            !Number.isFinite(deceleration) || deceleration <= 0) {
        throw new Error("JR four-light logic requires calculated scanning reference speed and deceleration");
    }
    let permittedSpeedSquared = referenceSpeed * referenceSpeed;

    function restrictAt(distance, targetSpeed) {
        permittedSpeedSquared = Math.min(permittedSpeedSquared,
            targetSpeed * targetSpeed + 2 * deceleration * distance);
    }

    function considerEnd(end) {
        // A scan cutoff is not an obstacle. All other published termination reasons
        // are a stop boundary for this package, including cycle (not a physical end).
        if (!end) return;
        switch (end.reason) {
            case "distance-limit": return;
            case "track-end":
            case "cycle":
            case "route-unresolved":
            case "topology-unavailable":
                restrictAt(end.distanceMeters, 0);
                return;
            default: throw new Error("Unknown observation termination: " + end.reason);
        }
    }

    // Each list belongs to one path occurrence; never add sibling path lengths.
    const pending = [{branches: input.forward, end: input.end, exit: input.self.lengthMeters}];
    while (pending.length > 0) {
        const path = pending.pop();
        considerEnd(path.end);
        let hasSelectedBranch = false;
        for (const branch of path.branches) {
            if (branch.selection === "not-selected") continue;
            hasSelectedBranch = true;
            if (branch.routeReady === false) {
                restrictAt(branch.distanceMeters, 0);
                continue;
            }
            if (!branch.section) {
                considerEnd(branch.end); // A terminal-only branch has no next section.
                continue;
            }
            if (branch.section.occupied) {
                restrictAt(branch.distanceMeters, 0);
                continue;
            }
            if (branch.downstreamSignal && branch.downstreamSignal.speedLimitMetersPerSecond !== null) {
                restrictAt(branch.distanceMeters, branch.downstreamSignal.speedLimitMetersPerSecond);
            }
            pending.push({branches: branch.next, end: branch.end,
                exit: branch.distanceMeters + branch.section.lengthMeters});
        }
        // No selected continuation is not proof of a clear route. An explicit end
        // supplies its own boundary; otherwise stop at this section's known exit.
        if (!hasSelectedBranch && !path.end) restrictAt(path.exit, 0);
    }

    if (permittedSpeedSquared >= referenceSpeed * referenceSpeed) {
        output.clearSpeedLimit();
        output.setModelVariant("proceed");
    } else if (permittedSpeedSquared >= SLOW_SPEED * SLOW_SPEED) {
        output.setSpeedLimit(SLOW_SPEED);
        output.setModelVariant("slow");
    } else if (permittedSpeedSquared >= CAUTION_SPEED * CAUTION_SPEED) {
        output.setSpeedLimit(CAUTION_SPEED);
        output.setModelVariant("caution");
    } else {
        // No YY/variable-speed Y in this four-aspect pack: do not promise 45 km/h
        // when even that speed cannot meet the observed downstream restriction.
        output.setSpeedLimit(0);
        output.setModelVariant("stop");
    }
}
