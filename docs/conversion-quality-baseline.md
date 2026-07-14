# Conversion quality baseline

Measured on 2026-07-14 before the shared Android/iOS scoring implementation,
using `app/src/main/assets/reading.db` at Git blob
`9e4c438664fedbb29552bca324b7c10a62bfea96` and the 54 cases in
`fixtures/sentence-conversion-regression.json`.

The Android baseline applies the existing Android-only word-cost adjustment.
The iOS baseline uses the raw database word cost, matching the implementations
before scoring parity. `baselineKnownFailureIds` in the fixture records the
Android baseline because the shared scorer adopts the existing Android formula
as its normative behavior.

| Platform | Top-1 | Required candidates in top 3 | Required candidates in top 8 | Forbidden candidates | Fully passing cases |
| --- | ---: | ---: | ---: | ---: | ---: |
| Android | 13/43 | 21/54 | 24/54 | 14 | 10/54 |
| iOS | 7/43 | 19/54 | 25/54 | 15 | 8/54 |

Top-1 excludes the 11 cases whose `expectedTop1` is `null`. A case is fully
passing only when its optional top-1 expectation matches, all required N-best
candidates are present, and no forbidden candidate is present.

## Wave 2 result

The Wave 2 katakana integration expands the shared corpus with three direct
loanword cases (57 total). On Android's shared converter, which is also fixed
as the iOS parity contract, the result is:

| Scope | Top-1 | Required in top 3 | Required in top 8 | Fully passing | Baseline-pass regressions |
| --- | ---: | ---: | ---: | ---: | ---: |
| All 57 cases | 23/46 | 32/57 | 36/57 | 16/57 | 0 |
| Katakana loanwords | 9/9 | 9/9 | 9/9 | 5/9 | 0 |

The original six katakana cases had 0/6 Top-1 and 0/6 Top-3 at baseline. The
three new direct cases require `プロジェクト`, `リリース`, and
`スカイツリー` at Top-1 and prohibit their fragmented mixed-script forms.
All previously passing expectations outside the category remain passing.

## Offline context result

The version-1 surface-bigram model was measured against the same 57 Wave 2
cases. Each platform evaluates the empty-model baseline and context model in the
same process. Android and iOS produced the same N-best ranking and integer costs
for the four context acceptance cases.

| Scope | Baseline top-1 | Context top-1 | Baseline top 3 | Context top 3 | Baseline top 8 | Context top 8 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| All 57 cases | 23/46 (50.0%) | 30/46 (65.2%) | 32/57 | 35/57 | 36/57 | 38/57 |

Fully passing cases increased from 16/57 to 20/57. No case that fully passed
the empty-model baseline regressed. Category top-1 results were:

| Category | Baseline | Context |
| --- | ---: | ---: |
| Katakana loanwords | 9/9 | 9/9 |
| Particles / auxiliaries | 3/6 | 5/6 |
| Homophones | 3/5 | 3/5 |
| Proper nouns | 2/6 | 4/6 |
| Numbers | 0/6 | 0/6 |
| Phrase boundaries | 2/3 | 2/3 |
| Conjugation | 2/6 | 5/6 |
| Multi-phrase | 2/5 | 2/5 |

Unknown-word cases have no top-1 denominator in this fixture. A non-neutral
unigram frequency backoff (0--56 points) was also evaluated, but reduced
homophone top-1 from 3/5 to 2/5 and katakana top-1 from 9/9 to 8/9. It was
rejected; model v1 backs an unknown bigram off to a neutral unigram cost.

## Size and warm p95

Measured on 2026-07-14 by rotating through the four acceptance readings after
four paired baseline/context warm-up rounds. Conversion includes dictionary
lookup; engine excludes lookup by reusing the lexemes and connection table.
Baseline and context samples are interleaved to limit JIT, cache, and thermal
ordering bias.

| Runtime / build | Baseline conversion p95 | Context conversion p95 | Baseline engine p95 | Context engine p95 |
| --- | ---: | ---: | ---: | ---: |
| Android 37 arm64 Pixel 10 Pro AVD, Play Debug | 19 ms | 19 ms (0 ms) | 13 ms | 14 ms (+1 ms) |
| iOS 26 arm64 simulator, Release `-O` | 41.011 ms | 43.437 ms (+2.427 ms) | 13.612 ms | 11.822 ms (-1.789 ms) |

The p95 uses 20 samples per mode for dictionary-included conversion and 40 per
mode for engine-only conversion. Simulator values are engineering comparisons,
not physical-device guarantees. The tests fail when context p95 exceeds the
paired baseline by more than 25% plus a small timer allowance.

| Size item | Measurement |
| --- | ---: |
| Shared model, Android and iOS | 219,009 bytes (213.9 KiB) |
| Model as compressed Android APK entry | 92,245 bytes (90.1 KiB) |
| Android Play Debug APK containing the model | 83,760,605 bytes (79.88 MiB) |
| iOS Release simulator app, logical files excluding XCTest | 155,480,134 bytes (148.28 MiB) |
| iOS Release simulator keyboard extension | 136,241,780 bytes (129.93 MiB) |

The simulator bundles are universal development artifacts and are not App
Store download-size estimates. The shared model's raw size is the relevant iOS
increment; the compressed APK entry is the relevant Android asset increment.
