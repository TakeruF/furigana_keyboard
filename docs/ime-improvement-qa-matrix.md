# IME improvement QA matrix

This document fixes the acceptance contract for the IME improvement work. It
does not make `KNOWN_FAILURE` acceptable behavior. The label describes the
2026-07-14 baseline in
`fixtures/ime-improvement-contracts.json` so later work can distinguish an
improvement from a regression.

## Matrix

| Area | Contract | Automated layer | Current baseline | Acceptance |
| --- | --- | --- | --- | --- |
| Conversion | `いばらき｜` | JVM + bundled DB | PASS | `茨城` is top-1 and standalone `荊` is absent |
| Conversion | `いえに｜` | JVM + bundled DB | PASS | `家に` is top-1; `家煮` is not top-1; standalone `家` is absent |
| Partial conversion | `すもも｜もも` | JVM | PASS | Commit only `すもも`; retain the exact right suffix `もも` |
| Cursor | start / middle / end | JVM state + Android instrumentation | KNOWN_FAILURE | Insertion, deletion, conversion, and cancellation preserve both sides of the cursor |
| Romaji | unresolved `n`, consonant only, deletion | JVM | PASS | No dictionary query while unresolved; deletion removes one visible unit |
| Candidate state | cancellation | JVM | PASS | Cancelling clears the selected index and returns to `EMPTY` |
| Space | tap | JVM state | PASS | Exactly one context-appropriate action |
| Space | hold / drag / reversal | Android instrumentation | KNOWN_FAILURE | Hold enters cursor mode; drag follows grapheme boundaries; reversal has no stale step |
| Space | `CANCEL` / multi-touch | Android instrumentation | KNOWN_FAILURE | Cancel dispatches nothing; secondary pointers are ignored |
| Handwriting | `本/木`, `未/末`, `土/士`, `口/日` | JVM ranker | PASS | Both members remain in the candidate list; only ordering may change |
| Retention | person, place, rare Han | JVM ranker/converter | PASS | No supplied class is filtered from N-best solely because it is uncommon |
| Unicode | supplementary plane / precomposed dakuten | JVM | PASS | No surrogate splitting; one visible character deletes cleanly |
| Unicode | combining dakuten / canonical equivalence | JVM + integration | KNOWN_FAILURE | Delete one grapheme; NFC-equivalent readings return equivalent candidates |

## Required interaction scenarios

Run the cursor and Space rows against an editable field containing, in turn,
empty text, `かな`, `A𠮟B`, and `か\u3099な`. Repeat at the start, middle, and
end selection. For every Space gesture, record the InputConnection calls and
assert that text commits, candidate selection, and cursor movement are mutually
exclusive outcomes. Repeat `CANCEL` before and after the hold threshold, reverse
direction at least twice in one drag, and add/remove a second pointer while the
primary pointer remains down.

## Candidate-quality comparison

Compare before and after with the same APK variant, reading database, context
model, locale, and clean user-learning state. Preserve the ordered top 8 for
every fixture input and report:

- exact top-1 accuracy and required-candidate top-3/top-8 containment;
- forbidden-candidate count (`荊` alone, `家煮` at top-1, `家` alone);
- partial-conversion suffix preservation, which must be 100%;
- recall of supplied handwriting and person/place/rare candidates, which must
  remain 100%;
- Unicode corruption, surrogate-split, and canonical-duplicate counts, all of
  which must be zero;
- regressions by fixture ID. Every baseline `PASS` is a hard non-regression
  gate, while a fixed known failure is reported as an improvement.

Do not accept an aggregate gain that hides a regression in one of the explicit
contracts. Store candidate strings as Unicode scalars and compare canonical
equivalence separately from byte-for-byte equality.

## Performance comparison

Use paired, interleaved before/after samples on the same physical device and an
arm64 emulator/simulator. Warm each path four times, then collect at least 50
samples per target reading and 100 gesture events per scenario. Report median,
p95, and maximum for:

- key-down to composing-text update;
- key-down to first candidate paint and to final N-best paint;
- conversion engine time with and without database lookup;
- Space drag event to selection update;
- handwriting stroke-up to first candidate paint;
- allocations and peak RSS for a 60-second mixed-input trace.

The comparison gate is: no statistically repeatable p95 regression greater
than 10% plus 2 ms for key/cursor handling, or greater than 25% plus 2 ms for
dictionary/recognition work. Peak RSS must not grow by more than 5 MiB. Quality
gates take precedence over latency gains. Record thermal state, build type,
device, OS, sample count, database hash, model hash, and the exact fixture
revision with every result.
