(ns cementmill.motionplan
  "Extends `cementmill.robotics/mission-actions` -- the 3-step cube-
  specimen-sampling / compressive-strength-press-test / Blaine-
  fineness-scan robot quality-lab mission every cement batch already
  runs (`cementmill.robotics/simulate-quality-lab-cell`) -- into an
  actual ordered list of Cartesian waypoints, one per mission action,
  walking the SAME action order the real mission already commits to
  the audit ledger (ADR-2607996500 -- direct port of `autoparts.
  motionplan`'s reference pattern, ADR-2607160000, and `fab.
  motionplan`'s, ADR-2607992500, to cementmill's own case).

  APPLIES CLEANLY, verified (not assumed) -- disclosed here because
  ADR-2607996500 explicitly directed checking this rather than forcing
  the abstraction: `cementmill.robotics/mission-actions` is ALREADY a
  fixed, ordered 3-step list (`:cube-specimen-sampling`,
  `:compressive-strength-press-test`, `:blaine-fineness-scan`) run at a
  single `:robot/quality-lab-cell-1` station, the exact same shape
  `autoparts.motionplan`/`fab.motionplan` already extend for their own
  verticals' 3-step missions -- no rethinking or scoping-down was
  needed to port this ns to cementmill (unlike, potentially, a vertical
  whose real process shape has no comparable fixed per-record action
  sequence at all).

  Honest scope, HONEST DESIGN CHOICE disclosed (mirrors `cementmill.
  cad`/`cementmill.robotics`'s own disclosed choices, and `autoparts.
  motionplan`/`fab.motionplan`'s before them): `vdesign.motionplan`
  extends `vdesign.process/plan`'s real multi-station BOM + 4D
  assembly-order sequence (the giemon-factory `construction.order.json
  :seq` pattern) -- but THIS repo has no multi-station BOM/assembly-
  order system at all, and ADR-2607160000 (which this ADR follows)
  explicitly directs NOT inventing one just to mirror automotive's
  shape. Instead this ns reuses `cementmill.robotics/mission-actions`'s
  existing, REAL 3-step list AS the station sequence -- the same 3
  actions `simulate-quality-lab-cell` already runs and records, walked
  in the same order, never a new invented process model.

  This is a WAYPOINT LIST -- a plausible, honestly simplified layout
  (mission actions placed at a fixed pitch along a straight line,
  working height derived from the batch's own real cube-specimen
  envelope dims via `cementmill.cad`) -- NOT an inverse-kinematics
  solver, NOT a trajectory optimizer, and it does not drive any real
  robot controller. `:tool-orientation` is a fixed 'straight down'
  approach vector, not a solved end-effector pose.

  `:station` is each action's own `:step` keyword name (as a string):
  this actor's data model has no separate station-naming concept the
  way `vdesign.process/plan`'s multi-station BOM does (every action
  runs at/near the SAME `:robot/quality-lab-cell-1`, see `cementmill.
  robotics/simulate-quality-lab-cell`), so the mission step honestly
  doubles as its own station identity rather than inventing station
  names this actor's data has never had. Spacing the 3 actions along a
  line by `station-pitch-m` is the SAME simplifying convention
  `vdesign.motionplan`/`autoparts.motionplan`/`fab.motionplan` use for
  their own multi-/single-station layouts, reused here even though this
  actor's own actions likely run at or near one physical QC-lab cell --
  disclosed, not hidden."
  (:require [cementmill.cad :as cad]
            [cementmill.robotics :as robotics]))

(def ^:const station-pitch-m
  "Nominal spacing between adjacent mission-action waypoints (m) -- a
  plausible, round figure, honestly NOT derived from any real
  quality-lab cell's actual layout (mirrors `autoparts.motionplan`/
  `fab.motionplan`'s own `station-pitch-m`, reused verbatim here at the
  same 1.5 m plausible single-cell scale)."
  1.5)

(def ^:const default-tool-orientation
  "Fixed straight-down tool-approach vector -- NOT a solved end-
  effector orientation (this namespace is not an IK solver; mirrors
  `autoparts.motionplan`/`fab.motionplan`'s `default-tool-
  orientation`)."
  [0.0 0.0 -1.0])

(def ^:const default-working-height-m
  "Fallback working height (m) when `motion-plan-for` is called with no
  batch at all (mirrors `autoparts.motionplan`/`fab.motionplan`'s
  `default-working-height-m`)."
  0.75)

(defn- working-height-m
  "Half the batch's own real tessellated cube-specimen-envelope height
  (`cementmill.cad/envelope-dims-mm`) -- a plausible fixed working
  height for every action, not a per-action solved height. Falls back
  to `default-working-height-m` only when `batch` itself is nil (an
  older/hand-rolled caller with nothing to read at all); a batch with
  no real `:specimen-side-mm` still gets a real answer via `cementmill.
  cad`'s own disclosed default."
  [batch]
  (if batch
    (/ (:height-mm (cad/envelope-dims-mm batch)) 2000.0)
    default-working-height-m))

(defn motion-plan-for
  "Ordered Cartesian waypoint list, one per `cementmill.robotics/
  mission-actions` entry (same order, same `:step` names):

    [{:seq :step :station :waypoint [x y z] :tool-orientation [dx dy dz]} ...]

  x = (action-index) * `station-pitch-m`; y = 0 (line centerline); z =
  `working-height-m`. `:seq` is 1-based (first action = seq 1).
  Deterministic: the same `batch` always produces the same plan --
  `cementmill.robotics/mission-actions` is itself a fixed list and no
  randomness is introduced here."
  [& [batch]]
  (let [z (working-height-m batch)]
    (mapv (fn [i {:keys [step]}]
            {:seq (inc i) :step step :station (name step)
             :waypoint [(* i station-pitch-m) 0.0 z]
             :tool-orientation default-tool-orientation})
          (range (count robotics/mission-actions))
          robotics/mission-actions)))
