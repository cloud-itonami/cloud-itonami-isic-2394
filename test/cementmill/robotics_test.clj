(ns cementmill.robotics-test
  "`cementmill.robotics`'s `physics-2d`-backed time-stepped press
  simulation, exercised directly (ADR-2607152000) -- the cement-mill
  analog of `kami-engine-vehicle-designer`'s `vdesign.simphysics-test`
  (ADR-2607151600)."
  (:require [clojure.test :refer [deftest is testing]]
            [cementmill.robotics :as robotics]))

(deftest press-trajectory-actually-evolves
  (testing "the trajectory is a real per-tick simulation output, not a
            no-op -- position and velocity both change across ticks,
            and the platen settles (sheds essentially all its closing
            velocity by the last tick)"
    (let [{:keys [trajectory ticks]} (robotics/simulate-press 12.0)
          first-t (first trajectory)
          last-t (last trajectory)]
      (is (> ticks 1))
      (is (= ticks (count trajectory)))
      (is (not= (:position first-t) (:position last-t))
          "the platen body must actually move over the simulated ticks")
      (is (not= (:velocity first-t) (:velocity last-t))
          "the platen body's velocity must actually change (it starts
           at the closing velocity and must decelerate on contact)")
      (is (< (Math/abs (first (:velocity last-t))) 1.0e-9)
          "by the last tick the platen has shed essentially all its
           closing velocity -- contact + settling actually happened"))))

(deftest heavier-platen-shows-higher-force
  (testing "platen MASS is the lever that moves :sim-peak-compressive-
            force-n/:sim-peak-compressive-stress-mpa in this model (see
            namespace docstring for why the peak DECELERATION,
            colliding with an immovable mass-0 specimen, provably does
            not depend on mass) -- a genuinely heavier press-platen-
            mass-kg configuration must show a genuinely higher
            simulated force, exactly proportional to mass since decel
            is fixed"
    (let [light (robotics/simulate-press 7.0)
          heavy (robotics/simulate-press 14.0)]
      (is (> (:sim-peak-compressive-force-n heavy) (:sim-peak-compressive-force-n light)))
      (is (> (:sim-peak-compressive-stress-mpa heavy) (:sim-peak-compressive-stress-mpa light)))
      (is (< (Math/abs (- (:sim-peak-compressive-stress-mpa heavy)
                          (* 2.0 (:sim-peak-compressive-stress-mpa light))))
             1.0e-6)
          "14.0kg is exactly 2x 7.0kg, and force/stress is exactly proportional to mass"))))

(deftest platen-mass-alone-does-not-change-peak-decel
  (testing "documented, verified finding (namespace docstring): colliding
            with a mass-0 (immovable) cube specimen, physics-2d's
            impulse resolution is independent of the moving platen's
            own mass -- doubling press-platen-mass-kg at the SAME
            closing velocity/crush-travel produces the SAME peak
            deceleration, not a fabricated heavier-implies-higher-decel
            relationship (only the FORCE, force = mass x decel, differs)"
    (let [a (robotics/simulate-press 12.0)
          b (robotics/simulate-press 24.0)
          decel-a (/ (:sim-peak-compressive-force-n a) 12.0)
          decel-b (/ (:sim-peak-compressive-force-n b) 24.0)]
      (is (< (Math/abs (- decel-a decel-b)) 1.0e-6)))))

(deftest press-telemetry-for-is-deterministic
  (testing "press-telemetry-for is pure -- the same :press-platen-mass-kg
            always reproduces the same telemetry, no IO/randomness"
    (let [batch {:press-platen-mass-kg 12.0}
          t1 (robotics/press-telemetry-for batch)
          t2 (robotics/press-telemetry-for batch)]
      (is (= t1 t2)))))

;; ----------------------------- strength-tolerance-out-of-range? -----------------------------

(deftest not-out-of-range-when-simulated-stress-within-bounds
  (is (not (robotics/strength-tolerance-out-of-range?
            {:sim-peak-compressive-stress-mpa 48.0 :strength-28d-min 42.5 :strength-28d-max 62.5})))
  (is (not (robotics/strength-tolerance-out-of-range?
            {:sim-peak-compressive-stress-mpa 42.5 :strength-28d-min 42.5 :strength-28d-max 62.5})))
  (is (not (robotics/strength-tolerance-out-of-range?
            {:sim-peak-compressive-stress-mpa 62.5 :strength-28d-min 42.5 :strength-28d-max 62.5}))))

(deftest out-of-range-when-simulated-stress-below-minimum-or-above-maximum
  (is (robotics/strength-tolerance-out-of-range?
       {:sim-peak-compressive-stress-mpa 28.0 :strength-28d-min 42.5 :strength-28d-max 62.5}))
  (is (robotics/strength-tolerance-out-of-range?
       {:sim-peak-compressive-stress-mpa 66.0 :strength-28d-min 42.5 :strength-28d-max 62.5})))

;; ----------------------------- fixture sanity (mirrors cementmill.store/demo-data) -----------------------------

(deftest demo-fixture-press-masses-produce-the-expected-verdicts
  (testing "the SAME per-batch press-platen-mass-kg values cementmill.store/demo-data
            seeds (12.0/12.0/7.0/14.0/16.5 for batch-1..5) actually simulate to the
            expected in-/out-of-tolerance verdict against each batch's own real
            [:strength-28d-min :strength-28d-max] band -- this is what makes the
            demo/governor fixtures genuinely simphysics-derived, not hand-set"
    (let [band {:strength-28d-min 42.5 :strength-28d-max 62.5}]
      (is (not (robotics/strength-tolerance-out-of-range?
                (merge band (robotics/press-telemetry-for {:press-platen-mass-kg 12.0}))))
          "batch-1/2's 12.0kg press config clears [42.5,62.5]")
      (is (robotics/strength-tolerance-out-of-range?
           (merge band (robotics/press-telemetry-for {:press-platen-mass-kg 7.0})))
          "batch-3's 7.0kg press config falls below [42.5,62.5] (consistent with its
           already-known out-of-spec 30.0 strength-28d-actual)")
      (is (robotics/strength-tolerance-out-of-range?
           (merge band (robotics/press-telemetry-for {:press-platen-mass-kg 16.5})))
          "batch-5's deliberately misconfigured 16.5kg press config genuinely exceeds
           [42.5,62.5] on independent recheck"))
    (is (not (robotics/strength-tolerance-out-of-range?
              (merge {:strength-28d-min 52.5 :strength-28d-max 72.5}
                     (robotics/press-telemetry-for {:press-platen-mass-kg 14.0}))))
        "batch-4's 14.0kg press config clears its own [52.5,72.5] band")))
