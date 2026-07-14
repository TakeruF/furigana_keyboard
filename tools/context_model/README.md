# Offline context model generation

The mobile runtimes only read `app/src/main/assets/context-model.bin`. Training
is a separate, deterministic build-time operation and never runs in the app.
Neither runtime needs network access or writes conversion input to storage.

## Pinned inputs

`sources.json` is the source manifest. Model v1 uses the Tatoeba Japanese
sentence export dated 2026-07-11, licensed CC BY 2.0 FR, and the repository's
bundled `reading.db`. Both inputs have fixed SHA-256 values. The small
`seed-bigrams.tsv` file contains product-domain **surface pairs**, not complete
sentences; its hash is also embedded in the generated count-table header.

The quality fixture under `fixtures/` is deliberately not read by the trainer.
This keeps Wave 2 evaluation separate from model generation.

## Reproduce

Run from the repository root with Python 3. No third-party Python packages are
required.

```sh
mkdir -p build/context-model
curl -L --fail \
  -o build/context-model/jpn_sentences_2026-07-11.tsv.bz2 \
  https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2
shasum -a 256 build/context-model/jpn_sentences_2026-07-11.tsv.bz2
python3 tools/context_model/train_context_model.py \
  --corpus build/context-model/jpn_sentences_2026-07-11.tsv.bz2
python3 tools/context_model/compile_context_model.py
python3 tools/context_model/compile_context_model.py --check
```

The download must hash to
`6f363cf9acc1efc0bf7bad645d0fb693a6e08bf3110fbdadcfc123a9e9612b6f`.
The trainer rejects an input whose hash differs from `sources.json`.
Tatoeba's official URL is a rolling weekly export, not a dated archive URL.
Retain the 3,415,765-byte matching download in build storage if the full
training pass must be repeated later; never substitute a newer weekly file
without updating the manifest and model version. The committed count table is
sufficient to reproduce and verify the mobile binary even when the raw training
snapshot is not present.

Expected v1 outputs:

| Artifact | SHA-256 | Bytes / records |
| --- | --- | ---: |
| `context-counts.tsv` | `5275f10c2273647da817b8ba49558d82406e9b1435ca5246094b4d67097001ba` | 211,829 bytes |
| `context-model.bin` | `22e83b94a259d900d5d60c058d9ba6773506ee43fc81b4f644da02a6495b2fc3` | 219,009 bytes |
| v1 model entries | — | 2,048 unigrams + 8,194 bigrams |

The count table records 248,802 input sentences and 322,253 tokenized chunks.
If the source, tokenizer, selection, or cost quantization changes, increment
`MODEL_VERSION` in `compile_context_model.py`, regenerate the shared fixture,
and rerun both platform parity and Wave 2 quality tests.

## Training and scoring

The trainer tokenizes written Japanese with a Viterbi pass over the bundled
lexicon and its existing word/POS connection costs. It counts content-surface
bigrams while carrying context across particles and auxiliaries. Unknown copied
text breaks the context chain. It selects at most 8,192 corpus bigrams with a
minimum count of two, then adds the reviewed product-domain seed pairs.

The compiler converts a bigram count `c` to the signed integer cost
`-min(3400, 1400 + round(500 * log2(c + 1)))`. An unknown bigram backs off to
the next-surface unigram table. Model v1 intentionally calibrates unigram costs
to zero, so missing evidence leaves the existing word and POS scores unchanged.
A tested 0–56 point frequency backoff regressed the shared homophone and
katakana categories and was rejected.

The binary is little-endian and begins with `FKCTX001`, followed by format
version, model version, unigram and bigram counts, and the SHA-256 of
`context-counts.tsv`. Strings are length-prefixed UTF-8 and every cost is a
signed 32-bit integer.
