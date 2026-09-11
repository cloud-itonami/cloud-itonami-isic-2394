(ns cementmill.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see
  `automotive.store-contract-test` (`cloud-itonami-isic-2910`) for the
  same pattern on the sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [cementmill.robotics :as robotics]
            [cementmill.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Ordinary Portland Cement Batch OPC-42.5N-104" (:batch-name (store/cement-batch s "batch-1"))))
      (is (= "JPN" (:jurisdiction (store/cement-batch s "batch-1"))))
      (is (= 47.0 (:strength-28d-actual (store/cement-batch s "batch-1"))))
      (is (= 42.5 (:strength-28d-min (store/cement-batch s "batch-1"))))
      (is (= 62.5 (:strength-28d-max (store/cement-batch s "batch-1"))))
      (is (false? (:kiln-emissions-unresolved? (store/cement-batch s "batch-1"))))
      (is (= 30.0 (:strength-28d-actual (store/cement-batch s "batch-3"))))
      (is (true? (:kiln-emissions-unresolved? (store/cement-batch s "batch-4"))))
      (is (false? (:robotics-sim-verified? (store/cement-batch s "batch-1"))) "no robotics mission has run yet")
      (is (true? (:robotics-sim-verified? (store/cement-batch s "batch-5"))) "seeded as already-on-file")
      (is (= 65.0 (:strength-28d-actual (store/cement-batch s "batch-5"))))
      (is (= 12.0 (:press-platen-mass-kg (store/cement-batch s "batch-1"))))
      (is (number? (:sim-peak-compressive-stress-mpa (store/cement-batch s "batch-1")))
          "real physics-2d press telemetry on file")
      (is (<= (:strength-28d-min (store/cement-batch s "batch-1"))
              (:sim-peak-compressive-stress-mpa (store/cement-batch s "batch-1"))
              (:strength-28d-max (store/cement-batch s "batch-1")))
          "batch-1's real simulated press reading clears its own real acceptance band")
      (is (= 16.5 (:press-platen-mass-kg (store/cement-batch s "batch-5")))
          "batch-5's press-run record uses a deliberately misconfigured (heavier) platen mass -- see cementmill.store/demo-data")
      (is (> (:sim-peak-compressive-stress-mpa (store/cement-batch s "batch-5"))
             (:strength-28d-max (store/cement-batch s "batch-5")))
          "batch-5's real simulated press reading genuinely exceeds its own real acceptance band")
      (is (= (:sim-peak-compressive-stress-mpa (store/cement-batch s "batch-5"))
             (:sim-peak-compressive-stress-mpa (robotics/press-telemetry-for (store/cement-batch s "batch-5"))))
          "the seeded telemetry is genuinely reproducible from the SAME press-telemetry-for call, never a hand-typed double")
      (is (false? (:batch-shipped? (store/cement-batch s "batch-1"))))
      (is (false? (:mill-certified? (store/cement-batch s "batch-1"))))
      (is (= ["batch-1" "batch-2" "batch-3" "batch-4" "batch-5"]
             (mapv :id (store/all-cement-batches s))))
      (is (nil? (store/kiln-emissions-screen-of s "batch-1")))
      (is (nil? (store/quality-standard-verification-of s "batch-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/shipment-history s)))
      (is (= [] (store/certificate-history s)))
      (is (zero? (store/next-shipment-sequence s "JPN")))
      (is (zero? (store/next-certificate-sequence s "JPN")))
      (is (false? (store/batch-already-shipped? s "batch-1")))
      (is (false? (store/batch-already-certified? s "batch-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :cement-batch/upsert
                                 :value {:id "batch-1" :batch-name "Ordinary Portland Cement Batch OPC-42.5N-104"}})
        (is (= "Ordinary Portland Cement Batch OPC-42.5N-104" (:batch-name (store/cement-batch s "batch-1"))))
        (is (= 47.0 (:strength-28d-actual (store/cement-batch s "batch-1"))) "unrelated field preserved"))
      (testing "robotics-sim result commits via :cement-batch/upsert and reads back"
        (store/commit-record! s {:effect :cement-batch/upsert
                                 :value {:id "batch-1" :robotics-sim-verified? true
                                        :robotics-sim-record {:mission-id "m-1" :passed? true}}})
        (is (true? (:robotics-sim-verified? (store/cement-batch s "batch-1"))))
        (is (= {:mission-id "m-1" :passed? true} (:robotics-sim-record (store/cement-batch s "batch-1"))))
        (is (= 47.0 (:strength-28d-actual (store/cement-batch s "batch-1"))) "unrelated field still preserved"))
      (testing "verification / kiln-emissions-screen payloads commit and read back"
        (store/commit-record! s {:effect :verification/set :path ["batch-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/quality-standard-verification-of s "batch-1")))
        (store/commit-record! s {:effect :kiln-emissions-screen/set :path ["batch-1"]
                                 :payload {:batch-id "batch-1" :verdict :resolved}})
        (is (= {:batch-id "batch-1" :verdict :resolved} (store/kiln-emissions-screen-of s "batch-1"))))
      (testing "cement-batch shipment drafts a record and advances the sequence"
        (store/commit-record! s {:effect :cement-batch/mark-shipped :path ["batch-1"]})
        (is (= "JPN-SHP-000000" (get (first (store/shipment-history s)) "record_id")))
        (is (= "cement-batch-shipment-draft" (get (first (store/shipment-history s)) "kind")))
        (is (true? (:batch-shipped? (store/cement-batch s "batch-1"))))
        (is (= 1 (count (store/shipment-history s))))
        (is (= 1 (store/next-shipment-sequence s "JPN")))
        (is (true? (store/batch-already-shipped? s "batch-1")))
        (is (false? (store/batch-already-shipped? s "batch-2"))))
      (testing "Mill Test Certificate drafts a record and advances the sequence"
        (store/commit-record! s {:effect :cement-batch/mark-certified :path ["batch-1"]})
        (is (= "JPN-MTC-000000" (get (first (store/certificate-history s)) "record_id")))
        (is (= "mill-certificate-draft" (get (first (store/certificate-history s)) "kind")))
        (is (true? (:mill-certified? (store/cement-batch s "batch-1"))))
        (is (= 1 (count (store/certificate-history s))))
        (is (= 1 (store/next-certificate-sequence s "JPN")))
        (is (true? (store/batch-already-certified? s "batch-1")))
        (is (false? (store/batch-already-certified? s "batch-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/cement-batch s "nope")))
    (is (= [] (store/all-cement-batches s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/shipment-history s)))
    (is (= [] (store/certificate-history s)))
    (is (zero? (store/next-shipment-sequence s "JPN")))
    (is (zero? (store/next-certificate-sequence s "JPN")))
    (store/with-cement-batches s {"x" {:id "x" :batch-name "n" :strength-28d-actual 47.0
                                   :strength-28d-min 42.5 :strength-28d-max 62.5
                                   :kiln-emissions-unresolved? false
                                   :batch-shipped? false :mill-certified? false
                                   :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:batch-name (store/cement-batch s "x"))))))
