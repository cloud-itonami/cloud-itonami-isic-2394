(ns cementmill.registry
  "Pure-function cement-batch-shipment + Mill-Test-Certificate record
  construction -- an append-only cement-mill book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a shipment-reference or
  Mill-Test-Certificate (MTC) reference number -- every mill/
  jurisdiction assigns its own reference format. This namespace does
  NOT invent one; it builds a jurisdiction-scoped sequence number and
  validates the record's required fields, the same honest, non-
  fabricating discipline `cementmill.facts` uses.

  `cement-batch-strength-out-of-range?` continues this fleet's
  two-sided range check family -- the same lo/hi bounds-comparison
  shape `automotive.registry/vehicle-emissions-out-of-range?` (and its
  own prior siblings: testlab/conservation/water/steelworks/turbine)
  established, applied here to a cement batch's own measured 28-day
  compressive-strength deviation against the batch's own recorded
  acceptance-band bounds -- the single most standard real cement QC/
  acceptance metric.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant/kiln control system. It builds the RECORD a
  mill would keep, not the act of shipping the batch or issuing the
  Mill Test Certificate itself (that is `cementmill.operation`'s
  `:actuation/ship-cement-batch`/`:actuation/issue-mill-certificate`,
  always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the mill's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn cement-batch-strength-out-of-range?
  "Does `batch`'s own `:strength-28d-actual` fall outside its own
  `[:strength-28d-min :strength-28d-max]` recorded acceptance-band
  bounds? A pure ground-truth check against the batch's own permanent
  fields -- no upstream comparison needed. One of this fleet's two-
  sided range check family (see ns docstring)."
  [{:keys [strength-28d-actual strength-28d-min strength-28d-max]}]
  (and (number? strength-28d-actual) (number? strength-28d-min) (number? strength-28d-max)
       (or (< strength-28d-actual strength-28d-min)
           (> strength-28d-actual strength-28d-max))))

(defn register-cement-batch-shipment
  "Validate + construct the CEMENT-BATCH-SHIPMENT registration DRAFT --
  the mill's own act of shipping a real cement batch to a customer or
  distributor. Pure function -- does not touch any real plant/kiln
  control system; it builds the RECORD a mill would keep.
  `cementmill.governor` independently re-verifies the batch's own
  28-day compressive-strength sufficiency against its own acceptance
  bounds, and a double-shipment for the same batch, before this is
  ever allowed to commit."
  [batch-id jurisdiction sequence]
  (when-not (and batch-id (not= batch-id ""))
    (throw (ex-info "cement-batch-shipment: batch_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "cement-batch-shipment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "cement-batch-shipment: sequence must be >= 0" {})))
  (let [shipment-number (str (str/upper-case jurisdiction) "-SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "cement-batch-shipment-draft"
                "batch_id" batch-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "CementBatchShipment" shipment-number shipment-number)}))

(defn register-mill-certificate
  "Validate + construct the MILL-TEST-CERTIFICATE (MTC) registration
  DRAFT -- the mill's own act of issuing a real Mill Test Certificate
  certifying a cement batch's composition and strength. Pure function
  -- does not touch any real plant/kiln control system; it builds the
  RECORD a mill would keep. `cementmill.governor` independently
  re-verifies the batch's own kiln-emissions resolution status, and a
  double-issuance for the same batch, before this is ever allowed to
  commit."
  [batch-id jurisdiction sequence]
  (when-not (and batch-id (not= batch-id ""))
    (throw (ex-info "mill-certificate: batch_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "mill-certificate: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "mill-certificate: sequence must be >= 0" {})))
  (let [evidence-number (str (str/upper-case jurisdiction) "-MTC-" (zero-pad sequence 6))
        record {"record_id" evidence-number
                "kind" "mill-certificate-draft"
                "batch_id" batch-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "evidence_number" evidence-number
     "certificate" (unsigned-certificate "MillTestCertificate" evidence-number evidence-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
