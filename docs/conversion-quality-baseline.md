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
