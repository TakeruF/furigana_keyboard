# Kana-kanji conversion scoring contract

This document is the cross-platform contract for Android and iOS conversion.
All rules below are normative. The bundled `reading.db` is the source of the
raw word and connection costs; clients must not add platform-specific ranking
heuristics.

## Text model

- Input strings are well-formed Unicode.
- A code point means a Unicode scalar value. Extended grapheme clusters
  (`Character` in Swift) are not used by conversion scoring or lattice
  offsets.
- `ConversionLexeme.start`, `ConversionLexeme.end`, and the matching segment
  fields are zero-based Unicode-scalar offsets into `reading`; `end` is
  exclusive.
- Strings are not normalized. Equality and deterministic ordering compare the
  numeric Unicode scalar sequence. Ordering is lexicographic by scalar value,
  with the shorter sequence first when one is a prefix of the other.

## Adjusted word cost

For a selected `conversion_lexeme` row, calculate:

```text
kanaContentPenalty =
    500 when surface and reading have exactly the same scalar sequence
             and left_id is one of {3, 4, 7, 8, 9}
      0 otherwise

perHanPenalty(frequency) =
    min(frequency, 3000) / 100 when frequency > 0
    3000 / 100                 when frequency is absent or <= 0

frequencyPenalty =
    0 when surface contains no Han scalar
    floor(sum(perHanPenalty(each Han occurrence)) / Han occurrence count)
      otherwise

adjustment = kanaContentPenalty + frequencyPenalty
             - UnicodeScalarCount(surface)

adjustedWordCost = clampInt32(rawWordCost + adjustment)
```

Every Han occurrence participates, including repeated scalars. Division is
non-negative integer division. `clampInt32` saturates below `-2147483648` and
above `2147483647`; it never wraps.

Database word and connection costs are first saturated to signed 32-bit
integers when read. Thus `rawWordCost` in the formula is already a signed
32-bit value on both platforms.

For this version of the contract, a Han scalar is a value in one of these
inclusive ranges (or one of the listed singletons):

```text
2E80-2EFF, 2F00-2FDF, 3005, 3007, 3021-3029, 3038-303B,
3400-4DBF, 4E00-9FFF, F900-FAFF, 16FE2-16FE3, 16FF0-16FF1,
20000-2EE5F, 2F800-2FA1F, 30000-323AF
```

Using explicit ranges prevents the Java and Apple Unicode database versions
from changing conversion results independently.

## Dictionary selection and path cost

For each distinct token reading, clients first select at most 12 database rows
in ascending `(word_cost, form_rank, surface scalar sequence, left_id,
right_id)` order, then apply the adjustment above. The adjustment does not
change which 12 rows are loaded.

The lattice accepts at most 48 input scalars and 16 scalars per dictionary
edge. It adds a one-scalar copy edge with word cost 4000 at every position. A
missing connection has cost 3000; duplicate connection entries resolve to the
smallest cost. Path accumulation uses signed 64-bit integers, so the supported
48-scalar lattice cannot overflow. The public result cost is saturated to a
signed 32-bit integer.

At pruning and final ranking, paths are ordered by:

1. total path cost;
2. number of copy edges;
3. number of segments;
4. surface Unicode scalar sequence;
5. final right ID.

When alternate bunsetsu segmentations are preserved and all five keys tie,
the bunsetsu-boundary scalar-offset sequence is the final lexicographic tie
breaker. This keeps beam pruning deterministic across collection runtimes.

The beam width is 12 and at most eight results are returned. Unless a caller
explicitly preserves different bunsetsu segmentations, final results are
deduplicated by the exact surface scalar sequence.

## Compatibility fixtures

- `fixtures/conversion-cost.tsv` fixes adjusted costs for rows from the shared
  `reading.db` plus synthetic Unicode and overflow boundaries.
- `fixtures/conversion-nbest.json` fixes N-best surfaces and rank for complete
  inputs using that same database.
- `fixtures/sentence-conversion-regression.json` is a UTF-8, sentence-level
  quality corpus shared by Android and iOS. Each case specifies its reading,
  an optional top-1 expectation, N-best candidates that must be present, and
  prohibited candidates. Its `baselineKnownFailureIds` records natural
  expectations that the bundled database does not yet meet. Both platform
  tests continue to run these cases and report top-1 accuracy, top-3
  containment, prohibited-candidate count, unresolved known failures,
  improvements, and regressions. A newly failing baseline-passing case fails
  the test; resolving a known failure is reported as an improvement.

The first two fixtures test arithmetic and deterministic parity only. The
sentence fixture additionally encodes semantic quality expectations, including
contextual preferences such as `生きます/行きます` and
`問って/取って/撮って`.
