#!/usr/bin/env python3
"""Compile trained counts into the shared integer-only FKCTX binary format."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from pathlib import Path


MAGIC = b"FKCTX001"
FORMAT_VERSION = 1
MODEL_VERSION = 1


def scalar_key(value: str) -> tuple[int, ...]:
    return tuple(ord(character) for character in value)


def source_hash(path: Path) -> bytes:
    return hashlib.sha256(path.read_bytes()).digest()


def unigram_cost(count: int) -> int:
    # Unknown bigrams back off to a neutral unigram cost. Keeping the observed
    # unigram table in the format makes the backoff explicit without allowing
    # tiny standalone frequency differences to reorder unrelated homophones.
    _ = count
    return 0


def bigram_cost(count: int) -> int:
    return -min(3_400, 1_400 + round(500 * math.log2(count + 1)))


def parse_counts(path: Path) -> tuple[dict[str, int], dict[tuple[str, str], int]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "# FKCTX_COUNTS 1":
        raise ValueError(f"invalid counts header: {path}")
    try:
        header_index = lines.index("kind\tprevious_surface\tnext_surface\tcount")
    except ValueError as error:
        raise ValueError("counts column header is missing") from error
    unigrams: dict[str, int] = {}
    bigrams: dict[tuple[str, str], int] = {}
    for line_number, line in enumerate(lines[header_index + 1:], header_index + 2):
        if not line:
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise ValueError(f"invalid counts line {line_number}: {line}")
        kind, previous, following, encoded_count = fields
        count = int(encoded_count)
        if count <= 0 or not following:
            raise ValueError(f"invalid counts values on line {line_number}")
        if kind == "U" and previous == "-":
            if following in unigrams:
                raise ValueError(f"duplicate unigram on line {line_number}")
            unigrams[following] = count
        elif kind == "B" and previous:
            pair = (previous, following)
            if pair in bigrams:
                raise ValueError(f"duplicate bigram on line {line_number}")
            bigrams[pair] = count
        else:
            raise ValueError(f"invalid record kind on line {line_number}")
    return unigrams, bigrams


def encoded_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    if not encoded or len(encoded) > 0xFFFF:
        raise ValueError(f"invalid UTF-8 field length for {value!r}")
    return struct.pack("<H", len(encoded)) + encoded


def compile_model(counts_path: Path) -> tuple[bytes, dict[str, int | str]]:
    unigrams, bigrams = parse_counts(counts_path)
    digest = source_hash(counts_path)
    output = bytearray(
        struct.pack(
            "<8sHHII32s",
            MAGIC,
            FORMAT_VERSION,
            MODEL_VERSION,
            len(unigrams),
            len(bigrams),
            digest,
        )
    )
    for surface, count in sorted(unigrams.items(), key=lambda item: scalar_key(item[0])):
        output.extend(encoded_string(surface))
        output.extend(struct.pack("<i", unigram_cost(count)))
    for (previous, following), count in sorted(
        bigrams.items(), key=lambda item: (scalar_key(item[0][0]), scalar_key(item[0][1]))
    ):
        output.extend(encoded_string(previous))
        output.extend(encoded_string(following))
        output.extend(struct.pack("<i", bigram_cost(count)))
    metadata: dict[str, int | str] = {
        "formatVersion": FORMAT_VERSION,
        "modelVersion": MODEL_VERSION,
        "unigramCount": len(unigrams),
        "bigramCount": len(bigrams),
        "entryCount": len(unigrams) + len(bigrams),
        "sourceSha256": digest.hex(),
        "modelSha256": hashlib.sha256(output).hexdigest(),
        "modelBytes": len(output),
    }
    return bytes(output), metadata


def main(args: argparse.Namespace) -> dict[str, int | str]:
    model, metadata = compile_model(args.counts)
    if args.check:
        if not args.output.is_file() or args.output.read_bytes() != model:
            raise SystemExit(f"generated context model differs: {args.output}")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(model)
    return metadata


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--counts", type=Path, default=Path("tools/context_model/context-counts.tsv")
    )
    parser.add_argument(
        "--output", type=Path, default=Path("app/src/main/assets/context-model.bin")
    )
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    print(json.dumps(main(parse_args()), ensure_ascii=False, sort_keys=True))
