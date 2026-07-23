(ns cementmill.facts
  "Per-jurisdiction cement-quality-standard catalog -- the G2-style
  spec-basis table the Kiln Governor checks every
  `:quality-standard/verify` proposal against.

  Coverage is reported HONESTLY: a jurisdiction not in this table has
  NO spec-basis. Seed values cite official cement product-standard
  bodies; this is a starting catalog, not a survey of every market.

  Real anchors seeded here:
    JPN -- JIS R 5210 (Portland cement), administered by Japan's
           JISC (日本産業標準調査会) with 一般社団法人セメント協会
           (the Japan Cement Association) as the industry body.
    USA -- ASTM C150/C150M (Standard Specification for Portland
           Cement), ASTM International, with the Portland Cement
           Association (PCA) as the industry body.
    GBR/DEU -- EN 197-1 (Cement -- Composition, specifications and
           conformity criteria), published by CEN, adopted nationally
           as BS EN 197-1 (UK, via BSI) and DIN EN 197-1 (Germany, via
           DIN) -- the same 'one international regime, several
           national adoptions' shape `automotive.facts` uses for
           UNECE WVTA (GBR/DEU).
    BRA -- Resolucao CONAMA no 237, de 19 de dezembro de 1997 (Art. 2o
           c/c Anexo 1), administered by CONAMA (Conselho Nacional do
           Meio Ambiente / Ministerio do Meio Ambiente) with IBAMA as
           the principal federal licensing executor -- unlike JPN/USA/
           GBR/DEU's product-quality standards, Brazil's binding
           anchor here is the prior ENVIRONMENTAL LICENSING duty:
           Anexo 1 names 'producao de material ceramico, cimento,
           gesso, amianto e vidro' among the activities that cannot
           site, install or operate without a Licenca Previa/
           Instalacao/Operacao from the competent environmental
           agency."
  )

(def catalog
  {"JPN" {:name "Japan"
          :owner-authority "日本産業標準調査会 (JISC) / 一般社団法人セメント協会 (Japan Cement Association)"
          :legal-basis "JIS R 5210:2019 ポルトランドセメント (参考)"
          :national-spec "ポルトランドセメントの化学成分・物理性能(強さ)要件"
          :provenance "https://www.jisc.go.jp/"
          :required-evidence ["化学成分試験報告書 (chemical-composition-test-report)"
                              "28日強度試験報告書 (28-day-compressive-strength-test-report)"
                              "ブレーン比表面積試験報告書 (blaine-fineness-test-report)"
                              "出荷検査連鎖記録 (shipment-quality-chain-of-custody-record)"]}
   "USA" {:name "United States"
          :owner-authority "ASTM International / Portland Cement Association (PCA)"
          :legal-basis "ASTM C150/C150M Standard Specification for Portland Cement (reference)"
          :national-spec "US Portland-cement type-classification and physical/chemical requirements"
          :provenance "https://www.astm.org/"
          :required-evidence ["chemical-composition-test-report"
                              "28-day-compressive-strength-test-report"
                              "blaine-fineness-test-report"
                              "shipment-quality-chain-of-custody-record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "BSI (British Standards Institution) / CEN"
          :legal-basis "BS EN 197-1:2011 Cement -- Composition, specifications and conformity criteria (UK national adoption of EN 197-1, reference)"
          :national-spec "UK conformity-of-production requirements for common cements"
          :provenance "https://www.bsigroup.com/"
          :required-evidence ["chemical-composition-test-report"
                              "28-day-compressive-strength-test-report"
                              "blaine-fineness-test-report"
                              "shipment-quality-chain-of-custody-record"]}
   "DEU" {:name "Germany"
          :owner-authority "DIN (Deutsches Institut für Normung) / CEN"
          :legal-basis "DIN EN 197-1 Zement -- Zusammensetzung, Anforderungen und Konformitätskriterien (deutsche Umsetzung von EN 197-1, Referenz)"
          :national-spec "Deutsche Konformitätsanforderungen für Normalzemente"
          :provenance "https://www.din.de/"
          :required-evidence ["Chemische-Zusammensetzungsprüfbericht (chemical-composition-test-report)"
                              "28-Tage-Druckfestigkeitsprüfbericht (28-day-compressive-strength-test-report)"
                              "Blaine-Feinheitsprüfbericht (blaine-fineness-test-report)"
                              "Versand-Rückverfolgbarkeitsnachweis (shipment-quality-chain-of-custody-record)"]}
   ;; BRA anchor differs in KIND from JPN/USA/GBR/DEU above: those cite a
   ;; product-quality standard (composition/strength spec a cement must
   ;; meet). Brazil's is a prior environmental-LICENSING duty -- the
   ;; undertaking itself (siting/installing/operating a cement, lime or
   ;; plaster plant) cannot lawfully proceed without a Licença Prévia/
   ;; Instalação/Operação from the competent environmental agency.
   ;; Verified 2026-07-23 by fetching the official PDF directly from
   ;; conama.mma.gov.br (Content-Disposition: CONAMA_RES_CONS_1997_237.pdf)
   ;; and running pdftotext -- quoted text below is copied verbatim from
   ;; that extraction, not paraphrased from memory.
   "BRA" {:name "Brazil"
          :owner-authority "CONAMA (Conselho Nacional do Meio Ambiente, Ministério do Meio Ambiente) / IBAMA (Instituto Brasileiro do Meio Ambiente e dos Recursos Naturais Renováveis)"
          :legal-basis "Resolução CONAMA nº 237, de 19 de dezembro de 1997 -- Art. 2º: \"A localização, construção, instalação, ampliação, modificação e operação de empreendimentos e atividades utilizadoras de recursos ambientais consideradas efetiva ou potencialmente poluidoras ... dependerão de prévio licenciamento do órgão ambiental competente\"; Anexo 1, Indústria de produtos minerais não metálicos: \"fabricação e elaboração de produtos minerais não metálicos tais como: produção de material cerâmico, cimento, gesso, amianto e vidro, entre outros.\" (reference; retrieved 2026-07-23)"
          :national-spec "Prior environmental licensing (Licença Prévia -> Licença de Instalação -> Licença de Operação, Art. 8º) required before siting, constructing, installing, expanding, modifying or operating Brazilian cement/lime/plaster (cimento/cal/gesso) manufacturing and their mineral-extraction (open-pit/underground quarrying) operations, per Anexo 1's non-metallic-mineral-products and mineral-extraction listings"
          :provenance "https://conama.mma.gov.br/?option=com_sisconama&task=arquivo.download&id=237"
          :required-evidence ["Licença Prévia (environmental-preliminary-license-lp)"
                              "Licença de Instalação (environmental-installation-license-li)"
                              "Licença de Operação (environmental-operating-license-lo)"
                              "enquadramento no Anexo 1 -- produção de material cerâmico, cimento, gesso (annex-1-nonmetallic-mineral-products-activity-record)"]}})

(defn spec-basis [iso3] (get catalog iso3))

(defn coverage
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-2394 R0: " (count catalog)
                 " jurisdictions seeded. Extend `cementmill.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
