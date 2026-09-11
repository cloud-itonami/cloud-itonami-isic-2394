(ns cementmill.scene-test
  "cementmill.scene's bridge from cementmill.cad's tessellated
  cube-specimen envelope + cementmill.robotics/simulate-press's
  trajectory into kami.webgpu.mesh's real input shape, asserted for
  well-formedness -- no browser/WebGPU device is available in this
  JVM/.cljc actor repo (see cementmill.scene's docstring). Direct port
  of autoparts.scene-test's/fab.scene-test's own assertions, adapted
  to a plain cement-batch map."
  (:require [clojure.test :refer [deftest is testing]]
            [cementmill.robotics :as robotics]
            [cementmill.scene :as scene]))

(def ^:private sample-batch
  {:id "batch-scene-test" :press-platen-mass-kg 12.0 :specimen-side-mm 48.5})

(deftest mesh-data-is-well-formed
  (testing "positions/normals/indices satisfy kami.webgpu.mesh/upload-mesh!'s
            real contract: same-length positions/normals, index count a
            multiple of 3, every index within the vertex range"
    (let [{:keys [positions normals indices vertex-count index-count]} (scene/scene-for sample-batch)]
      (is (pos? vertex-count))
      (is (pos? index-count))
      (is (= (count positions) vertex-count))
      (is (= (count normals) vertex-count)
          "upload-mesh! requires one normal per vertex, not optional like uvs/skin/morph")
      (is (= (count indices) index-count))
      (is (zero? (mod index-count 3)))
      (is (every? #(<= 0 % (dec vertex-count)) indices)
          "every index must reference a valid vertex")
      (is (every? #(= 3 (count %)) positions) "positions are [x y z]")
      (is (every? #(= 3 (count %)) normals) "normals are [x y z]")
      (is (every? (fn [n] (< (Math/abs (- 1.0 (Math/sqrt (reduce + (map * n n))))) 1e-6)) normals)
          "every normal must actually be unit-length"))))

(deftest one-frame-per-simulated-tick
  (testing "one :transform per cementmill.robotics/simulate-press trajectory tick"
    (let [sim (robotics/simulate-press sample-batch)
          sc (scene/scene-for sample-batch)]
      (is (= (:ticks sim) (count (:frames sc))))
      (is (every? #(= 3 (count (get-in % [:transform :translation]))) (:frames sc)))
      (is (every? #(= [0.0 0.0 0.0] (get-in % [:transform :rotation])) (:frames sc))
          "physics-2d has no orientation state -- every frame's rotation is identity, honestly")
      (is (every? #(= [1.0 1.0 1.0] (get-in % [:transform :scale])) (:frames sc)))
      ;; translations move: the scene isn't rendering a frozen frame.
      (is (not= (get-in (first (:frames sc)) [:transform :translation])
                (get-in (last (:frames sc)) [:transform :translation]))))))

(deftest mesh-is-unit-converted-to-meters-and-already-centered-in-xy
  (testing "the mesh's XY footprint extent (now in METERS, matching
            cementmill.robotics's trajectory units) still matches the real
            envelope-dims-mm side length (converted mm->m) on BOTH axes; X/Y
            are naturally centered on the local origin already (cementmill.
            cad's +/-0.5-unit-square sketch convention -- see cementmill.
            scene's docstring)"
    (let [{:keys [positions dims]} (scene/scene-for sample-batch)
          extent (fn [axis] (- (apply max (map #(nth % axis) positions))
                                (apply min (map #(nth % axis) positions))))]
      (is (< (Math/abs (- (extent 0) (/ (:length-mm dims) 1000.0))) 1e-6))
      (is (< (Math/abs (- (extent 1) (/ (:width-mm dims) 1000.0))) 1e-6))
      ;; centered: min/max along X (and Y) are symmetric around 0.
      (is (< (Math/abs (+ (apply min (map #(nth % 0) positions))
                          (apply max (map #(nth % 0) positions))))
             1e-6)))))

(deftest scene-for-accepts-a-bare-mass-number-too
  (testing "simulate-press's legacy bare-mass calling convention (a plain
            number, no batch map at all) also works through scene-for --
            cementmill.cad/envelope-dims-mm destructures a non-map value to
            all-nil, falling back to its disclosed default, and
            cementmill.robotics/simulate-press normalizes the same bare
            number internally -- but a real batch map is the documented,
            preferred way to get a genuinely per-batch envelope"
    (let [sc (scene/scene-for 12.0)]
      (is (pos? (:vertex-count sc)))
      (is (pos? (:index-count sc))))))

(deftest frames-genuinely-shift-with-real-specimen-geometry
  (testing "unlike fab.scene (fixed coordinate origin, geometry-invariant
            :frames), cementmill.scene's :frames DO genuinely shift with a
            batch's real :specimen-side-mm -- verified here, matching
            autoparts.scene's own finding, not fab.scene's, for this
            vertical's own reason (see cementmill.scene's docstring, 'A
            THIRD, cementmill-specific property')"
    (let [small (scene/scene-for {:press-platen-mass-kg 12.0 :specimen-side-mm 20.0})
          large (scene/scene-for {:press-platen-mass-kg 12.0 :specimen-side-mm 100.0})]
      (is (not= (get-in (first (:frames small)) [:transform :translation])
                (get-in (first (:frames large)) [:transform :translation]))
          "the platen's initial rendered position genuinely shifts with the
           real cube-specimen envelope size"))))
