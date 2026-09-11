(ns cementmill.motionplan-test
  "cementmill.motionplan/motion-plan-for -- the Cartesian waypoint list
  built from cementmill.robotics/mission-actions's real 3-step
  sequence (ADR-2607996500)."
  (:require [clojure.test :refer [deftest is testing]]
            [cementmill.cad :as cad]
            [cementmill.motionplan :as motionplan]
            [cementmill.robotics :as robotics]))

(deftest one-waypoint-per-mission-action-same-order
  (let [plan (motionplan/motion-plan-for {:press-platen-mass-kg 12.0})]
    (is (= (count robotics/mission-actions) (count plan)))
    (is (= (mapv :step robotics/mission-actions) (mapv :step plan)))
    (is (= [1 2 3] (mapv :seq plan)))
    (is (= ["cube-specimen-sampling" "compressive-strength-press-test" "blaine-fineness-scan"]
           (mapv :station plan)))))

(deftest waypoints-are-spaced-along-the-travel-axis
  (let [plan (motionplan/motion-plan-for {:press-platen-mass-kg 12.0})
        xs (mapv #(first (:waypoint %)) plan)]
    (is (= [0.0 motionplan/station-pitch-m (* 2 motionplan/station-pitch-m)] xs))
    (is (every? #(= motionplan/default-tool-orientation (:tool-orientation %)) plan))
    (is (every? #(zero? (second (:waypoint %))) plan) "y is the line centerline")))

(deftest working-height-derives-from-the-batchs-real-envelope
  (testing "z (working height) is half the batch's own real envelope height"
    (let [batch {:press-platen-mass-kg 12.0 :specimen-side-mm 40.0}
          plan (motionplan/motion-plan-for batch)
          z (nth (:waypoint (first plan)) 2)]
      (is (= (/ 40.0 2000.0) z))))
  (testing "a batch with no real :specimen-side-mm still gets a real
            answer via cementmill.cad's own disclosed default, not
            motionplan's separate fallback"
    (let [plan (motionplan/motion-plan-for {:press-platen-mass-kg 12.0})
          z (nth (:waypoint (first plan)) 2)]
      (is (= (/ cad/default-specimen-side-mm 2000.0) z))))
  (testing "no batch at all (older/hand-rolled caller) -> motionplan's own default-working-height-m"
    (let [plan (motionplan/motion-plan-for)
          z (nth (:waypoint (first plan)) 2)]
      (is (= motionplan/default-working-height-m z)))))

(deftest deterministic-same-batch-same-plan
  (is (= (motionplan/motion-plan-for {:press-platen-mass-kg 12.0 :specimen-side-mm 45.0})
         (motionplan/motion-plan-for {:press-platen-mass-kg 12.0 :specimen-side-mm 45.0}))))
