(ns cementmill.robotics
  "Robot-executed quality-lab verification -- the concrete, actor-
  level realization of ADR-2607011000's robotics premise (every
  cloud-itonami vertical is designed on the premise that a robot
  performs the physical-domain work; an independent governor gates any
  action before it ever reaches hardware), replicating the pattern
  ADR-2607142800 established as the fleet-wide convention (reference
  implementation: `automotive.robotics` in `cloud-itonami-isic-2910`)
  for THIS actor's own `cementmill.facts` requirement that a
  shipment proposal cite a 28-day-compressive-strength-test-report
  actually on file -- not merely a self-reported checklist string.

  ADR-2607152000 (extending ADR-2607151600, the automotive pilot that
  first wired a cloud-itonami `<domain>.robotics` namespace onto a REAL
  physics engine) rewires this ns onto a REAL engineering simulation
  instead of a synthetic, deterministic field comparison: the
  compressive-strength-test-press step of the quality-lab mission is
  now an ACTUAL time-stepped `kotoba-lang/physics-2d` rigid-body
  simulation -- a real `physics-2d` press-platen `Body2D` closes at a
  controlled velocity onto a real static (mass 0) cube-specimen
  `Body2D`, `world-step` actually integrates/collides/resolves the
  contact over real ticks, and `:sim-peak-compressive-force-n`/
  `:sim-peak-compressive-stress-mpa` are read directly off the ACTUAL
  simulated velocity trajectory (`press-telemetry-for` below) -- not
  invented. Unlike automotive's isic-2910 -> kami-engine-vehicle-
  designer pairing, this vertical has no design-library sibling repo,
  so the physics module lives DIRECTLY in this ns and takes a real
  pinned git-coordinate dependency on `kotoba-lang/physics-2d` alone
  (see `deps.edn`) -- ADR-2607152000's own key simplification versus
  the automotive pilot.

  A robot mission (`kotoba.robotics/mission`) walks the cement batch
  through three steps in the finish-mill QC lab -- an automated
  sampling arm pulling a cube specimen, an automated compressive-
  strength-test press breaking it, and an automated Blaine-fineness
  (specific-surface-area) scan -- built with `kotoba.robotics/action`
  + `kotoba.robotics/telemetry-proof`, and reports an overall :passed?
  verdict now derived from the REAL simulated press reading
  (`:sim-peak-compressive-stress-mpa`, see `press-telemetry-for`), not
  a hand-set field. `simulation-out-of-tolerance?` independently
  re-derives that verdict from the batch's OWN recorded real telemetry
  cross-checked against the batch's OWN recorded 28-day compressive-
  strength acceptance band (`:strength-28d-min`/`:strength-28d-max` --
  a REAL, already-established anchor, reused here, not a newly invented
  ceiling constant, unlike automotive's `decel-ceiling-g`), never from
  the mission's self-reported result -- the SAME 'ground truth, not
  self-report' discipline `cementmill.registry/cement-batch-strength-
  out-of-range?` uses for the independent ship-time recheck (both are
  grounded in the same 28-day-compressive-strength acceptance band,
  unlike `automotive.robotics`/`automotive.registry`'s two DISTINCT
  fields [crash telemetry for the CAE mission, emissions-deviation for
  dispatch] -- deliberate here, because 28-day compressive strength IS
  the single most standard cement QC/acceptance metric, and it is
  literally what the compressive-strength-test-press step of THIS
  mission measures -- now doubly so, since the mission itself actually
  runs a real press-collision simulation to produce that reading).
  `cementmill.governor`'s `robotics-simulation-violations` calls this
  ns's independent recheck, never the stored :passed? value, before any
  `:actuation/ship-cement-batch` proposal may commit.

  Honest scope (ADR-2607152000, mirroring ADR-2607151600's automotive
  disclosures):

  - 2D projection only (`physics-2d` has no 3D solver) -- x is the
    press's direction of travel (platen closing onto the specimen), y
    is lateral; world gravity is [0 0] (a horizontal press-closing
    projection, not a vertical drop).
  - the cube-specimen is a real ASTM C109/C109M 50 mm (2 in.) cube --
    'Standard Test Method for Compressive Strength of Hydraulic Cement
    Mortars (Using 2-in. or [50-mm] Cube Specimens)' -- modeled as a
    STATIC (mass 0) AABB, exactly mirroring `vdesign.simphysics`'s
    immovable crash-barrier pattern: `physics-2d` treats a mass-0 body
    as having zero inverse mass (an immovable anchor), which is also
    physically apt here -- a real compression-testing machine's
    specimen platform is bolted to the test rig, not free to recoil.
    `physics-2d` has NO material-stiffness/stress-strain model
    whatsoever, so the specimen's own real material strength cannot
    itself vary the simulated reading (the SAME disclosed limitation
    `vdesign.simphysics` states for automotive's crash body) -- what
    DOES vary the reading is this batch's own recorded press-run
    configuration (`:press-platen-mass-kg`, see `press-telemetry-for`).
  - real hydraulic compression-testers ARE run at a controlled,
    continuous, non-shock loading rate -- EN 12390-3 (Testing hardened
    concrete, Part 3: Compressive strength of test specimens) specifies
    0.6 +/- 0.2 MPa/s, ASTM C39 (concrete cylinders, the sibling test
    `cloud-itonami-isic-4211`'s own press models, ADR-2607152000)
    specifies 0.25 +/- 0.05 MPa/s -- both real, citable, controlled-
    rate-not-impact conventions. But `physics-2d`'s impulse solver has
    NO stress/strain model at all (the SAME disclosed limitation
    automotive's crash simulation states) -- it only ever produces a
    meaningful collision impulse from an actual closing VELOCITY over a
    real timestep, and the standards' own literal mm/min-scale
    quasi-static rates would produce a physically negligible one-tick
    impulse. `press-closing-velocity-mps` below is therefore a
    deliberately chosen, disclosed ANALOG closing rate -- fast enough
    for `physics-2d`'s single-tick boxcar-collision model to produce a
    physically meaningful impulse, never presented as a literal
    reproduction of either standard's mm/min displacement rate. What IS
    real: the controlled/continuous/non-shock loading-rate STANDARDS
    cited above, and the fact that `world-step` actually integrates
    this velocity tick-by-tick, not a shortcut formula.
  - `physics-2d`'s impulse resolver has no progressive crush stiffness/
    force-deflection model: whatever tick first detects ANY AABB
    overlap fully zeroes the closing velocity in that ONE tick (given
    `restitution` 0) -- a discrete, instantaneous 'boxcar' stop, the
    SAME disclosed limitation `vdesign.simphysics` states for
    automotive's crash body. By exact kinematic identity (a=v^2/d for a
    boxcar full stop over transit distance d at speed v), the peak
    deceleration is INDEPENDENT of the platen's own mass when colliding
    with a mass-0 (immovable) specimen (mass cancels algebraically in
    `physics-2d`'s `resolve-contact`, the SAME verified, documented
    property `vdesign.simphysics` establishes for automotive's crash
    body) -- so `:press-platen-mass-kg` is what actually moves
    `:sim-peak-compressive-force-n`/`:sim-peak-compressive-stress-mpa`
    here (via F = m*a), never the closing velocity or crush-travel
    (both fixed constants, shared by every batch)."
  (:require [kotoba.robotics :as robotics]
            [physics-2d :as p2d]))

;; ───────────────────── real, cited physical constants ─────────────────────

(def ^:const specimen-side-mm
  "ASTM C109/C109M's real, standard 50 mm (2 in.) cube specimen size for
  THIS actor's own cement 28-day compressive-strength test (distinct
  from ASTM C39/EN 12390-3's concrete cylinder/cube geometry -- the
  related but distinct downstream test `cloud-itonami-isic-4211`'s own
  concrete-cylinder press models, ADR-2607152000)."
  50.0)

(def ^:const specimen-face-area-mm2
  "The cube's own loaded face area (mm^2). 1 MPa = 1 N/mm^2, so dividing
  a simulated force (N) by this real, fixed geometry constant converts
  directly to a stress reading (MPa) comparable to the batch's own
  recorded :strength-28d-min/:strength-28d-max (both MPa)."
  (* specimen-side-mm specimen-side-mm))

(def ^:const peak-strain-fraction
  "Disclosed engineering prior: ~0.2% axial compressive strain at peak
  stress, a commonly-cited approximate value for cementitious/concrete
  specimens (NOT a measured fact for any specific batch -- `physics-2d`
  has no material-stiffness/stress-strain model at all, so this
  namespace cannot derive it from first principles; it only needs SOME
  disclosed crush-travel distance to derive a timestep, exactly the
  role `vdesign.simphysics`'s per-vehicle-class `crush-len-m` prior
  plays for automotive's own `dt` derivation, ADR-2607151600)."
  0.002)

(def ^:const crush-travel-m
  "Nominal specimen deformation distance (m) at failure --
  `peak-strain-fraction` x the cube's own real side length. A single,
  fixed, disclosed constant shared by every batch (unlike automotive's
  per-CLASS crush-len table; this actor does not yet model per-mix-
  design stiffness differences -- honest scope, not hidden)."
  (* (/ specimen-side-mm 1000.0) peak-strain-fraction))

(def ^:const press-closing-velocity-mps
  "The press-platen's controlled closing velocity (m/s) for this
  simulation -- see this ns's own docstring ('real hydraulic
  compression-testers...') for why this is a disclosed ANALOG rate
  (fast enough for `physics-2d`'s boxcar-collision model to produce a
  meaningful impulse), not a literal reproduction of EN 12390-3's
  0.6 MPa/s or ASTM C39's 0.25 MPa/s controlled-loading-rate
  conventions."
  1.0)

(def ^:const dt
  "Per-tick timestep (s) -- derived from THIS simulation's own
  crush-travel/closing-velocity (the nominal transit time across the
  specimen's own crush zone), the SAME principled-not-arbitrary
  identity `vdesign.simphysics` uses for automotive's `dt`
  (ADR-2607151600)."
  (/ crush-travel-m press-closing-velocity-mps))

(def ^:const platen-half-w-m
  "Press-platen AABB half-width (m) along the travel axis -- a thin,
  rigid platen face (20 mm full thickness); `physics-2d` colliders do
  not deform, so this dimension is a disclosed, arbitrary rigid-body
  stand-in, not a load-bearing physical parameter."
  0.01)

(def ^:const platen-half-h-m
  "Press-platen AABB half-height (m), lateral -- 100 mm full width,
  wider than the 50 mm cube specimen so the WHOLE specimen face loads,
  matching how a real compression-testing machine's platen is sized
  >= the specimen face."
  0.05)

(def ^:const specimen-half-w-m
  "Cube-specimen AABB half-width (m) along the travel axis -- the real
  50 mm cube's own half-depth."
  (/ (/ specimen-side-mm 1000.0) 2.0))

(def ^:const specimen-half-h-m
  "Cube-specimen AABB half-height (m), lateral -- the real 50 mm cube's
  own half-width (a cube is square in cross-section)."
  specimen-half-w-m)

(def ^:const gap-m
  "Press standoff distance (m) the platen starts behind the specimen,
  so the trajectory captures a real pre-contact approach phase, not
  just the collision tick itself (mirrors automotive's
  `default-gap-m`)."
  0.05)

(def ^:const settle-ticks
  "Extra ticks appended after the platen is expected to reach the
  specimen, so the trajectory also captures post-contact settling --
  the SAME constant + rationale as `vdesign.simphysics` (ADR-2607151600):
  `physics-2d`'s positional correction removes 80% of any remaining
  overlap per tick, so residual overlap after 15 more ticks is
  ~3e-11 of whatever it was at first contact."
  15)

;; ───────────────────── real physics-2d press simulation ─────────────────────

(defn simulate-press
  "Time-steps a REAL `physics-2d` world for ONE compressive-strength-
  press-test cycle: a press-platen `Body2D` (mass `platen-mass-kg`,
  velocity `press-closing-velocity-mps`) approaches and collides with a
  static (mass 0, immovable -- matching `vdesign.simphysics`'s
  crash-barrier pattern, ADR-2607151600/ADR-2607152000) cube-specimen
  `Body2D`. Returns {:trajectory [{:tick :position :velocity} ...]
  (platen body only) :sim-peak-compressive-force-n n
  :sim-peak-compressive-stress-mpa n :sim-peak-crush-distance-m n
  :ticks n :dt n :closing-velocity-mps n}.

  `:sim-peak-compressive-force-n` is `platen-mass-kg` times the PEAK
  magnitude of tick-to-tick velocity change (along the travel axis)
  divided by `dt` -- F = m*a, derived from the ACTUAL simulated
  velocity trajectory (the SAME technique `vdesign.simphysics` uses for
  `:sim-decel-g`, extended one step further into a force via the
  platen's own recorded mass). `:sim-peak-compressive-stress-mpa`
  divides that force by the cube's own real face area (mm^2) -- 1 MPa =
  1 N/mm^2 -- so it is directly comparable to a batch's own recorded
  :strength-28d-min/:strength-28d-max (both MPa).
  `:sim-peak-crush-distance-m` is the largest AABB penetration depth
  (m) actually observed between the platen's leading face and the
  specimen's near face across the whole trajectory -- informational
  (this ns's tolerance check uses the force/stress reading, not
  displacement), derived from the actual simulated positions, not
  invented.

  Pure, deterministic -- the same `platen-mass-kg` always reproduces
  the same telemetry; no IO, no wall-clock."
  [platen-mass-kg]
  (let [v0 press-closing-velocity-mps
        approach-m (+ gap-m platen-half-w-m specimen-half-w-m)
        ticks (long (+ settle-ticks (long (Math/ceil (/ approach-m (* v0 dt))))))
        specimen-x 0.0
        platen-x (- specimen-x specimen-half-w-m platen-half-w-m gap-m)
        platen (p2d/make-body {:position [platen-x 0.0]
                                :velocity [v0 0.0]
                                :mass platen-mass-kg
                                :restitution 0.0
                                :friction 0.0
                                :collider (p2d/make-aabb-collider platen-half-w-m platen-half-h-m)
                                :user-data :press-platen})
        specimen (p2d/make-body {:position [specimen-x 0.0]
                                  :velocity [0.0 0.0]
                                  :mass 0.0
                                  :restitution 0.0
                                  :friction 0.0
                                  :collider (p2d/make-aabb-collider specimen-half-w-m specimen-half-h-m)
                                  :user-data :cube-specimen})
        w0 (p2d/world-new [0.0 0.0])
        [w1 pid] (p2d/world-add w0 platen)
        [w2 _sid] (p2d/world-add w1 specimen)
        worlds (reductions (fn [w _] (p2d/world-step w dt)) w2 (range ticks))
        trajectory (mapv (fn [tick world]
                            (let [b (nth (:bodies world) pid)]
                              {:tick tick :position (:position b) :velocity (:velocity b)}))
                          (range (count worlds)) worlds)
        vxs (mapv (comp first :velocity) trajectory)
        peak-decel-mps2 (->> (map (fn [va vb] (Math/abs (/ (- vb va) dt))) vxs (rest vxs))
                              (reduce max 0.0))
        contact-plane-x (- specimen-x specimen-half-w-m)
        penetrations-m (mapv (fn [{:keys [position]}]
                                (max 0.0 (- (+ (first position) platen-half-w-m) contact-plane-x)))
                              trajectory)
        peak-force-n (* platen-mass-kg peak-decel-mps2)]
    {:trajectory trajectory
     :sim-peak-compressive-force-n peak-force-n
     :sim-peak-compressive-stress-mpa (/ peak-force-n specimen-face-area-mm2)
     :sim-peak-crush-distance-m (reduce max 0.0 penetrations-m)
     :ticks (count trajectory)
     :dt dt
     :closing-velocity-mps v0}))

(defn press-telemetry-for
  "Runs the REAL `simulate-press` time-stepped `physics-2d` simulation
  for `batch`'s own recorded `:press-platen-mass-kg` press-run
  configuration and returns the actual simulated telemetry:
  {:sim-peak-compressive-force-n n :sim-peak-compressive-stress-mpa n
  :sim-peak-crush-distance-m n :ticks n :dt n :closing-velocity-mps n}.
  Pure, deterministic -- no IO; the same `:press-platen-mass-kg` always
  reproduces the same telemetry."
  [batch]
  (simulate-press (:press-platen-mass-kg batch)))

(def mission-actions
  "The three-step quality-lab verification mission every cement batch
  walks through before `:actuation/ship-cement-batch` is proposable.
  :grasp/:actuate at :low safety, :sense at :none -- verification/QA
  handling of a stationary lab specimen, not the moving-batch/vehicle-
  dispatch actuation that is `:actuation/ship-cement-batch` itself
  (always :safety-critical -- see `cementmill.governor`)."
  [{:step :cube-specimen-sampling        :kind :grasp   :safety :low}
   {:step :compressive-strength-press-test :kind :actuate :safety :low}
   {:step :blaine-fineness-scan          :kind :sense   :safety :none}])

(defn strength-tolerance-out-of-range?
  "Ground-truth check: does `batch`'s own recorded REAL
  `:sim-peak-compressive-stress-mpa` (the ACTUAL `physics-2d`-simulated
  press-collision reading -- see `press-telemetry-for`) fall outside
  its own recorded [:strength-28d-min :strength-28d-max] acceptance-
  band bounds? Reuses the batch's OWN already-established real
  acceptance band -- unlike automotive's `decel-ceiling-g`, this ns
  invents no new tolerance constant (ADR-2607152000). Needs no mission
  run or proposal inspection once the telemetry is on file -- its
  inputs are permanent fields already on the batch, the same shape
  `cementmill.registry/cement-batch-strength-out-of-range?` uses."
  [{:keys [sim-peak-compressive-stress-mpa strength-28d-min strength-28d-max]}]
  (and (number? sim-peak-compressive-stress-mpa) (number? strength-28d-min) (number? strength-28d-max)
       (or (< sim-peak-compressive-stress-mpa strength-28d-min)
           (> sim-peak-compressive-stress-mpa strength-28d-max))))

(defn simulate-quality-lab-cell
  "Run the robot quality-lab verification mission for `batch-id`
  (`batch` is the full cement-batch record, incl. `:press-platen-mass-
  kg` and `:strength-28d-min`/`:strength-28d-max`). Actually runs the
  REAL engine:

    1. `press-telemetry-for` -- the actual `physics-2d`-stepped press-
       platen/cube-specimen collision trajectory
       (`:sim-peak-compressive-force-n`/`:sim-peak-compressive-stress-
       mpa`).

  Returns {:mission .. :actions [{:action .. :proof ..} ..] :passed?
  bool :sim-peak-compressive-force-n n :sim-peak-compressive-stress-mpa
  n}. Deterministic: :passed? is derived from the batch's OWN recorded
  press-run configuration via the REAL simulated trajectory
  (`strength-tolerance-out-of-range?`), never invented or randomized --
  `kotoba.robotics` mandates no network/IO, and a repeatable simulation
  is what makes the governor's independent recheck
  (`simulation-out-of-tolerance?`) meaningful."
  [batch-id batch]
  (let [telemetry (press-telemetry-for batch)
        out-of-range? (strength-tolerance-out-of-range? (merge batch telemetry))
        reading (if out-of-range? :out-of-tolerance :nominal)
        mission (robotics/mission (str "mission-" batch-id "-quality-lab")
                                   :robot/quality-lab-cell-1
                                   :quality-lab-verification
                                   :boundaries {:station "finish-mill-qc-lab"}
                                   :max-steps (count mission-actions))
        actions (mapv (fn [{:keys [step kind safety]}]
                        (let [a (robotics/action (str (:mission/id mission) "-" (name step))
                                                  (:mission/id mission) kind safety
                                                  :params {:step step :batch-id batch-id})]
                          {:action a
                           :proof (robotics/telemetry-proof (:mission/id mission) step reading
                                                             :provenance :simulated)}))
                      mission-actions)]
    {:mission mission
     :actions actions
     :passed? (not out-of-range?)
     :sim-peak-compressive-force-n (:sim-peak-compressive-force-n telemetry)
     :sim-peak-compressive-stress-mpa (:sim-peak-compressive-stress-mpa telemetry)}))

(defn simulation-out-of-tolerance?
  "Independent ground-truth recheck for the governor: does `batch`'s
  OWN current, on-file REAL simulated press telemetry
  (`:sim-peak-compressive-stress-mpa`) fall out of its own recorded
  28-day compressive-strength acceptance band right now? Ignores
  whatever :passed? verdict a prior mission run stored -- identical in
  spirit to `cementmill.registry/cement-batch-strength-out-of-range?`'s
  refusal to trust a proposal's self-report. Does NOT re-run the
  simulation -- it re-derives the boolean from the real, already-
  persisted telemetry field (`cementmill.store` persists it on every
  `:cement-batch/upsert`), the same 'ground truth, not self-report'
  discipline applied to the STORED reading, not a fresh recompute."
  [batch]
  (strength-tolerance-out-of-range? batch))
