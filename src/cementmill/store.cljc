(ns cementmill.store
  "SSoT for the cement-mill actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/cementmill/store_contract_test.clj), which is the whole point:
  the actor, the Kiln Governor and the audit ledger never know which
  SSoT they run on.

  Like `automotive.store`'s dual dispatch/certificate history and
  every other dual-actuation sibling before it, this actor has TWO
  actuation events (shipping a cement batch, issuing a Mill Test
  Certificate) acting on the SAME entity (a cement batch), each with
  its OWN history collection, sequence counter and dedicated double-
  actuation-guard boolean (`:batch-shipped?`/`:mill-certified?`, never
  a `:status` value) -- the same discipline every prior sibling
  governor's guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320).

  The ledger stays append-only on every backend: 'which batch was
  screened for an unresolved kiln-emissions finding, which batch was
  shipped, which Mill Test Certificate was issued, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a community trusting a cement mill
  needs, and the evidence a mill needs if a shipment or certificate
  decision is later disputed."
  (:require [cementmill.registry :as registry]
            [cementmill.robotics :as robotics]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (cement-batch [s id])
  (all-cement-batches [s])
  (kiln-emissions-screen-of [s batch-id] "committed kiln-emissions screening verdict for a batch, or nil")
  (quality-standard-verification-of [s batch-id] "committed quality-standard requirements verification, or nil")
  (ledger [s])
  (shipment-history [s] "the append-only cement-batch-shipment history (cementmill.registry drafts)")
  (certificate-history [s] "the append-only Mill-Test-Certificate history (cementmill.registry drafts)")
  (next-shipment-sequence [s jurisdiction] "next shipment-number sequence for a jurisdiction")
  (next-certificate-sequence [s jurisdiction] "next evidence-number sequence for a jurisdiction")
  (batch-already-shipped? [s batch-id] "has this batch already been shipped?")
  (batch-already-certified? [s batch-id] "has this batch's Mill Test Certificate already been issued?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-cement-batches [s batches] "replace/seed the cement-batch directory (map id->batch)"))

;; ----------------------------- demo data -----------------------------

(defn- with-press-telemetry
  "Merges REAL press-collision telemetry onto a demo batch's base
  fields -- `cementmill.robotics/press-telemetry-for` actually runs a
  `physics-2d`-stepped press-platen/cube-specimen collision simulation
  for this batch's own `:press-platen-mass-kg` press-run configuration
  (ADR-2607152000), so even the 'already on file' seed data (as if from
  an earlier real compressive-strength-press run) is genuinely
  simulation-derived, never hand-typed doubles."
  [base]
  (merge base (select-keys (robotics/press-telemetry-for base)
                           [:sim-peak-compressive-force-n :sim-peak-compressive-stress-mpa
                            :sim-peak-crush-distance-m])))

(defn demo-data
  "A small, self-contained cement-batch set covering both actuation
  lifecycles (shipping a batch, issuing a Mill Test Certificate) so
  the actor + tests run offline. `:strength-28d-actual`/`:strength-28d-
  min`/`:strength-28d-max` remain this batch's own already-certified
  lab-recorded 28-day strength band (unchanged -- `cementmill.registry/
  cement-batch-strength-out-of-range?`'s real, established anchor);
  `:press-platen-mass-kg` is a NEW, real per-batch press-run
  configuration field (ADR-2607152000) -- `with-press-telemetry` runs
  the REAL `physics-2d` press simulation for it, producing
  `:sim-peak-compressive-force-n`/`:sim-peak-compressive-stress-mpa`,
  the ground truth `cementmill.robotics/simulation-out-of-tolerance?`
  independently rechecks against the SAME `:strength-28d-min`/
  `:strength-28d-max` band. batch-1/2/4 use the mill's own standard
  press-platen-mass (12.0/12.0/14.0 kg -- clears its own band with
  margin); batch-3's lighter 7.0 kg reading lands below its own band,
  consistent with its already-known 30.0 out-of-spec strength. batch-5
  (blast-furnace-slag cement, seeded `:robotics-sim-verified? true`,
  i.e. 'already on file') is DELIBERATELY press-tested with an
  unrealistically heavy 16.5 kg platen-mass configuration -- a genuine
  press-run-record inconsistency (no real QC lab uses a heavier platen
  for one batch than its own standard SOP) that the real, re-run
  simulation catches on independent recheck even though the mission was
  marked passed without this real check ever having run -- the
  cement-mill analog of automotive's vehicle-5 misclassified-:city
  fixture (ADR-2607151600)."
  []
  {:cement-batches
   (into {}
         (map (fn [b] [(:id b) (with-press-telemetry b)]))
         [{:id "batch-1" :batch-name "Ordinary Portland Cement Batch OPC-42.5N-104"
           :strength-28d-actual 47.0 :strength-28d-min 42.5 :strength-28d-max 62.5
           :press-platen-mass-kg 12.0
           :kiln-emissions-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :mill-certified? false
           :jurisdiction "JPN" :status :intake}
          {:id "batch-2" :batch-name "Ordinary Portland Cement Batch OPC-42.5N-207"
           :strength-28d-actual 47.0 :strength-28d-min 42.5 :strength-28d-max 62.5
           :press-platen-mass-kg 12.0
           :kiln-emissions-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :mill-certified? false
           :jurisdiction "ATL" :status :intake}
          {:id "batch-3" :batch-name "普通ポルトランドセメント バッチ OPC-42.5N-311"
           :strength-28d-actual 30.0 :strength-28d-min 42.5 :strength-28d-max 62.5
           :press-platen-mass-kg 7.0
           :kiln-emissions-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :mill-certified? false
           :jurisdiction "JPN" :status :intake}
          {:id "batch-4" :batch-name "早強ポルトランドセメント バッチ RPC-52.5R-412"
           :strength-28d-actual 55.0 :strength-28d-min 52.5 :strength-28d-max 72.5
           :press-platen-mass-kg 14.0
           :kiln-emissions-unresolved? true
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :mill-certified? false
           :jurisdiction "JPN" :status :intake}
          {:id "batch-5" :batch-name "高炉セメントB種 バッチ BFS-B-509"
           :strength-28d-actual 65.0 :strength-28d-min 42.5 :strength-28d-max 62.5
           :press-platen-mass-kg 16.5
           :kiln-emissions-unresolved? false
           :robotics-sim-verified? true :robotics-sim-record nil
           :batch-shipped? false :mill-certified? false
           :jurisdiction "JPN" :status :intake}])})

;; ----------------------------- shared commit logic -----------------------------

(defn- ship-cement-batch!
  "Backend-agnostic `:cement-batch/mark-shipped` -- looks up the batch
  via the protocol and drafts the cement-batch-shipment record, and
  returns {:result .. :batch-patch ..} for the caller to persist."
  [s batch-id]
  (let [a (cement-batch s batch-id)
        seq-n (next-shipment-sequence s (:jurisdiction a))
        result (registry/register-cement-batch-shipment batch-id (:jurisdiction a) seq-n)]
    {:result result
     :batch-patch {:batch-shipped? true
                   :shipment-number (get result "shipment_number")}}))

(defn- issue-mill-certificate!
  "Backend-agnostic `:cement-batch/mark-certified` -- looks up the
  batch via the protocol and drafts the Mill-Test-Certificate record,
  and returns {:result .. :batch-patch ..} for the caller to persist."
  [s batch-id]
  (let [a (cement-batch s batch-id)
        seq-n (next-certificate-sequence s (:jurisdiction a))
        result (registry/register-mill-certificate batch-id (:jurisdiction a) seq-n)]
    {:result result
     :batch-patch {:mill-certified? true
                   :evidence-number (get result "evidence_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (cement-batch [_ id] (get-in @a [:cement-batches id]))
  (all-cement-batches [_] (sort-by :id (vals (:cement-batches @a))))
  (kiln-emissions-screen-of [_ id] (get-in @a [:kiln-emissions-screens id]))
  (quality-standard-verification-of [_ batch-id] (get-in @a [:verifications batch-id]))
  (ledger [_] (:ledger @a))
  (shipment-history [_] (:shipments @a))
  (certificate-history [_] (:certificates @a))
  (next-shipment-sequence [_ jurisdiction] (get-in @a [:shipment-sequences jurisdiction] 0))
  (next-certificate-sequence [_ jurisdiction] (get-in @a [:certificate-sequences jurisdiction] 0))
  (batch-already-shipped? [_ batch-id] (boolean (get-in @a [:cement-batches batch-id :batch-shipped?])))
  (batch-already-certified? [_ batch-id] (boolean (get-in @a [:cement-batches batch-id :mill-certified?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :cement-batch/upsert
      (swap! a update-in [:cement-batches (:id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :kiln-emissions-screen/set
      (swap! a assoc-in [:kiln-emissions-screens (first path)] payload)

      :cement-batch/mark-shipped
      (let [batch-id (first path)
            {:keys [result batch-patch]} (ship-cement-batch! s batch-id)
            jurisdiction (:jurisdiction (cement-batch s batch-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:shipment-sequences jurisdiction] (fnil inc 0))
                       (update-in [:cement-batches batch-id] merge batch-patch)
                       (update :shipments registry/append result))))
        result)

      :cement-batch/mark-certified
      (let [batch-id (first path)
            {:keys [result batch-patch]} (issue-mill-certificate! s batch-id)
            jurisdiction (:jurisdiction (cement-batch s batch-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:certificate-sequences jurisdiction] (fnil inc 0))
                       (update-in [:cement-batches batch-id] merge batch-patch)
                       (update :certificates registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-cement-batches [s batches] (when (seq batches) (swap! a assoc :cement-batches batches)) s))

(defn seed-db
  "A MemStore seeded with the demo cement-batch set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :kiln-emissions-screens {} :ledger [] :shipment-sequences {}
                           :shipments [] :certificate-sequences {} :certificates []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/kiln-emissions-screen payloads,
  ledger facts, shipment/certificate records) are stored as EDN
  strings so `langchain.db` doesn't expand them into sub-entities --
  the same convention every sibling actor's store uses."
  {:batch/id                          {:db/unique :db.unique/identity}
   :verification/batch-id             {:db/unique :db.unique/identity}
   :kiln-emissions-screen/batch-id     {:db/unique :db.unique/identity}
   :ledger/seq                        {:db/unique :db.unique/identity}
   :shipment/seq                      {:db/unique :db.unique/identity}
   :certificate/seq                   {:db/unique :db.unique/identity}
   :shipment-sequence/jurisdiction    {:db/unique :db.unique/identity}
   :certificate-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- batch->tx [{:keys [id batch-name strength-28d-actual strength-28d-min strength-28d-max
                           press-platen-mass-kg sim-peak-compressive-force-n sim-peak-compressive-stress-mpa
                           kiln-emissions-unresolved? robotics-sim-verified? robotics-sim-record
                           batch-shipped? mill-certified?
                           jurisdiction status shipment-number evidence-number]}]
  (cond-> {:batch/id id}
    batch-name                                 (assoc :batch/batch-name batch-name)
    strength-28d-actual                        (assoc :batch/strength-28d-actual strength-28d-actual)
    strength-28d-min                           (assoc :batch/strength-28d-min strength-28d-min)
    strength-28d-max                           (assoc :batch/strength-28d-max strength-28d-max)
    press-platen-mass-kg                        (assoc :batch/press-platen-mass-kg press-platen-mass-kg)
    sim-peak-compressive-force-n                 (assoc :batch/sim-peak-compressive-force-n sim-peak-compressive-force-n)
    (some? sim-peak-compressive-stress-mpa)     (assoc :batch/sim-peak-compressive-stress-mpa sim-peak-compressive-stress-mpa)
    (some? kiln-emissions-unresolved?)         (assoc :batch/kiln-emissions-unresolved? kiln-emissions-unresolved?)
    (some? robotics-sim-verified?)              (assoc :batch/robotics-sim-verified? robotics-sim-verified?)
    (some? robotics-sim-record)                 (assoc :batch/robotics-sim-record (ls/enc robotics-sim-record))
    (some? batch-shipped?)                     (assoc :batch/batch-shipped? batch-shipped?)
    (some? mill-certified?)                    (assoc :batch/mill-certified? mill-certified?)
    jurisdiction                               (assoc :batch/jurisdiction jurisdiction)
    status                                     (assoc :batch/status status)
    shipment-number                            (assoc :batch/shipment-number shipment-number)
    evidence-number                            (assoc :batch/evidence-number evidence-number)))

(def ^:private batch-pull
  [:batch/id :batch/batch-name :batch/strength-28d-actual
   :batch/strength-28d-min :batch/strength-28d-max
   :batch/press-platen-mass-kg :batch/sim-peak-compressive-force-n :batch/sim-peak-compressive-stress-mpa
   :batch/kiln-emissions-unresolved? :batch/robotics-sim-verified? :batch/robotics-sim-record
   :batch/batch-shipped? :batch/mill-certified?
   :batch/jurisdiction :batch/status :batch/shipment-number :batch/evidence-number])

(defn- pull->batch [m]
  (when (:batch/id m)
    {:id (:batch/id m) :batch-name (:batch/batch-name m)
     :strength-28d-actual (:batch/strength-28d-actual m)
     :strength-28d-min (:batch/strength-28d-min m)
     :strength-28d-max (:batch/strength-28d-max m)
     :press-platen-mass-kg (:batch/press-platen-mass-kg m)
     :sim-peak-compressive-force-n (:batch/sim-peak-compressive-force-n m)
     :sim-peak-compressive-stress-mpa (:batch/sim-peak-compressive-stress-mpa m)
     :kiln-emissions-unresolved? (boolean (:batch/kiln-emissions-unresolved? m))
     :robotics-sim-verified? (boolean (:batch/robotics-sim-verified? m))
     :robotics-sim-record (ls/dec* (:batch/robotics-sim-record m))
     :batch-shipped? (boolean (:batch/batch-shipped? m))
     :mill-certified? (boolean (:batch/mill-certified? m))
     :jurisdiction (:batch/jurisdiction m) :status (:batch/status m)
     :shipment-number (:batch/shipment-number m) :evidence-number (:batch/evidence-number m)}))

(defrecord DatomicStore [conn]
  Store
  (cement-batch [_ id]
    (pull->batch (d/pull (d/db conn) batch-pull [:batch/id id])))
  (all-cement-batches [_]
    (->> (d/q '[:find [?id ...] :where [?e :batch/id ?id]] (d/db conn))
         (map #(pull->batch (d/pull (d/db conn) batch-pull [:batch/id %])))
         (sort-by :id)))
  (kiln-emissions-screen-of [_ id]
    (ls/dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :kiln-emissions-screen/batch-id ?aid] [?k :kiln-emissions-screen/payload ?p]]
              (d/db conn) id)))
  (quality-standard-verification-of [_ batch-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :verification/batch-id ?aid] [?a :verification/payload ?p]]
              (d/db conn) batch-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (shipment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :shipment/seq ?s] [?e :shipment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (certificate-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :certificate/seq ?s] [?e :certificate/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (next-shipment-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :shipment-sequence/jurisdiction ?j] [?e :shipment-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-certificate-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :certificate-sequence/jurisdiction ?j] [?e :certificate-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (batch-already-shipped? [s batch-id]
    (boolean (:batch-shipped? (cement-batch s batch-id))))
  (batch-already-certified? [s batch-id]
    (boolean (:mill-certified? (cement-batch s batch-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :cement-batch/upsert
      (d/transact! conn [(batch->tx value)])

      :verification/set
      (d/transact! conn [{:verification/batch-id (first path) :verification/payload (ls/enc payload)}])

      :kiln-emissions-screen/set
      (d/transact! conn [{:kiln-emissions-screen/batch-id (first path) :kiln-emissions-screen/payload (ls/enc payload)}])

      :cement-batch/mark-shipped
      (let [batch-id (first path)
            {:keys [result batch-patch]} (ship-cement-batch! s batch-id)
            jurisdiction (:jurisdiction (cement-batch s batch-id))
            next-n (inc (next-shipment-sequence s jurisdiction))]
        (d/transact! conn
                     [(batch->tx (assoc batch-patch :id batch-id))
                      {:shipment-sequence/jurisdiction jurisdiction :shipment-sequence/next next-n}
                      {:shipment/seq (count (shipment-history s)) :shipment/record (ls/enc (get result "record"))}])
        result)

      :cement-batch/mark-certified
      (let [batch-id (first path)
            {:keys [result batch-patch]} (issue-mill-certificate! s batch-id)
            jurisdiction (:jurisdiction (cement-batch s batch-id))
            next-n (inc (next-certificate-sequence s jurisdiction))]
        (d/transact! conn
                     [(batch->tx (assoc batch-patch :id batch-id))
                      {:certificate-sequence/jurisdiction jurisdiction :certificate-sequence/next next-n}
                      {:certificate/seq (count (certificate-history s)) :certificate/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-cement-batches [s batches]
    (when (seq batches) (d/transact! conn (mapv batch->tx (vals batches)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:cement-batches ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [cement-batches]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-cement-batches s cement-batches))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo cement-batch set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
