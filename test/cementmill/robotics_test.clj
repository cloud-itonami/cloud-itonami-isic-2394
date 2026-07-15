(ns cementmill.robotics-test
  "`cementmill.robotics`'s `physics-2d`-backed time-stepped press
  simulation, exercised directly (ADR-2607152000) -- the cement-mill
  analog of `kami-engine-vehicle-designer`'s `vdesign.simphysics-test`
  (ADR-2607151600). Also covers `cementmill.cad`'s real BREP bridge
  into the STATIC `:cube-specimen` body's AABB (ADR-2607996500),
  including the cementmill-specific GEOMETRY-INVARIANCE findings
  disclosed in this ns's own docstring -- verified below, not just
  asserted in prose, and genuinely DIFFERENT in shape from BOTH prior
  digital-twin ports (`autoparts.robotics-test`, `fab.simphysics-
  test`): (1) `:sim-peak-compressive-force-n`/`:sim-peak-compressive-
  stress-mpa` ARE exactly geometry-invariant, same class of finding as
  both priors; (2) UNLIKE both priors, `:ticks` genuinely GROWS with
  specimen size here (a real, pre-existing, non-floating-point property
  of this ns's own `approach-m` formula); (3) `:sim-peak-crush-
  distance-m` and the post-collision `:trajectory` segment CAN diverge
  for sufficiently different specimen sizes -- a real, verified IEEE-
  754 floating-point knife-edge in exactly which tick first detects
  contact, the SAME CLASS of finding `fab.simphysics-test` disclosed,
  empirically located here at a real threshold (~150mm side) rather
  than scattered unpredictably, and always bounded in magnitude (never
  exceeding roughly one tick-step's own travel distance, `press-
  closing-velocity-mps * dt`)."
  (:require [clojure.test :refer [deftest is testing]]
            [cementmill.cad :as cad]
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

;; ----------------------- ADR-2607996500 CAD-derived geometry -----------------------

(deftest batch-with-no-specimen-field-is-unchanged-from-pre-adr-2607996500-behavior
  (testing "a batch with only :press-platen-mass-kg (no real measured cube
            dimension on file) produces IDENTICAL numeric results to the
            bare-number call with the same mass -- cementmill.cad's
            disclosed default closes the gap transparently, no behavior
            change for batches with nothing on file"
    (let [bare (robotics/simulate-press 12.0)
          via-map (robotics/simulate-press {:id "batch-x" :press-platen-mass-kg 12.0})
          explicit (robotics/simulate-press {:press-platen-mass-kg 12.0
                                              :specimen-side-mm cad/default-specimen-side-mm})]
      (is (= (:sim-peak-compressive-force-n bare) (:sim-peak-compressive-force-n via-map)))
      (is (= (:sim-peak-compressive-stress-mpa bare) (:sim-peak-compressive-stress-mpa via-map)))
      (is (= (:ticks bare) (:ticks via-map)))
      (is (= (:trajectory bare) (:trajectory via-map))
          "identical geometry (both fall back to cementmill.cad's default) -> identical trajectory")
      (is (= bare explicit)
          "an explicit :specimen-side-mm equal to the disclosed default reproduces
           the bare-number call bit-for-bit -- no floating-point divergence for
           the default case itself")
      (is (< (Math/abs (- robotics/specimen-half-w-m (:half-w (:specimen-half-extents-m bare)))) 1e-15))
      (is (< (Math/abs (- robotics/specimen-half-h-m (:half-h (:specimen-half-extents-m bare)))) 1e-15)))))

(deftest cad-derived-specimen-geometry-genuinely-changes-the-platens-placement
  (testing "two batches with the SAME :press-platen-mass-kg but DIFFERENT
            real :specimen-side-mm produce DIFFERENT :trajectory position
            values -- a genuine, non-cosmetic effect of cementmill.cad's
            real per-batch geometry, matching autoparts.robotics's own
            finding (NOT fab.simphysics's, where the moving body's fixed
            coordinate origin makes CAD geometry invisible in :trajectory)
            -- because here the MOVING :press-platen is placed relative to
            the CAD-derived STATIC :cube-specimen, see ns docstring"
    (let [small (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 20.0})
          large (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 100.0})
          pos0 (fn [r] (:position (first (:trajectory r))))]
      (is (not= (:specimen-half-extents-m small) (:specimen-half-extents-m large)))
      (is (not= (pos0 small) (pos0 large))
          "the platen's initial position genuinely shifts with the real cube-specimen envelope size")
      (is (< (first (pos0 large)) (first (pos0 small)))
          "a larger cube specimen pushes the platen's start position further out (more
           negative) along the travel axis -- platen-x = specimen-x - half-w -
           platen-half-w-m - gap-m, so a bigger half-w moves the start further left"))))

(deftest cad-derived-geometry-does-not-change-the-force-reading-disclosed-invariant
  (testing "simulate-press's own documented geometry-invariance: peak
            compressive force/stress are driven by press-platen-mass-kg
            (and the fixed closing-velocity/crush-travel constants) --
            NEVER by the cube-specimen envelope's outer size -- verified
            here across genuinely different specimen sizes that stay in
            the same collision-detection-tick regime (see the knife-edge
            test below for what happens once that regime boundary is
            crossed), so a future change that breaks this real property
            of the 'boxcar' collision technique is caught"
    (let [small (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 20.0})
          large (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 100.0})]
      (is (= (:sim-peak-compressive-force-n small) (:sim-peak-compressive-force-n large)))
      (is (= (:sim-peak-compressive-stress-mpa small) (:sim-peak-compressive-stress-mpa large)))
      (is (= (:dt small) (:dt large)))
      (is (< (Math/abs (- (:sim-peak-crush-distance-m small) (:sim-peak-crush-distance-m large))) 1e-9)
          "both settle to essentially zero residual crush distance in this regime"))))

(deftest ticks-genuinely-grows-with-specimen-size-unlike-either-prior-port
  (testing "a REAL, VERIFIED divergence from BOTH autoparts.robotics-test
            and fab.simphysics-test (disclosed in this ns's own docstring,
            not smoothed over): unlike either prior port, where the tick-
            COUNT formula excludes half-w entirely (so :ticks is itself
            geometry-invariant there), THIS ns's pre-existing approach-m =
            gap-m + platen-half-w-m + half-w DOES include half-w, so
            :ticks genuinely grows with a larger CAD-derived specimen --
            verified with real numbers here, not assumed identical to
            either prior vertical"
    (let [small (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 20.0})
          large (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 100.0})]
      (is (< (:ticks small) (:ticks large))
          "a larger specimen's own half-width inflates approach-m, and hence :ticks")
      ;; but the extra/fewer trailing ticks are all already-settled,
      ;; ~zero-velocity samples appended after the real collision peak --
      ;; so the peak reading itself is untouched by this (see the
      ;; invariance test above).
      (is (= (:sim-peak-compressive-force-n small) (:sim-peak-compressive-force-n large))))))

(deftest crush-distance-can-diverge-across-a-real-floating-point-collision-tick-knife-edge
  (testing "a REAL, VERIFIED finding this ns's own docstring discloses
            (empirically located, not assumed): once half-w genuinely
            varies per batch (this ADR), IEEE-754 rounding of the half-w-
            involving platen-x/contact-plane-x sums can tip which TICK
            first detects the platen/specimen collision -- verified here
            with a batch pair straddling the real threshold this repo's
            own constants produce (~150mm side; a 300mm cube specimen
            genuinely triggers a one-tick-later contact detection than a
            100mm one, at these fixed press-closing-velocity-mps/dt/gap-m
            constants). The PRACTICAL consequence, same class of finding
            as fab.simphysics-test's own disclosed post-collision
            divergence, but manifesting here in :sim-peak-crush-distance-m
            (a field fab.simphysics/autoparts.robotics do not compute at
            all): the peak transient AABB overlap observed differs
            between the two batches, though ALWAYS bounded by roughly one
            tick-step's own travel distance (press-closing-velocity-mps *
            dt = 1.0e-4 m here), never unbounded drift. What DOES still
            hold, verified: :sim-peak-compressive-force-n/:sim-peak-
            compressive-stress-mpa remain exactly invariant regardless
            (the collision-tick shift changes WHEN/how-much transient
            overlap is sampled, never the peak velocity-change magnitude
            itself, which is fixed by dt/closing-velocity alone)"
    (let [near (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 100.0})
          far (robotics/simulate-press {:press-platen-mass-kg 12.0 :specimen-side-mm 300.0})]
      (is (not= (:sim-peak-crush-distance-m near) (:sim-peak-crush-distance-m far))
          "documents the real divergence this ns's docstring discloses -- if this
           assertion ever starts failing (the two values become identical), that
           is GOOD news, not a regression: a future engine/placement change may
           have closed this floating-point gap, and this test should be updated
           to say so")
      (is (< (:sim-peak-crush-distance-m near) 1e-9)
          "the 100mm batch settles to essentially zero residual crush distance")
      (is (< (:sim-peak-crush-distance-m far) 1.0e-4)
          "the 300mm batch's crush distance, though nonzero, stays bounded by
           roughly one tick-step's own travel distance -- not unbounded drift")
      (is (not= (:trajectory near) (:trajectory far))
          "confirms the geometry genuinely IS different -- a real finding about
           a real divergence, not two identical inputs")
      (is (= (:sim-peak-compressive-force-n near) (:sim-peak-compressive-force-n far))
          "the disclosed force invariant holds regardless of which side of the
           collision-tick knife-edge a batch's geometry falls on")
      (is (= (:sim-peak-compressive-stress-mpa near) (:sim-peak-compressive-stress-mpa far))))))

(deftest platen-mass-still-scales-force-independent-of-specimen-geometry
  (testing "mass legitimately scales the force reading even when specimen
            geometry is held fixed -- the two effects (mass -> force,
            geometry -> trajectory position/ticks) are orthogonal, as
            documented"
    (let [light (robotics/simulate-press {:press-platen-mass-kg 7.0 :specimen-side-mm 60.0})
          heavy (robotics/simulate-press {:press-platen-mass-kg 21.0 :specimen-side-mm 60.0})]
      (is (< (:sim-peak-compressive-force-n light) (:sim-peak-compressive-force-n heavy)))
      (is (< (Math/abs (- (:sim-peak-compressive-force-n heavy)
                          (* 3.0 (:sim-peak-compressive-force-n light))))
             1.0e-6)
          "21.0kg is exactly 3x 7.0kg, and force is exactly proportional to mass
           regardless of the (held-fixed) specimen geometry"))))

(deftest press-telemetry-for-uses-the-batchs-own-real-geometry
  (testing "press-telemetry-for now threads the FULL batch (not just
            :press-platen-mass-kg) into simulate-press, so a batch with
            real :specimen-side-mm on file gets a genuinely per-batch
            geometry reflected in its telemetry's own :specimen-half-
            extents-m, while the force/stress reading itself stays
            geometry-invariant per the disclosed contract above"
    (let [batch {:id "batch-x" :press-platen-mass-kg 12.0 :specimen-side-mm 30.0}
          telemetry (robotics/press-telemetry-for batch)
          {:keys [length-mm width-mm]} (cad/envelope-dims-mm batch)]
      (is (= {:half-w (/ length-mm 2000.0) :half-h (/ width-mm 2000.0)}
             (:specimen-half-extents-m telemetry)))
      (is (pos? (:sim-peak-compressive-force-n telemetry))))))
