# Operator Guide

## First Deployment
1. Register quality-control engineers, mills, cement batches, personnel and robots.
2. Import historical batch / kiln-emissions / quality-standard records.
3. Run read-only validation and robot quality-lab mission dry-runs.
4. Configure cement-quality-standard evidence checklists and human sign-off paths.
5. Publish a dry-run audit export.

## Minimum Production Controls
- governor gate on every robot action before shipment
- human sign-off for `:high`/`:safety-critical` robot actions (e.g. quality-lab sampling/testing on safety-critical batches, Mill Test Certificate issuance)
- audit export for every shipment, sign-off and disclosure
- backup manual process

## Certification
Certified operators must prove robot-safety integrity, evidence-backed
records and human review for safety-affecting actions.

## Operating states
intake : quality-standard-rules-verify : kiln-emissions-screen : approve : ship-cement-batch : issue-mill-certificate : audit

## Audit export (social operation)

After a production session, export the append-only package for
quality-standard inspectors or internal compliance:

```clojure
(require '[cementmill.store :as store]
         '[cementmill.export :as export])
(export/audit-package store)        ; EDN maps
(export/package->csv-bundle store)  ; CSV files as string map
```

Drafts remain **unsigned** — signing and submission to a quality-
standard body are the cement mill's own acts (see README Actuation
honesty).

Static UI sample: `docs/samples/operator-console.html`.
