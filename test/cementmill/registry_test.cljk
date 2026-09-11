(ns cementmill.registry-test
  (:require [clojure.test :refer [deftest is]]
            [cementmill.registry :as r]))

;; ----------------------------- cement-batch-strength-out-of-range? -----------------------------

(deftest not-out-of-range-when-within-bounds
  (is (not (r/cement-batch-strength-out-of-range? {:strength-28d-actual 47.0 :strength-28d-min 42.5 :strength-28d-max 62.5})))
  (is (not (r/cement-batch-strength-out-of-range? {:strength-28d-actual 42.5 :strength-28d-min 42.5 :strength-28d-max 62.5})))
  (is (not (r/cement-batch-strength-out-of-range? {:strength-28d-actual 62.5 :strength-28d-min 42.5 :strength-28d-max 62.5}))))

(deftest out-of-range-when-below-minimum-or-above-maximum
  (is (r/cement-batch-strength-out-of-range? {:strength-28d-actual 30.0 :strength-28d-min 42.5 :strength-28d-max 62.5}))
  (is (r/cement-batch-strength-out-of-range? {:strength-28d-actual 65.0 :strength-28d-min 42.5 :strength-28d-max 62.5})))

(deftest out-of-range-is-false-on-missing-fields
  (is (not (r/cement-batch-strength-out-of-range? {})))
  (is (not (r/cement-batch-strength-out-of-range? {:strength-28d-actual 30.0}))))

;; ----------------------------- register-cement-batch-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-shipment
  (let [result (r/register-cement-batch-shipment "batch-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-cement-batch-shipment "batch-1" "JPN" 7)]
    (is (= (get result "shipment_number") "JPN-SHP-000007"))
    (is (= (get-in result ["record" "batch_id"]) "batch-1"))
    (is (= (get-in result ["record" "kind"]) "cement-batch-shipment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? Exception (r/register-cement-batch-shipment "" "JPN" 0)))
  (is (thrown? Exception (r/register-cement-batch-shipment "batch-1" "" 0)))
  (is (thrown? Exception (r/register-cement-batch-shipment "batch-1" "JPN" -1))))

;; ----------------------------- register-mill-certificate -----------------------------

(deftest certificate-is-a-draft-not-real-certification
  (let [result (r/register-mill-certificate "batch-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest certificate-assigns-evidence-number
  (let [result (r/register-mill-certificate "batch-1" "JPN" 3)]
    (is (= (get result "evidence_number") "JPN-MTC-000003"))
    (is (= (get-in result ["record" "batch_id"]) "batch-1"))
    (is (= (get-in result ["record" "kind"]) "mill-certificate-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest certificate-validation-rules
  (is (thrown? Exception (r/register-mill-certificate "" "JPN" 0)))
  (is (thrown? Exception (r/register-mill-certificate "batch-1" "" 0)))
  (is (thrown? Exception (r/register-mill-certificate "batch-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-cement-batch-shipment "batch-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-cement-batch-shipment "batch-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-SHP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-SHP-000001" (get-in hist2 [1 "record_id"])))))
