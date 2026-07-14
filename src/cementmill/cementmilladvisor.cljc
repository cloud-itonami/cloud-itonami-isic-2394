(ns cementmill.cementmilladvisor
  "Cement Mill Advisor client -- the *contained intelligence node* for
  the cement-mill actor.

  It normalizes cement-batch intake, drafts a per-jurisdiction
  cement-quality-standard evidence checklist, screens batches for an
  unresolved kiln-stack-emissions finding, drafts the cement-batch-
  shipment action, and drafts the Mill-Test-Certificate-issuance
  action. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real batch shipment/Mill-Test-Certificate
  issuance. Every output is censored downstream by
  `cementmill.governor` before anything touches the SSoT, and
  `:actuation/ship-cement-batch`/`:actuation/issue-mill-certificate`
  proposals NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/ship-cement-batch | :actuation/issue-mill-certificate | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [cementmill.facts :as facts]
            [cementmill.registry :as registry]
            [cementmill.robotics :as robotics]
            [cementmill.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the batch, strength figures or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "バッチ記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :cement-batch/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-requirements
  "Per-jurisdiction cement-quality-standard evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `cementmill.facts` -- the Kiln Governor must reject this (never
  invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/cement-batch db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "cementmill.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :verification/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :verification/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-kiln-emissions
  "Kiln-stack-emissions screening draft. `:kiln-emissions-unresolved?`
  on the batch record injects the failure mode: the Kiln Governor must
  HOLD, un-overridably, on any unresolved out-of-limit finding."
  [db {:keys [subject]}]
  (let [a (store/cement-batch db subject)]
    (cond
      (nil? a)
      {:summary "対象バッチ記録が見つかりません" :rationale "no batch record"
       :cites [] :effect :kiln-emissions-screen/set :value {:batch-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:kiln-emissions-unresolved? a))
      {:summary    (str (:batch-name a) ": 未解決のキルン排ガス超過を検出")
       :rationale  "キルンスタック排ガススクリーニングが未解決の超過を検出。人手確認とホールドが必須。"
       :cites      [:kiln-emissions-check]
       :effect     :kiln-emissions-screen/set
       :value      {:batch-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:batch-name a) ": 未解決のキルン排ガス超過なし")
       :rationale  "キルン排ガススクリーニング完了。"
       :cites      [:kiln-emissions-check]
       :effect     :kiln-emissions-screen/set
       :value      {:batch-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- simulate-quality-lab-cell
  "Runs the robot quality-lab verification mission
  (`cementmill.robotics`) and drafts its result as a proposal. This now
  ACTUALLY runs a real `physics-2d`-backed time-stepped press-platen /
  cube-specimen collision simulation (ADR-2607152000, extending the
  automotive pilot ADR-2607151600) -- the batch's own recorded
  `:press-platen-mass-kg` press-run configuration becomes an actual
  `Body2D` stepped tick-by-tick into a static cube specimen; see
  `cementmill.robotics/simulate-quality-lab-cell`'s docstring. High
  confidence -- the mission itself is deterministic simulated telemetry
  derived from the batch's own recorded press-run configuration (never
  an LLM guess); the Kiln Governor still independently re-derives
  :passed? from the real telemetry fields this drafts before any
  `:actuation/ship-cement-batch` proposal may commit -- see
  `cementmill.governor`'s `robotics-simulation-violations`."
  [db {:keys [subject]}]
  (let [a (store/cement-batch db subject)]
    (if (nil? a)
      {:summary "対象バッチ記録が見つかりません" :rationale "no batch record"
       :cites [] :effect :cement-batch/upsert :value {:id subject :robotics-sim-verified? false}
       :stake nil :confidence 0.0}
      (let [{:keys [mission actions passed? sim-peak-compressive-force-n sim-peak-compressive-stress-mpa]}
            (robotics/simulate-quality-lab-cell subject a)]
        {:summary    (str subject ": 品質ラボロボット検証ミッション " (if passed? "合格" "不合格"))
         :rationale  (str "mission=" (:mission/id mission) " actions=" (count actions)
                          " sim-peak-compressive-stress-mpa=" sim-peak-compressive-stress-mpa
                          " strength-28d-band=[" (:strength-28d-min a) "," (:strength-28d-max a) "]")
         :cites      [(:mission/id mission)]
         :effect     :cement-batch/upsert
         :value      {:id subject
                      :robotics-sim-verified? passed?
                      :sim-peak-compressive-force-n sim-peak-compressive-force-n
                      :sim-peak-compressive-stress-mpa sim-peak-compressive-stress-mpa
                      :robotics-sim-record {:mission-id (:mission/id mission)
                                            :actions (mapv #(dissoc % :action) actions)
                                            :passed? passed?}}
         :stake      nil
         :confidence 0.95}))))

(defn- propose-ship-cement-batch
  "Draft the actual CEMENT-BATCH-SHIPMENT action -- shipping a real
  cement batch to a customer or distributor. ALWAYS `:stake
  :actuation/ship-cement-batch` -- this is a REAL-WORLD safety-
  critical act, never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`cementmill.phase`); the governor also always escalates on
  `:actuation/ship-cement-batch`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [a (store/cement-batch db subject)]
    {:summary    (str subject " 向け出荷実行提案"
                      (when a (str " (batch=" (:batch-name a) ")")))
     :rationale  (if a
                   (str "strength-28d-actual=" (:strength-28d-actual a)
                        " spec=[" (:strength-28d-min a) "," (:strength-28d-max a) "]")
                   "バッチ記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :cement-batch/mark-shipped
     :value      {:batch-id subject}
     :stake      :actuation/ship-cement-batch
     :confidence (if (and a (not (registry/cement-batch-strength-out-of-range? a))) 0.9 0.3)}))

(defn- propose-issue-mill-certificate
  "Draft the actual MILL-TEST-CERTIFICATE action -- issuing a real
  Mill Test Certificate certifying a cement batch's composition and
  strength. ALWAYS `:stake :actuation/issue-mill-certificate` -- this
  is a REAL-WORLD safety-critical act, never a draft the actor may
  auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`cementmill.phase`); the governor also always
  escalates on `:actuation/issue-mill-certificate`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/cement-batch db subject)]
    {:summary    (str subject " 向けミルテスト証明書発行提案"
                      (when a (str " (batch=" (:batch-name a) ")")))
     :rationale  (if a
                   "jurisdiction-evidence-checklist referenced"
                   "バッチ記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :cement-batch/mark-certified
     :value      {:batch-id subject}
     :stake      :actuation/issue-mill-certificate
     :confidence (if a 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :cement-batch/intake                     (normalize-intake db request)
    :quality-standard/verify                 (verify-requirements db request)
    :kiln-emissions/screen                   (screen-kiln-emissions db request)
    :robotics/simulate-quality-lab-cell      (simulate-quality-lab-cell db request)
    :actuation/ship-cement-batch             (propose-ship-cement-batch db request)
    :actuation/issue-mill-certificate        (propose-issue-mill-certificate db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはセメント工場の出荷実行・ミルテスト証明書発行エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:cement-batch/upsert|:verification/set|:kiln-emissions-screen/set|"
       ":cement-batch/mark-shipped|:cement-batch/mark-certified) "
       "(:robotics/simulate-quality-lab-cell も :cement-batch/upsert で "
       ":robotics-sim-verified? を提案する) "
       ":stake(:actuation/ship-cement-batch か :actuation/issue-mill-certificate か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :quality-standard/verify                 {:batch (store/cement-batch st subject)}
    :kiln-emissions/screen                   {:batch (store/cement-batch st subject)}
    :robotics/simulate-quality-lab-cell      {:batch (store/cement-batch st subject)}
    :actuation/ship-cement-batch              {:batch (store/cement-batch st subject)}
    :actuation/issue-mill-certificate         {:batch (store/cement-batch st subject)}
    {:batch (store/cement-batch st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Kiln Governor escalates/
  holds -- an LLM hiccup can never auto-ship a cement batch or auto-
  issue a Mill Test Certificate."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :cementmilladvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
