(ns cementmill.governor
  "Kiln Governor -- the independent compliance layer that earns the
  Cement Mill Advisor the right to commit. The LLM has no notion of
  cement-quality-standard law, whether a batch's own measured 28-day
  compressive strength actually stays within its own recorded
  acceptance band, whether a kiln-stack-emissions finding against the
  batch has actually stayed unresolved, or when an act stops being a
  draft and becomes a real-world batch shipment or Mill-Test-
  Certificate issuance, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD -- the cement-mill analog
  of `automotive.governor`'s Automotive Governor
  (`cloud-itonami-isic-2910`).

  Six checks, in priority order: the first FIVE are HARD violations, a
  human approver CANNOT override them (you don't get to approve your
  way past a fabricated quality-standard spec-basis, incomplete
  evidence, a robot quality-lab mission that never ran or that
  independently re-checks out-of-tolerance, an out-of-spec batch, or
  an unresolved kiln-emissions finding). The sixth, the confidence/
  actuation gate, is SOFT: it
  asks a human to look (low confidence / actuation), and the human may
  approve -- but see `cementmill.phase`: for `:stake :actuation/ship-
  cement-batch`/`:actuation/issue-mill-certificate` (a real safety-
  critical act) NO phase ever allows auto-commit either. Two
  independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the requirements proposal cite
                                       an OFFICIAL source (`cementmill.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:actuation/ship-cement-
                                       batch`/`:actuation/issue-mill-
                                       certificate`, has the batch
                                       actually been verified with a
                                       full chemical-composition-test-
                                       report/28-day-compressive-
                                       strength-test-report/blaine-
                                       fineness-test-report/shipment-
                                       quality-chain-of-custody-record
                                       evidence checklist on file?
    3. Robot simulation missing or
       independently out-of-
       tolerance                    -- for `:actuation/ship-cement-
                                       batch`, has the robot quality-
                                       lab verification mission
                                       (`cementmill.robotics`)
                                       actually run and been recorded
                                       on the batch
                                       (`:robotics-sim-verified?`)? AND
                                       INDEPENDENTLY recompute whether
                                       the batch's own recorded 28-day
                                       compressive-strength reading
                                       falls out of its own recorded
                                       acceptance-band bounds
                                       (`cementmill.robotics/
                                       simulation-out-of-tolerance?`),
                                       ignoring whatever :passed?
                                       verdict the mission run itself
                                       stored -- the same 'ground
                                       truth, not self-report'
                                       discipline check 4 below uses.
    4. Cement-batch strength out of
       range                        -- for `:actuation/ship-cement-
                                       batch`, INDEPENDENTLY
                                       recompute whether the batch's
                                       own measured 28-day compressive
                                       strength falls outside its own
                                       recorded acceptance-band bounds
                                       (`cementmill.registry/cement-
                                       batch-strength-out-of-range?`)
                                       -- needs no proposal inspection
                                       or stored-verdict lookup at
                                       all. One of this fleet's two-
                                       sided range check family
                                       (`automotive.registry/vehicle-
                                       emissions-out-of-range?` and its
                                       own prior siblings established
                                       the pattern; `cementmill.
                                       robotics/strength-tolerance-
                                       out-of-range?` above shares the
                                       SAME field family, deliberately
                                       -- see that ns's docstring for
                                       why this differs from
                                       automotive's two DISTINCT
                                       fields).
    5. Kiln-emissions unresolved    -- reported by THIS proposal itself
                                       (a `:kiln-emissions/screen` that
                                       just found an unresolved
                                       out-of-limit finding), or
                                       already on file for the batch
                                       (`:kiln-emissions/screen`/
                                       `:actuation/issue-mill-
                                       certificate`). Evaluated
                                       UNCONDITIONALLY (not scoped to a
                                       specific op), the SAME
                                       discipline `automotive.governor/
                                       end-of-line-defect-unresolved-
                                       violations`/... (prior
                                       siblings)... established --
                                       exercised in tests/demo via
                                       `:kiln-emissions/screen`
                                       DIRECTLY, not via an actuation
                                       op against an unscreened batch
                                       -- see this ns's own test suite.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:actuation/
                                       ship-cement-batch`/`:actuation/
                                       issue-mill-certificate` (REAL
                                       safety-critical acts) ->
                                       escalate.

  Two more guards, double-shipment/double-certificate-issuance
  prevention, are enforced but NOT listed as numbered HARD checks
  above because they need no upstream comparison at all --
  `already-shipped-violations`/`already-certified-violations` refuse
  to ship a batch/issue a Mill Test Certificate for the SAME batch
  twice, off dedicated `:batch-shipped?`/`:mill-certified?` facts
  (never a `:status` value) -- the SAME 'check a dedicated boolean,
  not status' discipline every prior sibling governor's guards
  establish, informed by `cloud-itonami-isic-6492`'s status-lifecycle
  bug (ADR-2607071320)."
  (:require [cementmill.facts :as facts]
            [cementmill.registry :as registry]
            [cementmill.robotics :as robotics]
            [cementmill.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Shipping a real cement batch and issuing a real Mill Test
  Certificate are the two real-world actuation events this actor
  performs -- a two-member set, matching every prior dual-actuation
  sibling's shape."
  #{:actuation/ship-cement-batch :actuation/issue-mill-certificate})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:quality-standard/verify` (or actuation) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's cement-quality-standard requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:quality-standard/verify :actuation/ship-cement-batch :actuation/issue-mill-certificate} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は品質規格要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:actuation/ship-cement-batch`/`:actuation/issue-mill-
  certificate`, the jurisdiction's required chemical-composition-
  test-report/28-day-compressive-strength-test-report/blaine-
  fineness-test-report/shipment-quality-chain-of-custody-record
  evidence must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/ship-cement-batch :actuation/issue-mill-certificate} op)
    (let [a (store/cement-batch st subject)
          verification (store/quality-standard-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction a) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(化学成分試験報告書/28日強度試験報告書/ブレーン比表面積試験報告書/出荷検査連鎖記録等)が充足していない状態での提案"}]))))

(defn- robotics-simulation-violations
  "For `:actuation/ship-cement-batch`: HARD hold if the robot quality-
  lab verification mission (`cementmill.robotics`) never ran and was
  recorded on the batch (`:robotics-sim-verified?`), OR if it did but
  an INDEPENDENT recompute of the batch's own 28-day compressive-
  strength fields (`cementmill.robotics/simulation-out-of-tolerance?`)
  says out-of-tolerance right now -- never trusts the mission's own
  stored :passed? verdict alone, the same discipline `cement-batch-
  strength-out-of-range-violations` below uses."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-cement-batch)
    (let [a (store/cement-batch st subject)]
      (cond
        (not (:robotics-sim-verified? a))
        [{:rule :robotics-simulation-missing
          :detail (str subject " の品質ラボロボット検証ミッションが未実行・未合格")}]

        (robotics/simulation-out-of-tolerance? a)
        [{:rule :robotics-simulation-out-of-tolerance
          :detail (str subject " の28日強度実測値("
                       (:strength-28d-actual a) ")が独立再検証で許容範囲["
                       (:strength-28d-min a) "," (:strength-28d-max a) "]を逸脱")}]))))

(defn- cement-batch-strength-out-of-range-violations
  "For `:actuation/ship-cement-batch`, INDEPENDENTLY recompute whether
  the batch's own 28-day compressive strength falls outside its own
  recorded acceptance-band bounds via `cementmill.registry/cement-
  batch-strength-out-of-range?` -- needs no proposal inspection or
  stored-verdict lookup at all, since its inputs are permanent
  ground-truth fields already on the batch."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-cement-batch)
    (let [a (store/cement-batch st subject)]
      (when (registry/cement-batch-strength-out-of-range? a)
        [{:rule :cement-batch-strength-out-of-range
          :detail (str subject " の28日強度実測値(" (:strength-28d-actual a)
                      ")が許容範囲[" (:strength-28d-min a) "," (:strength-28d-max a) "]を逸脱")}]))))

(defn- kiln-emissions-unresolved-violations
  "An unresolved kiln-stack-emissions finding -- reported by THIS
  proposal (e.g. a `:kiln-emissions/screen` that itself just found
  one), or already on file in the store for the batch
  (`:kiln-emissions/screen`/`:actuation/issue-mill-certificate`) -- is
  a HARD, un-overridable hold. Evaluated UNCONDITIONALLY (not scoped
  to a specific op) so the screening op itself can HARD-hold on its
  own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        batch-id (when (contains? #{:kiln-emissions/screen :actuation/issue-mill-certificate} op) subject)
        hit-on-file? (and batch-id (= :unresolved (:verdict (store/kiln-emissions-screen-of st batch-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :kiln-emissions-unresolved
        :detail "未解決のキルン排ガス超過がある状態でのミルテスト証明書発行提案は進められない"}])))

(defn- already-shipped-violations
  "For `:actuation/ship-cement-batch`, refuses to ship a batch for the
  SAME batch twice, off a dedicated `:batch-shipped?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-cement-batch)
    (when (store/batch-already-shipped? st subject)
      [{:rule :already-shipped
        :detail (str subject " は既に出荷済み")}])))

(defn- already-certified-violations
  "For `:actuation/issue-mill-certificate`, refuses to issue a Mill
  Test Certificate for the SAME batch twice, off a dedicated
  `:mill-certified?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/issue-mill-certificate)
    (when (store/batch-already-certified? st subject)
      [{:rule :already-certified
        :detail (str subject " は既にミルテスト証明書発行済み")}])))

(defn check
  "Censors a Cement Mill Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (robotics-simulation-violations request st)
                           (cement-batch-strength-out-of-range-violations request st)
                           (kiln-emissions-unresolved-violations request proposal st)
                           (already-shipped-violations request st)
                           (already-certified-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
