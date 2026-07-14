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

weakKatakanaCuePenalty =
    1200 when surface is the full-width katakana transliteration of reading
              and reading has no strong loanword cue
       0 otherwise

perHanPenalty(frequency) =
    min(frequency, 3000) / 100 when frequency > 0
    3000 / 100                 when frequency is absent or <= 0

frequencyPenalty =
    0 when surface contains no Han scalar
    floor(sum(perHanPenalty(each Han occurrence)) / Han occurrence count)
      otherwise

adjustment = kanaContentPenalty + weakKatakanaCuePenalty + frequencyPenalty
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

For each distinct token reading, clients look up both the reading itself and
its full-width katakana transliteration, taking at most 12 rows from each in
ascending `(word_cost, form_rank, surface scalar sequence, left_id, right_id)`
order. Rows from the transliterated lookup are retained only when their surface
is that exact full-width transliteration. The combined rows are deduplicated by
`(surface, left_id, right_id)`, adjusted as above, and the lattice retains its
12 cheapest lexemes per occurrence and reading. This makes ordinary small kana
such as `ゃゅょ` eligible through the dictionary without treating every native
reading containing them as a loanword.

A strong cue is `ー`, `ゔ`, one of `ぁぃぅぇぉゎ`, or at least two
occurrences of `っ`. A lone `っ` is deliberately insufficient, so native mixed
words such as `引っ越し` do not become loanword spans. Dictionary katakana
without a strong cue remains eligible, but pays the 1200 weak-cue cost above;
this lets `アプリ` and `シャツ` compete without promoting fragments such as
`ホン` in an ordinary native reading.

A selected row whose surface is exactly the full-width katakana
transliteration of its hiragana reading is a katakana edge. Up to four adjacent
katakana edges may be joined into one edge of at most 16 scalars. The joined
word cost is the sum of component word costs plus 200 per join; its left ID is
the first component's and its right ID is the last component's. This lets
`すかい + つりー` participate as one `スカイツリー` unit.

Dictionary-independent katakana spans are also normal lattice edges, not
post-ranking fallbacks. Every 2--8 scalar hiragana span with a strong cue gets
its full-width katakana surface, noun left/right IDs, and word cost
`1000 + 500 * scalarCount`. A span is not generated when it would absorb a
one-scalar kana particle or auxiliary at either edge. This keeps particles
outside a loanword, as in `スカイツリー + に`.

The lattice accepts at most 48 input scalars and 16 scalars per dictionary
edge. It adds a one-scalar copy edge with word cost 4000 at every position. A
missing connection has cost 3000; duplicate connection entries resolve to the
smallest cost. Path accumulation uses signed 64-bit integers, so the supported
48-scalar lattice cannot overflow. The public result cost is saturated to a
signed 32-bit integer.

Three structural penalties are added while extending a path:

- 900 when two consecutive dictionary edges inside a strongly signaled
  loanword range are both pure Han surfaces with one-scalar readings;
- 1600 for a one- or two-scalar unconverted-kana fragment or a one-scalar
  pure-Han fragment inside a strongly signaled loanword range. A dictionary
  kana edge immediately following a multi-scalar katakana unit is treated as
  a suffix outside that unit and is exempt;
- 900 from the second Han/unconverted-kana script alternation within the
  same loanword range.

Mixed surfaces are not treated as pure Han or unconverted kana. These rules
therefore do not blanket-penalize dictionary edges such as `取り扱い` or
`引っ越し`.

## Context cost

The existing adjusted word and POS connection costs remain unchanged. Every
edge expansion adds an independent signed integer `contextCost` from the shared
`context-model.bin` before beam pruning. Android and iOS bundle the identical
binary and do not apply platform-specific context heuristics.

The context state is the most recent content surface. Content POS IDs are
`{2, 3, 4, 7, 8, 9, 12}`. Particle and auxiliary IDs `{5, 6}` add no context
cost and preserve that surface; this permits `学校 + へ + 行きます` to use a
`学校→行きます` bigram. A copy edge or any other POS class clears the state.
Sequential bunsetsu confirmation carries both the last right POS ID and this
content surface into the suffix reanalysis.

For a content edge, an exact surface bigram is looked up first. If absent, the
runtime backs off to the next-surface unigram table, and if that surface is also
absent it uses zero. Model v1 stores observed unigrams with a calibrated cost of
zero: unknown context therefore defers to the original word and POS scores.
Exact v1 bigram count `c` is quantized as:

```text
contextCost = -min(3400, 1400 + round(500 * log2(c + 1)))
```

All compiled costs are signed 32-bit integers and path accumulation remains
signed 64-bit. The versioned little-endian model header contains `FKCTX001`,
format and model versions, unigram and bigram counts, and the SHA-256 of the
generated count table. Runtime loading is read-only and requires no network or
input-history storage. Training, source pinning, license, and reproduction are
documented in `tools/context_model/README.md`.

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
- `fixtures/context-conversion-nbest.json` fixes context model metadata, model
  bytes/hash, and all eight ranked surfaces and integer costs for the four
  acceptance readings. Both platforms also compare production beam 12 with a
  beam-64 reference and confirm that a result limit of one retains top-1.
- `fixtures/sentence-conversion-regression.json` is a UTF-8, sentence-level
  quality corpus shared by Android and iOS. Each case specifies its reading,
  an optional top-1 expectation, N-best candidates that must be present, and
  prohibited candidates. Its `baselineKnownFailureIds` records natural
  expectations that the bundled database does not yet meet. Both platform
  tests continue to run these cases and report top-1 accuracy, top-3
  containment, prohibited-candidate count, unresolved known failures,
  improvements, and regressions. A newly failing baseline-passing case fails
  the test; resolving a known failure is reported as an improvement.

The sentence corpus has 57 cases, including direct Wave 2 checks for
`ぷろじぇくと`, `りりーす`, and `すかいつりー`. The first two fixtures
test arithmetic and deterministic parity only. The
sentence fixture additionally encodes semantic quality expectations, including
contextual preferences such as `生きます/行きます` and
`問って/取って/撮って`.
