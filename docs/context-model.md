# Offline context model

## Runtime contract

Android and iOS package the same byte-for-byte `context-model.bin` and decode
the same signed 32-bit costs. The model is immutable. Loading and conversion do
not use the network, and typed input or candidate history is not persisted.

The converter keeps the existing adjusted word cost, POS connection cost, and
structural penalties. While each lattice edge is expanded it adds an independent
context term:

```text
pathCost += wordCost + connectionCost + structuralPenalty + contextCost
```

Content POS IDs `{2, 3, 4, 7, 8, 9, 12}` update the prior context surface.
Particles and auxiliaries `{5, 6}` preserve it, so `学校 + へ + 行きます`
uses the `学校→行きます` entry. A copy edge or another POS class clears it.
When a leading bunsetsu is confirmed, suffix conversion receives the same
content-surface state together with the confirmed right POS ID.
An exact surface bigram is tried first; a miss backs off to the next-surface
unigram. The v1 unigram calibration is neutral (zero), preserving the original
word/POS decision when no contextual evidence exists.

Context cost is part of the path before beam pruning. It is not an after-the-
fact reordering of the eighth candidate. The prior surface is part of the beam
state key, the production beam width is 12, and output remains capped at eight.
The shared context fixture compares every returned surface and integer cost on
both platforms. For all four acceptance examples, beam 12 returns the same
eight results as a beam-64 reference, and requesting only one result keeps the
same top-1.

## Model identity

| Field | v1 value |
| --- | --- |
| Format / model version | `1 / 1` |
| Unigrams / bigrams | `2,048 / 8,194` |
| Count source SHA-256 | `5275f10c2273647da817b8ba49558d82406e9b1435ca5246094b4d67097001ba` |
| Binary SHA-256 | `22e83b94a259d900d5d60c058d9ba6773506ee43fc81b4f644da02a6495b2fc3` |
| Binary size | 219,009 bytes (213.9 KiB) |

Generation details, pinned upstream hashes, and exact commands are in
`tools/context_model/README.md`. The source corpus is the 2026-07-11 Tatoeba
Japanese sentence export, attributed to Tatoeba contributors and licensed
CC BY 2.0 FR. The compiled artifact is a transformed, selected, and quantized
aggregate; complete source sentences are not distributed in the app.

## Quality and performance gate

The 57-case shared Wave 2 corpus is evaluated twice in one test: first with an
empty context model, then with v1. Android and iOS must produce identical model
metadata, exact target N-best results, and integer costs. The contextual result
must improve overall top-1, must not reduce top-1 in any category, and must not
reduce per-category top-3 or N-best containment, increase the overall prohibited
candidate count, or break any baseline-passing case.

Current quality and latency measurements are recorded in
`docs/conversion-quality-baseline.md`. Android instrumentation and iOS XCTest
both rotate through the four acceptance readings and report dictionary-included
conversion p95, baseline/context engine p95, delta, and model bytes. These are
environment-specific engineering measurements, not device-wide guarantees.
