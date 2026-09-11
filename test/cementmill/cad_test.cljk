(ns cementmill.cad-test
  "cementmill.cad's real BREP cube-specimen envelope bridge
  (ADR-2607996500) -- envelope-dims-mm's real-vs-default fallback
  discipline, and envelope-solid/envelope-mesh's genuine tessellation
  output. Direct port of autoparts.cad-test's/fab.cad-test's
  assertions, adapted to this ns's own single `:specimen-side-mm`
  field (a cube has one degree of freedom, not an independent
  length/width/height triple -- see cementmill.cad's own docstring for
  why)."
  (:require [clojure.test :refer [deftest is testing]]
            [cementmill.cad :as cad]
            [cementmill.robotics :as robotics]))

(deftest envelope-dims-mm-falls-back-to-disclosed-default-when-absent
  (testing "a batch with no :specimen-side-mm gets the disclosed
            ASTM-nominal default, on all three keys equally (a cube)"
    (is (= {:length-mm cad/default-specimen-side-mm
            :width-mm cad/default-specimen-side-mm
            :height-mm cad/default-specimen-side-mm}
           (cad/envelope-dims-mm {:id "batch-x" :press-platen-mass-kg 12.0}))))
  (testing "nil batch also falls back cleanly"
    (is (= {:length-mm cad/default-specimen-side-mm
            :width-mm cad/default-specimen-side-mm
            :height-mm cad/default-specimen-side-mm}
           (cad/envelope-dims-mm nil)))))

(deftest envelope-dims-mm-uses-a-batchs-own-real-measurement-when-present
  (testing "an explicit :specimen-side-mm overrides the default on ALL
            THREE keys equally -- this test article is a cube, by the
            ASTM C109/C109M test method's own definition, not an
            independent length/width/height triple"
    (is (= {:length-mm 48.5 :width-mm 48.5 :height-mm 48.5}
           (cad/envelope-dims-mm {:specimen-side-mm 48.5})))))

(deftest default-specimen-side-reproduces-robotics-prior-fixed-constant
  (testing "the disclosed fallback default is DEFINED to exactly
            reproduce cementmill.robotics's pre-ADR-2607996500
            specimen-side-mm figure (the real ASTM C109/C109M 50 mm
            cube), so a batch with nothing on file behaves identically
            to this actor's behavior before this ADR"
    (is (= robotics/specimen-side-mm cad/default-specimen-side-mm))
    (is (= robotics/specimen-half-w-m (/ cad/default-specimen-side-mm 2000.0)))
    (is (= robotics/specimen-half-h-m (/ cad/default-specimen-side-mm 2000.0)))))

(deftest envelope-solid-produces-real-tessellatable-geometry
  (let [{:keys [dims] :as solid} (cad/envelope-solid {:specimen-side-mm 48.5})]
    (is (= {:length-mm 48.5 :width-mm 48.5 :height-mm 48.5} dims))
    (is (seq (:vertices solid)))
    (is (seq (:edges solid)))
    (testing "the tessellated footprint's X/Y extent matches the requested
              side length (mm) on BOTH axes -- a genuine square footprint"
      (let [{:keys [positions]} (cad/envelope-mesh solid)
            extent (fn [axis] (- (apply max (map #(nth % axis) positions))
                                  (apply min (map #(nth % axis) positions))))]
        (is (< (Math/abs (- (extent 0) 48.5)) 1e-6))
        (is (< (Math/abs (- (extent 1) 48.5)) 1e-6))
        (is (< (Math/abs (- (extent 0) (extent 1))) 1e-9)
            "a cube's footprint is square: X extent == Y extent")))))

(deftest envelope-mesh-is-well-formed
  (let [solid (cad/envelope-solid {:specimen-side-mm 50.0})
        {:keys [positions indices]} (cad/envelope-mesh solid)]
    (is (pos? (count positions)))
    (is (pos? (count indices)))
    (is (zero? (mod (count indices) 3)) "indices are complete triangles")
    (is (every? #(<= 0 % (dec (count positions))) indices)
        "every index references a valid vertex")
    (is (every? #(= 3 (count %)) positions) "positions are [x y z]")))

(deftest envelope-dims-mm-vary-per-batch
  (testing "two batches with different real measured cube dimensions get
            genuinely different envelopes -- this is not a fixed constant
            dressed up as per-batch data"
    (is (not= (cad/envelope-dims-mm {:specimen-side-mm 45.0})
              (cad/envelope-dims-mm {:specimen-side-mm 55.0})))))
