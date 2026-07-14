# ADR-0001: Cement Mill Advisor ⊣ Kiln Governor architecture

- Status: Accepted (2026-07-15)
- Repository: `cloud-itonami-isic-2394` (ISIC Rev.5 `2394`)

## Context

A 2026-07-14 value-chain survey of the construction industry found
raw-material extraction (`cloud-itonami-isic-0810`, quarrying) and
building-construction execution (`cloud-itonami-isic-4211`) both
implemented, but the building-materials-manufacturing tier in between
-- ISIC 2394, manufacture of cement, lime and plaster -- had NO actor
at all. Construction's raw quarried stone/limestone must become
cement before any building gets built; this mid-chain tier was a gap
in an otherwise-covered value chain.

This vertical needs the same governed-actor pattern as the rest of
the cloud-itonami fleet: an untrusted advisor proposes; an independent
governor may HOLD; high-stakes actuation never auto-commits. It also
adopts the concrete robotics-process-simulation pattern
ADR-2607142800 established (reference implementation:
`cloud-itonami-isic-2910`'s `automotive.robotics`) from day one,
rather than declaring `:robotics` as a flag without exercising it.

## Decision

1. Namespaces live under `cementmill.*` with the standard
   facts / registry / store / governor / phase / advisor / operation /
   sim / export / robotics shape.
2. Entity is a **cement batch** (a production batch/lot), not a
   vehicle, aircraft assembly or hull block.
3. Dual actuation on the same entity:
   - `:actuation/ship-cement-batch` (cement-batch-shipment draft)
   - `:actuation/issue-mill-certificate` (Mill Test Certificate draft)
4. Double-actuation guards use dedicated booleans
   (`:batch-shipped?`, `:mill-certified?`), never a status lifecycle
   (ADR-2607071320 / 6492 lesson).
5. `cement-batch-strength-out-of-range?` continues the fleet
   two-sided range check family (after testlab / conservation / water
   / steelworks / turbine / automotive), applied here to a cement
   batch's own measured 28-day compressive strength against its own
   recorded acceptance-band bounds -- the single most standard real
   cement QC/acceptance metric.
6. Robotics premise made concrete from day one (ADR-2607142800
   pattern): `cementmill.robotics` walks a batch through a three-step
   quality-lab mission (cube-specimen sampling, compressive-strength-
   test press, Blaine-fineness scan) via `kotoba.robotics` mission/
   action/telemetry-proof contracts. `cementmill.governor`'s
   `robotics-simulation-violations` requires the mission on file AND
   independently re-derives out-of-tolerance from the batch's own
   28-day strength fields, never trusting the mission's self-reported
   verdict. Deliberately grounded in the SAME field family as check 5
   (unlike automotive's two DISTINCT fields -- structural-deviation
   for the CAE mission, emissions-deviation for dispatch) because
   28-day compressive strength is the single most standard cement
   QC/acceptance metric and is literally what the compressive-
   strength-test-press mission step measures; both checks co-fire
   whenever a batch's strength is genuinely out of range (see
   `cementmill.robotics`/`cementmill.governor` ns docstrings).
7. Kiln-emissions unresolved is evaluated unconditionally so
   `:kiln-emissions/screen` itself can HARD-hold (the same discipline
   `automotive.governor/end-of-line-defect-unresolved-violations` and
   its own prior siblings established).
8. Spec-basis catalog seeds JPN (JIS R 5210 / JISC + Japan Cement
   Association) / USA (ASTM C150/C150M / PCA) / GBR (BS EN 197-1 / BSI
   national adoption of EN 197-1) / DEU (DIN EN 197-1 / DIN national
   adoption of EN 197-1) only; missing jurisdictions are uncovered,
   never fabricated.

## Consequences

(+) The construction value chain's mid-tier manufacturing gap is
closed: quarrying (0810) -> cement milling (2394, this repo) ->
building construction (4211) is now a fully-implemented chain.
(+) Reuses langgraph + store dual-backend parity without new physics.
(+) Robotics premise is exercised from day one, not retrofitted later.
(−) No physical plant digital-twin tick in this repo (follow-up domain
data is out of scope here).
(−) Quality-standard-body coverage is a starting catalog (four
jurisdictions), not exhaustive.
(−) Robotics-simulation and ground-truth-range checks sharing one
field family means they always co-fire when a batch's strength is out
of range -- a deliberate, documented departure from automotive's
two-distinct-field design, not a design defect (see governor ns
docstring).

## Related

- ADR-2607142800 (robotics premise -> concrete process simulation,
  fleet pattern; identifies isic-2394 as a follow-up gap)
- ADR-2607011000 (robotics premise + ISIC section coverage)
- Sibling architecture: `cloud-itonami-isic-2910` docs/adr/0001
  (`automotive.robotics`, the reference implementation this repo
  replicates), `cloud-itonami-isic-0810` docs/adr/0001 (upstream
  quarrying sibling), `cloud-itonami-isic-4211` docs/adr/0001
  (downstream construction sibling)
