(ns cementmill.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously shipped a HAND-WRITTEN `docs/samples/operator-
  console.html` with no generator behind it. This namespace replaces it
  with a page derived entirely from a REAL run of this repo's own actor
  stack (`cementmill.operation` -> `cementmill.governor` ->
  `cementmill.store`), driven through `langgraph.graph/run*` exactly the
  way `cementmill.sim` (`clojure -M:dev:run`) drives it.

  The scenario below is this repo's OWN `cementmill.sim` scenario --
  confirmed BEFORE this file was written to produce a 16-fact ledger
  with 7 HARD governor holds against the real seeded batch ids
  `batch-1`..`batch-5` from `cementmill.store/demo-data`. Nothing on the
  page is hand-typed: every batch id, batch name, jurisdiction, strength
  figure, simulated press-stress figure, hold rule, hold detail string,
  shipment number and Mill-Test-Certificate evidence number is read back
  out of the store the actor just wrote.

  The two figures that look most like 'invented numbers' are the ones
  that are least invented: `:strength-28d-actual` is the batch's own
  seeded lab-recorded value, and `:sim-peak-compressive-stress-mpa` is
  produced by an actual `physics-2d`-stepped press-platen/cube-specimen
  collision simulation (`cementmill.robotics`, ADR-2607152000), re-run
  independently by the governor rather than trusted from the mission's
  own stored verdict.

  Deterministic by construction: batches are `sort-by :id`, the ledger
  and both draft-record histories are append-only vectors, the advisor
  is the deterministic mock, and nothing in the page carries a timestamp
  or a random value -- so two consecutive runs are byte-identical.
  `-main` REFUSES to write a page that contains no HARD hold, because a
  console showing no hold is not evidence of a governor.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [cementmill.registry :as registry]
            [cementmill.robotics :as robotics]
            [cementmill.store :as store]
            [cementmill.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  "The same operator context `cementmill.sim` runs under -- a phase-3
  quality-control engineer."
  {:actor-id "op-1" :actor-role :quality-control-engineer :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through this repo's own `cementmill.sim`
  scenario, which reaches every disposition this actor can produce:

    batch-1  clears a full lifecycle -- intake (auto-commits clean at
             phase 3, no actuation stake), a JPN quality-standard
             requirements verification (escalates -- approved), a
             kiln-emissions screening (clean -- approved), a robot
             quality-lab press mission (approved, `:passed? true`), a
             cement-batch shipment (ALWAYS escalates -- approved) and a
             Mill-Test-Certificate issuance (ALWAYS escalates --
             approved).
    batch-2  HARD-holds a quality-standard verification for a
             deliberately unregistered jurisdiction with no official
             spec-basis in `cementmill.facts`.
    batch-3  clears its own verification, then HARD-holds a shipment
             attempted BEFORE the robot quality-lab mission ever ran;
             its real press simulation then genuinely lands below its
             own [42.5,62.5] band (mission records `:passed? false`), so
             the retried shipment HARD-holds again.
    batch-5  is seeded `:robotics-sim-verified? true` ('already on
             file'), but its deliberately-misconfigured 16.5 kg platen
             mass makes the re-run simulation exceed its own band on
             independent recheck -- the governor never trusts the stale
             on-file verdict, so the shipment HARD-holds.
    batch-4  HARD-holds a kiln-emissions screening on its own unresolved
             out-of-limit finding.
    batch-1  HARD-holds a SECOND shipment and a SECOND certificate
             issuance (the double-actuation guards).

  Every HARD hold bypasses the human entirely -- it is never offered for
  approval. Returns the store the actor wrote."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1" {:op :cement-batch/intake :subject "batch-1"
                       :patch {:id "batch-1"
                               :batch-name "Ordinary Portland Cement Batch OPC-42.5N-104"}})

    (exec! actor "t2" {:op :quality-standard/verify :subject "batch-1"})
    (approve! actor "t2")

    (exec! actor "t3" {:op :kiln-emissions/screen :subject "batch-1"})
    (approve! actor "t3")

    (exec! actor "t3b" {:op :robotics/simulate-quality-lab-cell :subject "batch-1"})
    (approve! actor "t3b")

    (exec! actor "t4" {:op :actuation/ship-cement-batch :subject "batch-1"})
    (approve! actor "t4")

    (exec! actor "t5" {:op :actuation/issue-mill-certificate :subject "batch-1"})
    (approve! actor "t5")

    (exec! actor "t6" {:op :quality-standard/verify :subject "batch-2" :no-spec? true})

    (exec! actor "t7" {:op :quality-standard/verify :subject "batch-3"})
    (approve! actor "t7")

    (exec! actor "t7b" {:op :actuation/ship-cement-batch :subject "batch-3"})

    (exec! actor "t7c" {:op :robotics/simulate-quality-lab-cell :subject "batch-3"})
    (approve! actor "t7c")

    (exec! actor "t8" {:op :actuation/ship-cement-batch :subject "batch-3"})

    (exec! actor "t8b" {:op :quality-standard/verify :subject "batch-5"})
    (approve! actor "t8b")
    (exec! actor "t8c" {:op :actuation/ship-cement-batch :subject "batch-5"})

    (exec! actor "t9" {:op :kiln-emissions/screen :subject "batch-4"})

    (exec! actor "t10" {:op :actuation/ship-cement-batch :subject "batch-1"})

    (exec! actor "t11" {:op :actuation/issue-mill-certificate :subject "batch-1"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- basis-str
  "Renders a ledger fact's `:basis` -- keywords (hold rules, advisor
  cite keys) keep their colon, strings (legal-basis / provenance URLs)
  are shown as-is."
  [basis]
  (str/join ", " (map str basis)))

(defn- last-fact-for [ledger batch-id]
  (last (filter #(= (:subject %) batch-id) ledger)))

(defn- status-cell [ledger batch-id]
  (let [f (last-fact-for ledger batch-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"

      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join " + " (map name (:basis f)))) "</span>")

      (= :approval-rejected (:t f))
      "<span class=\"critical\">rejected by approver</span>"

      (= :committed (:t f)) "<span class=\"ok\">committed</span>"

      :else "<span class=\"muted\">in progress</span>")))

(defn- strength-cell
  "The batch's own lab-recorded 28-day compressive strength against its
  own recorded acceptance band, classed by the SAME pure predicate the
  governor uses (`registry/cement-batch-strength-out-of-range?`)."
  [{:keys [strength-28d-actual strength-28d-min strength-28d-max] :as b}]
  (format "<span class=\"%s\">%s &isin; [%s,%s] MPa</span>"
          (if (registry/cement-batch-strength-out-of-range? b) "critical" "ok")
          (esc strength-28d-actual) (esc strength-28d-min) (esc strength-28d-max)))

(defn- press-sim-cell
  "The peak compressive stress produced by the REAL `physics-2d`-stepped
  press simulation of this batch's own `:press-platen-mass-kg` press-run
  configuration, classed by the SAME predicate the governor independently
  re-runs (`robotics/simulation-out-of-tolerance?`)."
  [{:keys [sim-peak-compressive-stress-mpa press-platen-mass-kg] :as b}]
  (if (nil? sim-peak-compressive-stress-mpa)
    "<span class=\"muted\">not simulated</span>"
    (format "<span class=\"%s\">%s MPa</span> <span class=\"muted\">(platen %s kg)</span>"
            (if (robotics/simulation-out-of-tolerance? b) "critical" "ok")
            (esc sim-peak-compressive-stress-mpa) (esc press-platen-mass-kg))))

(defn- kiln-cell [{:keys [kiln-emissions-unresolved?]}]
  (if kiln-emissions-unresolved?
    "<span class=\"critical\">unresolved finding</span>"
    "<span class=\"ok\">resolved</span>"))

(defn- lifecycle-cell [{:keys [batch-shipped? mill-certified? shipment-number evidence-number]}]
  (cond
    (and batch-shipped? mill-certified?)
    (format "<span class=\"ok\">shipped &amp; certified</span> <code>%s</code> <code>%s</code>"
            (esc shipment-number) (esc evidence-number))

    batch-shipped?
    (format "<span class=\"warn\">shipped, not yet certified</span> <code>%s</code>"
            (esc shipment-number))

    mill-certified?
    (format "<span class=\"warn\">certified, not yet shipped</span> <code>%s</code>"
            (esc evidence-number))

    :else "<span class=\"muted\">in mill</span>"))

(defn- batch-row [ledger {:keys [id batch-name jurisdiction] :as b}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc batch-name) (esc jurisdiction)
          (strength-cell b) (press-sim-cell b) (kiln-cell b)
          (lifecycle-cell b)
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject basis summary violations]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (if (= :governor-hold t)
            "<span class=\"critical\">governor-hold</span>"
            (str "<span class=\"ok\">" (esc (name t)) "</span>"))
          (esc (name (or op :n-a))) (esc subject)
          (esc (basis-str basis))
          (esc (or summary
                   (some->> violations (map :detail) (str/join " / "))
                   ""))))

(defn- draft-row [record]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (get record "record_id")) (esc (get record "kind"))
          (esc (get record "batch_id")) (esc (get record "jurisdiction"))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract (README
  ;; `Ops`, `cementmill.governor`/`cementmill.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run. The rule names
  ;; below are the governor's own `:rule` keywords.
  ["        <tr><td><code>:cement-batch/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; no actuation stake</span></td></tr>"
   "        <tr><td><code>:quality-standard/verify</code></td><td><span class=\"warn\">phase-3: human approval</span> &middot; HARD <code>:no-spec-basis</code> when the jurisdiction has no official spec-basis</td></tr>"
   "        <tr><td><code>:kiln-emissions/screen</code></td><td><span class=\"warn\">phase-3: human approval</span> &middot; HARD <code>:kiln-emissions-unresolved</code> on its own unresolved finding</td></tr>"
   "        <tr><td><code>:robotics/simulate-quality-lab-cell</code></td><td><span class=\"warn\">phase-3: human approval</span> &middot; runs the real <code>physics-2d</code> press simulation</td></tr>"
   "        <tr><td><code>:actuation/ship-cement-batch</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span> &middot; HARD <code>:evidence-incomplete</code> / <code>:robotics-simulation-missing</code> / <code>:robotics-simulation-out-of-tolerance</code> / <code>:cement-batch-strength-out-of-range</code> / <code>:already-shipped</code></td></tr>"
   "        <tr><td><code>:actuation/issue-mill-certificate</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span> &middot; HARD <code>:evidence-incomplete</code> / <code>:kiln-emissions-unresolved</code> / <code>:already-certified</code></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-cement-batches db)
        holds (filter #(= :governor-hold (:t %)) ledger)
        drafts (concat (store/shipment-history db) (store/certificate-history db))]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-2394 &middot; cement, lime and plaster</title><style>\n"
     (jp-go-dds.skin/dds+skin)
     "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of cement, lime and plaster (ISIC 2394) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · batch shipment and Mill Test Certificate issuance always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Cement batches</h2>\n"
     "    <p class=\"muted\">Build-time-generated from <code>cementmill.store</code> via <code>cementmill.render-html</code> (<code>clojure -M:dev:render-html</code>) — a real run of the actor, not a mock-up. The press-stress column is the peak compressive stress from an actual <code>physics-2d</code>-stepped press-platen / cube-specimen collision simulation of each batch's own recorded press-run configuration; the Kiln Governor re-runs it independently rather than trusting the mission's stored verdict.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Name</th><th>Jurisdiction</th><th>28-day strength</th><th>Simulated press stress</th><th>Kiln emissions</th><th>Actuation lifecycle</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Kiln Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden — they never reach a human approver at all. The governor independently recomputes the batch's own 28-day strength band and re-runs its press simulation; it never trusts a self-reported verdict. Double shipment and double certificate issuance are refused off dedicated <code>:batch-shipped?</code> / <code>:mill-certified?</code> facts, never a <code>:status</code> value.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — "
     (count ledger) " facts, of which <strong>" (count holds)
     "</strong> are HARD governor holds that never reached a human.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Batch</th><th>Basis</th><th>Summary / violation detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Draft records committed by this run</h2>\n"
     "    <p class=\"muted\">Constructed by <code>cementmill.registry</code> — a mill's own book-of-record draft. Every certificate this actor produces is UNSIGNED (<code>status: draft-unsigned</code>): signing is the mill's act, not the actor's. Nothing here touches a real plant or kiln control system.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Batch</th><th>Jurisdiction</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map draft-row drafts)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        hs (filter #(= :governor-hold (:t %)) (store/ledger db))]
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (spit out (render db))
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count hs) "HARD governor holds,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/certificate-history db)) "Mill-Test-Certificate drafts )")))
