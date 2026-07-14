(ns cementmill.governor-contract-test
  "The governor contract as executable tests -- the cement-mill analog
  of `automotive.governor-contract-test` (`cloud-itonami-isic-2910`).
  The single invariant under test:

    Cement Mill Advisor never ships a cement batch or issues a Mill
    Test Certificate the Kiln Governor would reject,
    `:actuation/ship-cement-batch`/`:actuation/issue-mill-certificate`
    NEVER auto-commit at any phase, `:cement-batch/intake` (no direct
    capital risk) MAY auto-commit when clean, and every decision
    (commit OR hold) leaves exactly one ledger fact.

  NOTE on co-firing (see `cementmill.governor`/`cementmill.robotics`
  ns docstrings): unlike `automotive.governor`'s
  `robotics-simulation-violations`/`vehicle-emissions-out-of-range-
  violations` (which key off two DISTINCT fields), this actor's
  `robotics-simulation-violations` and `cement-batch-strength-out-of-
  range-violations` are both grounded in the SAME 28-day compressive-
  strength fields. So a batch whose strength is genuinely out of
  range will show BOTH rules in its violations vector at once (unless
  the robotics-missing branch fires instead of the out-of-tolerance
  branch). Tests below assert PRESENCE of the rule each test targets,
  not exclusivity."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [cementmill.store :as store]
            [cementmill.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :quality-control-engineer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving a requirements
  verification on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :quality-standard/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- screen!
  "Walks `subject` through kiln-emissions screening -> approve,
  leaving a screening on file. Only safe to call for a batch whose
  kiln-emissions status has already resolved -- an unresolved finding
  HARD-holds the screen itself (see
  `kiln-emissions-unresolved-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :kiln-emissions/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(defn- simulate-robotics!
  "Walks `subject` through the robot quality-lab verification mission
  -> approve, leaving `:robotics-sim-verified?` on file. Only
  meaningful to call for a batch whose 28-day strength is actually
  within tolerance -- an out-of-tolerance batch still gets
  :robotics-sim-verified? recorded (per whatever the mission itself
  found: false), but `cementmill.governor`'s independent recheck
  HARD-holds regardless."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-robotics") {:op :robotics/simulate-quality-lab-cell :subject subject} operator)
  (approve! actor (str tid-prefix "-robotics")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :cement-batch/intake :subject "batch-1"
                   :patch {:id "batch-1" :batch-name "Ordinary Portland Cement Batch OPC-42.5N-104"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Ordinary Portland Cement Batch OPC-42.5N-104" (:batch-name (store/cement-batch db "batch-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest requirements-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :quality-standard/verify :subject "batch-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/quality-standard-verification-of db "batch-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a quality-standard/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :quality-standard/verify :subject "batch-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/quality-standard-verification-of db "batch-1")) "no verification written"))))

(deftest ship-cement-batch-without-verification-is-held
  (testing "actuation/ship-cement-batch before any requirements verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/ship-cement-batch :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest cement-batch-strength-out-of-range-is-held
  (testing "a batch whose own 28-day strength falls outside its own acceptance-band bounds -> HOLD (co-fires with robotics-simulation-missing, since batch-3's robotics mission never ran either -- see ns docstring)"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "batch-3")
          res (exec-op actor "t5" {:op :actuation/ship-cement-batch :subject "batch-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:cement-batch-strength-out-of-range} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest kiln-emissions-unresolved-is-held-and-unoverridable
  (testing "an unresolved kiln-emissions finding on a batch -> HOLD, and never reaches request-approval -- exercised via :kiln-emissions/screen DIRECTLY, not via the actuation op against an unscreened batch (see this actor's governor ns docstring / automotive's [cloud-itonami-isic-2910] and its own prior siblings' ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :kiln-emissions/screen :subject "batch-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:kiln-emissions-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/kiln-emissions-screen-of db "batch-4")) "no clearance written"))))

(deftest ship-cement-batch-always-escalates-then-human-decides
  (testing "a clean, fully-verified, in-spec batch still ALWAYS interrupts for human approval -- actuation/ship-cement-batch is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "batch-1")
          _ (simulate-robotics! actor "t7pre2" "batch-1")
          r1 (exec-op actor "t7" {:op :actuation/ship-cement-batch :subject "batch-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, shipment record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:batch-shipped? (store/cement-batch db "batch-1"))))
          (is (= 1 (count (store/shipment-history db))) "one draft shipment record"))))))

(deftest issue-mill-certificate-always-escalates-then-human-decides
  (testing "a clean, fully-verified, resolved-emissions batch still ALWAYS interrupts for human approval -- actuation/issue-mill-certificate is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "batch-1")
          _ (screen! actor "t8pre2" "batch-1")
          r1 (exec-op actor "t8" {:op :actuation/issue-mill-certificate :subject "batch-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, certificate record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:mill-certified? (store/cement-batch db "batch-1"))))
          (is (= 1 (count (store/certificate-history db))) "one draft certificate record"))))))

(deftest ship-cement-batch-double-shipment-is-held
  (testing "shipping the same batch twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "batch-1")
          _ (simulate-robotics! actor "t9pre2" "batch-1")
          _ (exec-op actor "t9a" {:op :actuation/ship-cement-batch :subject "batch-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :actuation/ship-cement-batch :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-shipped} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/shipment-history db))) "still only the one earlier shipment"))))

(deftest issue-mill-certificate-double-issuance-is-held
  (testing "issuing the same batch's Mill Test Certificate twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "batch-1")
          _ (screen! actor "t10pre2" "batch-1")
          _ (exec-op actor "t10a" {:op :actuation/issue-mill-certificate :subject "batch-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :actuation/issue-mill-certificate :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-certified} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/certificate-history db))) "still only the one earlier certificate issuance"))))

(deftest robotics-simulation-always-needs-approval
  (testing "robotics/simulate-quality-lab-cell is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :robotics/simulate-quality-lab-cell :subject "batch-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:robotics-sim-verified? (store/cement-batch db "batch-1"))))))))

(deftest ship-cement-batch-without-robotics-simulation-is-held
  (testing "actuation/ship-cement-batch before the robot quality-lab mission ever ran -> HOLD (robotics-simulation-missing) -- batch-1's strength is in-range so this isolates the check-3 'missing' rule from check-4"
    (let [[db actor] (fresh)
          _ (verify! actor "t12pre" "batch-1")
          res (exec-op actor "t12" {:op :actuation/ship-cement-batch :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:robotics-simulation-missing} (-> (store/ledger db) last :basis)))
      (is (not (some #{:cement-batch-strength-out-of-range} (-> (store/ledger db) last :basis)))
          "batch-1's strength is in-range, so check 4 does not co-fire here")
      (is (empty? (store/shipment-history db))))))

(deftest robotics-simulation-out-of-tolerance-is-held
  (testing "batch-5 has a robotics-sim already on file, but its own 28-day strength reading falls outside its own tolerance bounds on INDEPENDENT recheck -> HOLD, never trusts the on-file verdict alone (co-fires with cement-batch-strength-out-of-range -- see ns docstring)"
    (let [[db actor] (fresh)
          _ (verify! actor "t13pre" "batch-5")
          res (exec-op actor "t13" {:op :actuation/ship-cement-batch :subject "batch-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:robotics-simulation-out-of-tolerance} (-> (store/ledger db) last :basis)))
      (is (some #{:cement-batch-strength-out-of-range} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :cement-batch/intake :subject "batch-1"
                          :patch {:id "batch-1" :batch-name "Ordinary Portland Cement Batch OPC-42.5N-104"}} operator)
      (exec-op actor "b" {:op :quality-standard/verify :subject "batch-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
