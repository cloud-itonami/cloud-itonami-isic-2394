(ns cementmill.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean cement batch
  through intake -> quality-standard requirements verification ->
  kiln-emissions screening -> robot quality-lab mission ->
  cement-batch-shipment proposal (always escalates) -> human approval
  -> commit, then through Mill-Test-Certificate proposal (always
  escalates) -> human approval -> commit, then shows seven HARD holds
  (a jurisdiction with no spec-basis; a shipment attempted before the
  robot quality-lab mission ever ran; an out-of-spec 28-day
  compressive-strength deviation; a robotics mission already on file
  whose batch nonetheless independently re-checks out-of-tolerance; an
  unresolved kiln-emissions finding screened directly via
  `:kiln-emissions/screen` [never via an actuation op against an
  unscreened batch -- see this actor's own governor ns docstring / the
  lesson `automotive`'s (`cloud-itonami-isic-2910`) and its own prior
  siblings' ADR-0001s already recorded]; and a double cement-batch-
  shipment/certificate-issuance of an already-processed batch) that
  never reach a human at all, and prints the audit ledger + the draft
  shipment and Mill-Test-Certificate records."
  (:require [langgraph.graph :as g]
            [cementmill.export :as export]
            [cementmill.store :as store]
            [cementmill.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :quality-control-engineer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== cement-batch/intake batch-1 (JPN, clean; strength within spec, no kiln-emissions finding) ==")
    (println (exec! actor "t1" {:op :cement-batch/intake :subject "batch-1"
                                :patch {:id "batch-1" :batch-name "Ordinary Portland Cement Batch OPC-42.5N-104"}} operator))

    (println "== quality-standard/verify batch-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :quality-standard/verify :subject "batch-1"} operator))
    (println (approve! actor "t2"))

    (println "== kiln-emissions/screen batch-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :kiln-emissions/screen :subject "batch-1"} operator))
    (println (approve! actor "t3"))

    (println "== robotics/simulate-quality-lab-cell batch-1 (robot quality-lab mission; escalates -- human approves) ==")
    (println (exec! actor "t3b" {:op :robotics/simulate-quality-lab-cell :subject "batch-1"} operator))
    (println (approve! actor "t3b"))

    (println "== actuation/ship-cement-batch batch-1 (always escalates -- actuation/ship-cement-batch) ==")
    (let [r (exec! actor "t4" {:op :actuation/ship-cement-batch :subject "batch-1"} operator)]
      (println r)
      (println "-- human quality-control engineer approves --")
      (println (approve! actor "t4")))

    (println "== actuation/issue-mill-certificate batch-1 (always escalates -- actuation/issue-mill-certificate) ==")
    (let [r (exec! actor "t5" {:op :actuation/issue-mill-certificate :subject "batch-1"} operator)]
      (println r)
      (println "-- human quality-control engineer approves --")
      (println (approve! actor "t5")))

    (println "== quality-standard/verify batch-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :quality-standard/verify :subject "batch-2" :no-spec? true} operator))

    (println "== quality-standard/verify batch-3 (escalates -- human approves; sets up the out-of-spec test) ==")
    (println (exec! actor "t7" {:op :quality-standard/verify :subject "batch-3"} operator))
    (println (approve! actor "t7"))

    (println "== actuation/ship-cement-batch batch-3 before robotics simulation -> HARD hold (robotics-simulation-missing; cement-batch-strength-out-of-range co-fires too, since batch-3's 30.0 is already out of [42.5,62.5] independent of robotics state -- see cementmill.robotics ns docstring on why both checks share the same 28-day-strength field family) ==")
    (println (exec! actor "t7b" {:op :actuation/ship-cement-batch :subject "batch-3"} operator))

    (println "== robotics/simulate-quality-lab-cell batch-3 (out-of-spec strength; escalates -- human approves; mission itself records :passed? false) ==")
    (println (exec! actor "t7c" {:op :robotics/simulate-quality-lab-cell :subject "batch-3"} operator))
    (println (approve! actor "t7c"))

    (println "== actuation/ship-cement-batch batch-3 (30.0 outside [42.5,62.5] strength tolerance -> still HARD hold: robotics-simulation-missing [mission ran but recorded :passed? false, so never :robotics-sim-verified? true] + cement-batch-strength-out-of-range) ==")
    (println (exec! actor "t8" {:op :actuation/ship-cement-batch :subject "batch-3"} operator))

    (println "== actuation/ship-cement-batch batch-5 (robotics-sim PRE-SEEDED :robotics-sim-verified? true, but 28-day strength 65.0 outside [42.5,62.5] tolerance on independent recheck -> HARD hold: robotics-simulation-out-of-tolerance + cement-batch-strength-out-of-range, the governor never trusts the stale on-file verdict alone) ==")
    (println (exec! actor "t8b" {:op :quality-standard/verify :subject "batch-5"} operator))
    (println (approve! actor "t8b"))
    (println (exec! actor "t8c" {:op :actuation/ship-cement-batch :subject "batch-5"} operator))

    (println "== kiln-emissions/screen batch-4 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :kiln-emissions/screen :subject "batch-4"} operator))

    (println "== actuation/ship-cement-batch batch-1 AGAIN (double-shipment -> HARD hold) ==")
    (println (exec! actor "t10" {:op :actuation/ship-cement-batch :subject "batch-1"} operator))

    (println "== actuation/issue-mill-certificate batch-1 AGAIN (double-issuance -> HARD hold) ==")
    (println (exec! actor "t11" {:op :actuation/issue-mill-certificate :subject "batch-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft cement-batch-shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))

    (println "== draft Mill-Test-Certificate records ==")
    (doseq [r (store/certificate-history db)] (println r))

    (println "== social hand-off: audit package counts ==")
    (println (:counts (export/audit-package db)))
    (println "== social hand-off: CSV bundle keys ==")
    (println (keys (export/package->csv-bundle db)))))
