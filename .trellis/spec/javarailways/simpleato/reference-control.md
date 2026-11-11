# Reference driving and stopping control

## Scope and owners

Status: current implementation in `SimpleATO/control`. `NominalMotionReference`,
`DrivingReferenceStrategy`, `StoppingReferenceStrategy` and `NominalServiceBrakeModel` own
ordinary operating control. Basic owns the actual per-car dynamics and device-update algebra.
The independently composed executor owns hard protection, command application, intervention
latches and diagnostics; these strategies do not generate an ATP judgement.

The input carries the current speed/acceleration, ordered per-car devices and capabilities,
all relative-position speed targets, actual physical delta and declared maximum delta.
A target at distance zero means the actual operating point; neither this point nor the reference
curve is displaced by the host's 0.3 m arrival acceptance tolerance.

## Reference preparation and runtime work

`NominalMotionReference.fromCapabilities` prepares an immutable ideal profile once per vehicle
configuration. Configured operating acceleration/deceleration and nominal performance samples
are distinct from conservative guaranteed pneumatic braking. A low or zero guaranteed pneumatic
floor does not imply that a blended nominal service brake has no operating capability.
The profile prepares grade variants at 0.005 intervals over [-0.2, 0.2], rounding to the adverse
bin. Outside that domain or without positive deceleration it returns no profile.

Constant operating deceleration joins a constant-jerk terminal release continuously. The release
jerk is bounded by half the positive physical acceleration slews and 0.5 m/s³. Position queries
use square/cube roots and translate the same profile to every target; they do not integrate
future dynamics or construct a new profile every tick. Runtime curve work is O(targets).

Positive speed targets reserve a conservative brake-preparation interval before the nominal
deceleration begins. For target speed `vt` at distance `d`, the allowed speed is
`max(vt, -aT + sqrt((aT)^2 + vt^2 + 2ad))`, where `T` is the largest per-car command delay or
electric-brake release time, plus 1.25x the nominal air apply time, plus one supervision
margin. The formula treats preparation as constant speed, so it over-reserves distance; at the
target point it never reports less than `vt`. Zero-speed stopping keeps the existing terminal
jerk curve and `stoppingDistanceMeters` inverse. This closes the observed gap where a nominal
positive-limit curve allowed a speed from which no ordinary service-brake interval remained
hard-safe; the nominal curve is still not a safety certificate.

`ReferenceStrategyMode.stoppingTarget` selects only an actual zero-speed target whose braking
curve is intercepted, at no more than 20 km/h. It also enters when a moving train has already
begun ordinary braking for a governing zero-target reference; actuator response or passive uphill
braking can otherwise keep speed below the ideal interception curve until premature rest.
A brake inherited while initially stationary does not count as begun stopping. Once entered, the executor supplies the previous target identifier and distance; the same
non-extending target remains in stopping even if inertia temporarily increases speed or passive
uphill deceleration lowers it faster than the ideal curve. An increased target distance releases
this latch and re-evaluates interception. The executor resets the
stopping strategy on mission/mode changes and owns the terminal-rest latch, so extending an
unchanged identifier and repairing an early standstill cannot accidentally share a latch.

## Per-car nominal response

The nominal model requires the complete ordered device snapshot. Missing physical state produces
NaN / `MODEL_UNAVAILABLE`; it never substitutes an average pressure vehicle. One known current
interval calls Basic's shared `VehicleDeviceDynamics.advanceDeviceState`. The following response
uses finite affine segments ending at delay expiry, slew catch, electric/air compensation and
adhesion saturation. `AffineActuatorResponse` uses closed-form discrete velocity/distance sums,
including the first rest crossing and asymmetric jerk. It has a fixed catch-event budget per
segment, and no loop over future physical ticks or calls to `TrainDynamics.step`.

Future speed-dependent electric demand and resistance are frozen nominal approximations. The
model is not a conservative certificate, especially during electric fade. Production accuracy
must be checked using the real kernel and frozen vehicle configuration; constant-force algebra
agreement alone does not prove real-model parking accuracy.

## Decision and failure contracts

The executor supplies a predicate containing only hard ordinary-control feasibility. A strategy
first filters actual configured ordinary commands with this predicate; EB never participates in
ordinary optimization. `ordinaryCommandAdmissible=false` means this hard set is empty and leaves
the intervention decision to the executor.

Nominal stopping feasibility is evaluated for all zero targets, even when the immediate lowest
reference speed is still the vehicle's cruise speed. This prevents positive acceleration from
consuming the response distance before the ideal speed curve becomes restrictive. If no nominal
candidate reaches the exact operating goal, the strategy still chooses the strongest hard-safe
ordinary brake and reports `TARGET_UNREACHABLE`. `MODEL_UNAVAILABLE` and `TARGET_UNREACHABLE`
are operating accuracy states, never ATP failures.

A far-target fast path avoids evaluating every candidate model when the nearest zero target is
already beyond the whole catalogue's nominal stopping distance. The bound probes the
strongest-traction ordinary command: every candidate shares the same maximum-service-brake
continuation, and that command cannot leave the known interval slower than another, so the
probe bounds the catalogue. One maximum supervision interval is added to cover a cached
decision. Beyond the bound all candidates are nominally reachable, precision stays `TRACKING`,
and no per-candidate model is built. Near the target the complete per-candidate evaluation is
unchanged. A non-finite probe disables the fast path.

Every notch is compared to one common response-adjusted reference, with feedforward acceleration,
velocity feedback and a 0.025 m/s² score hysteresis. Candidate-specific reference horizons are
forbidden: they compare different objectives and can penalize a necessary release. A pending
accepted device command is retained through its delivery delay only while it remains hard-safe
and release-reachable; protection can interrupt the hold. Holding through all force settling is
not equivalent and can cause premature stops.

The stopping strategy excludes traction. Before another brake interval it checks whether the
maximum release travel still reaches the point. Terminal holding uses the weakest hard-safe
ordinary command whose pneumatic force can oppose gravity at rest, excluding favorable Davis
resistance and electric braking. This is N on flat/uphill track and a light brake on downhill
track; releasing everything and waiting until zero to send B can allow downhill movement during
its delivery delay. The nominal event model also computes first rest under that held demand.
When further braking would lose the point and the held-demand rest is within one current
interval of the target, it commits this terminal demand. Every subsequent command still passes
the hard predicate; a changed/extended target resets the commitment. This suppresses terminal
B/N cycling without a position offset, late traction correction or bypassing ATP.

## Verification

`ReferenceStrategyTest` covers curve continuity/inversion, ordinary versus nominal infeasibility,
missing device state, moving target extension, per-car event algebra versus the current kernel,
and heterogeneous electric/air parking under fixed/variable deltas, grade and asymmetric jerk.
Parking experiments aim at <= 5 cm error and low last-metre switching. This is an experimental
acceptance target for measured feasible cases, not a guarantee for every initial state/profile.
Full executor/ATP and frozen S100 experiments are separate required evidence before deployment.

`ReferenceProtectionIntegrationTest` composes the real per-car kernel with the executor and ATP:
24 heterogeneous parking cases, two varying-position nonzero-speed scenarios, two held-air
nearby departures (2/10 m), and an instantaneous 1 kg exact-boundary case. Nonzero-speed cases
retain the vehicle's higher physical maximum so that speed compliance is not supplied by a
physical max-speed clamp. Each actual scenario asserts parking accuracy, absence of ATP events,
and post-rest holding; frozen S100 world tests remain separate evidence.
