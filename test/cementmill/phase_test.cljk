(ns cementmill.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:actuation/ship-cement-batch`/`:actuation/issue-mill-
  certificate` must NEVER be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [cementmill.phase :as phase]))

(deftest ship-cement-batch-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real cement-batch shipment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/ship-cement-batch))
          (str "phase " n " must not auto-commit :actuation/ship-cement-batch")))))

(deftest issue-mill-certificate-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real Mill Test Certificate"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/issue-mill-certificate))
          (str "phase " n " must not auto-commit :actuation/issue-mill-certificate")))))

(deftest kiln-emissions-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling screening op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :kiln-emissions/screen))
          (str "phase " n " must not auto-commit :kiln-emissions/screen")))))

(deftest robotics-simulate-quality-lab-cell-never-auto-at-any-phase
  (testing "the robot quality-lab verification mission carries no direct capital risk, but is still never auto-eligible, matching every sibling verification op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :robotics/simulate-quality-lab-cell))
          (str "phase " n " must not auto-commit :robotics/simulate-quality-lab-cell")))))

(deftest robotics-simulate-quality-lab-cell-enabled-from-phase-2
  (is (contains? (:writes (get phase/phases 2)) :robotics/simulate-quality-lab-cell))
  (is (contains? (:writes (get phase/phases 3)) :robotics/simulate-quality-lab-cell))
  (is (not (contains? (:writes (get phase/phases 1)) :robotics/simulate-quality-lab-cell))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":cement-batch/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:cement-batch/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :cement-batch/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/ship-cement-batch} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/issue-mill-certificate} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :cement-batch/intake} :commit)))))
