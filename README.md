# cloud-itonami-isic-2394

Open Business Blueprint for **ISIC Rev.5 2394**: manufacture of
cement, lime and plaster -- clinker-kiln operation, finish-mill
quality control and Mill-Test-Certificate issuance for a community
cement plant.

This repository publishes a cement-mill actor -- cement-batch intake,
per-jurisdiction cement-quality-standard evidence checklist
verification, kiln-stack-emissions screening, robot quality-lab
verification and dual actuation (batch shipment + Mill Test
Certificate issuance) -- as an OSS business that any qualified cement
mill can fork, deploy, run, improve and sell, so a mill keeps its own
production and quality-standard history instead of renting a closed
MES / quality SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **Cement Mill Advisor ⊣
Kiln Governor**.

## Scope note: mid-chain milling, not quarrying or on-site construction

This repository is scoped to **manufacturing cement** from already-
quarried raw material (finish-mill grinding, kiln clinkering, quality
control, shipment) -- the middle tier of the construction value chain,
between raw-material extraction and building erection. Distinct from:

- `cloud-itonami-isic-0810` — Community Quarry and Stone Supply: the
  UPSTREAM sibling one step earlier in the chain. Quarries extract and
  ship raw limestone/aggregate; this repo's cement mill is one of
  quarryops's customers, not the other way around. Neither repo models
  the other's operations.
- `cloud-itonami-isic-4211` — Community Building Construction: the
  DOWNSTREAM sibling one step later in the chain. Construction sites
  consume finished cement (this repo's shipped product) to build
  structures; this repo does not model site work, permits or
  building-code inspection.
- `cloud-itonami-isic-2395` — manufacture of articles of concrete,
  cement and plaster (a further downstream, higher-value-add
  fabrication step; not yet a full actor in this fleet).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (sampling arm,
compressive-strength-test press, Blaine-fineness scanner) operate
under an actor that proposes actions and an independent **Kiln
Governor** that gates them. The governor never issues a Mill Test
Certificate itself; `:high`/`:safety-critical` actions
(`:actuation/ship-cement-batch`, `:actuation/issue-mill-certificate`)
require human sign-off.

**Robot process simulation is concrete, not just a flag** (replicating
the pattern ADR-2607142800 established, extending ADR-2607011000):
`cementmill.robotics` walks every cement batch through a robot-
executed quality-lab verification mission (`kotoba.robotics`
mission/action/telemetry-proof contracts) -- automated cube-specimen
sampling, a compressive-strength-test press, a Blaine-fineness scan --
before `:actuation/ship-cement-batch` is proposable.

**This is now a REAL engineering simulation, not a synthetic field
comparison (ADR-2607152000, extending automotive's pilot
ADR-2607151600 to this vertical).** This repository takes a REAL
git-coordinate dependency on
[`kotoba-lang/physics-2d`](https://github.com/kotoba-lang/physics-2d)
(pinned by SHA in `deps.edn`), and `cementmill.robotics/simulate-
quality-lab-cell` actually calls it: a real time-stepped rigid-body
press-collision simulation -- a press-platen `Body2D` (the batch's own
recorded `:press-platen-mass-kg` press-run configuration) closes at a
disclosed controlled velocity onto a static (mass 0) ASTM C109/C109M
50 mm cube-specimen `Body2D`, actually stepped tick-by-tick via
`physics-2d/world-step`. Unlike automotive's pairing with a separate
`kami-engine-vehicle-designer` design-library repo, this vertical has
no design-library sibling -- the physics module lives directly inside
`cementmill.robotics`, taking a real dependency on `physics-2d` alone
(ADR-2607152000's own key simplification). The Kiln Governor
independently re-derives the batch's own real simulated press telemetry
(`:sim-peak-compressive-stress-mpa`) against the batch's OWN existing
recorded 28-day compressive-strength acceptance band
(`:strength-28d-min`/`:strength-28d-max` -- a real, already-established
anchor, reused here, not a newly invented ceiling), never trusting the
mission's self-reported verdict alone. Honest scope: the physics is a
2D projection, the cube-specimen is treated as a static (mass 0)
immovable anchor with no material-stiffness model at all (so the
platen's own recorded press-run mass, not the specimen's real
strength, is what varies the simulated reading), and the platen's
closing velocity is a disclosed ANALOG rate (not a literal reproduction
of EN 12390-3's/ASTM C39's mm/min-scale controlled-loading-rate
conventions) -- see `cementmill.robotics`'s namespace docstring for the
full, disclosed derivation. This real-engine wiring extends automotive's
pilot to this vertical; the remaining cloud-itonami manufacturing
actors are extended per ADR-2607152000's own fleet-extension plan.

## Core contract

```text
cement-batch intake + quality-standard rules verify + kiln-emissions screen
  -> Cement Mill Advisor proposal
  -> Kiln Governor (HARD holds un-overridable)
  -> phase gate (actuation always escalates)
  -> human approval for high stakes
  -> append-only ledger + draft records
```

## Actuation honesty

Shipping a cement batch and issuing a Mill Test Certificate produce
**unsigned draft records and ledger facts only**. This actor does not
talk to real plant control systems or quality-standard-body portals.
Signature and physical dispatch are the cement mill's own acts.

## Ops

| Op | Effect |
|---|---|
| `:cement-batch/intake` | normalize cement-batch directory patch (phase 3 may auto-commit when clean) |
| `:quality-standard/verify` | per-jurisdiction cement-quality-standard evidence checklist (always human) |
| `:kiln-emissions/screen` | kiln-stack-emissions screen (HARD hold if unresolved out-of-limit finding) |
| `:robotics/simulate-quality-lab-cell` | robot quality-lab verification mission (always human; required on file before shipment) |
| `:actuation/ship-cement-batch` | draft cement-batch-shipment record (always human; HARD hold if robotics-sim missing/out-of-tolerance or strength out of range) |
| `:actuation/issue-mill-certificate` | draft Mill Test Certificate record (always human; HARD hold if kiln-emissions unresolved) |

## Social / regulatory hand-off

```clojure
(require '[cementmill.store :as store]
         '[cementmill.export :as export])

(def db (store/seed-db))
(export/audit-package db)           ;; EDN maps for quality-standard/regulator hand-off
(export/package->csv-bundle db)     ;; CSV bundle (cement-batches/ledger/shipments/mill-certificates)
```

Operator console (static sample): `docs/samples/operator-console.html`.

## Develop

```bash
clojure -M:dev:test
clojure -M:lint
clojure -M:dev:run
```

## License

AGPL-3.0-or-later — see `LICENSE`.

## Operator console (Pages)

After enabling GitHub Pages (Settings → Pages → GitHub Actions), the
static console would be at:

https://cloud-itonami.github.io/cloud-itonami-isic-2394/

Local: open `docs/samples/operator-console.html`.

## Export audit package (CLI)

```bash
clojure -M:dev:export
# or: clojure -M:dev:export /tmp/audit-2394
```

Writes CSV files under `out/audit-package/` (or the given directory).
