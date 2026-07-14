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

  A robot mission (`kotoba.robotics/mission`) walks the cement batch
  through three steps in the finish-mill QC lab -- an automated
  sampling arm pulling a cube specimen, an automated compressive-
  strength-test press breaking it, and an automated Blaine-fineness
  (specific-surface-area) scan -- built with `kotoba.robotics/action`
  + `kotoba.robotics/telemetry-proof`, and reports an overall :passed?
  verdict. `simulation-out-of-tolerance?` independently re-derives
  that verdict from the batch's OWN recorded 28-day compressive-
  strength fields, never from the mission's self-reported result --
  the SAME 'ground truth, not self-report' discipline
  `cementmill.registry/cement-batch-strength-out-of-range?` uses for
  the independent ship-time recheck (both are grounded in the same
  28-day-compressive-strength acceptance band, unlike
  `automotive.robotics`/`automotive.registry`'s two DISTINCT fields
  [structural-deviation for the CAE mission, emissions-deviation for
  dispatch] -- deliberate here, because 28-day compressive strength IS
  the single most standard cement QC/acceptance metric, and it is
  literally what the compressive-strength-test-press step of THIS
  mission measures). `cementmill.governor`'s
  `robotics-simulation-violations` calls this ns's independent
  recheck, never the stored :passed? value, before any
  `:actuation/ship-cement-batch` proposal may commit.

  Pure data + pure functions -- no real robot I/O, no network.
  `kotoba.robotics` is itself \"policy, not control\"; this namespace
  simulates what a real robot quality-lab cell would report,
  deterministically, from the batch's own recorded fields, so tests
  and the demo run offline exactly like every other sibling namespace
  in this actor."
  (:require [kotoba.robotics :as robotics]))

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
  "Ground-truth check: does `batch`'s own recorded
  :strength-28d-actual fall outside its own recorded
  [:strength-28d-min :strength-28d-max] acceptance-band bounds? Needs
  no mission run or proposal inspection -- its inputs are permanent
  fields already on the batch, the same shape
  `cementmill.registry/cement-batch-strength-out-of-range?` uses."
  [{:keys [strength-28d-actual strength-28d-min strength-28d-max]}]
  (and (number? strength-28d-actual) (number? strength-28d-min) (number? strength-28d-max)
       (or (< strength-28d-actual strength-28d-min)
           (> strength-28d-actual strength-28d-max))))

(defn simulate-quality-lab-cell
  "Run the robot quality-lab verification mission for `batch-id`
  (`batch` is the full cement-batch record, incl. strength-28d-*
  fields). Returns {:mission .. :actions [{:action .. :proof ..} ..]
  :passed? bool}. Deterministic: :passed? is derived from the batch's
  OWN recorded 28-day compressive-strength fields via
  `strength-tolerance-out-of-range?`, never invented or randomized --
  `kotoba.robotics` mandates no network/IO, and a repeatable
  simulation is what makes the governor's independent recheck
  (`simulation-out-of-tolerance?`) meaningful."
  [batch-id batch]
  (let [out-of-range? (strength-tolerance-out-of-range? batch)
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
     :passed? (not out-of-range?)}))

(defn simulation-out-of-tolerance?
  "Independent ground-truth recheck for the governor: does `batch`'s
  OWN current 28-day compressive-strength fields fall out of range
  right now? Ignores whatever :passed? verdict a prior mission run
  stored -- identical in spirit to
  `cementmill.registry/cement-batch-strength-out-of-range?`'s refusal
  to trust a proposal's self-report."
  [batch]
  (strength-tolerance-out-of-range? batch))
