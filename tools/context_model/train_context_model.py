#!/usr/bin/env python3
"""Train deterministic content-surface counts for the offline context model.

This build-time program is intentionally independent from both mobile runtimes.
It tokenizes a fixed written-sentence corpus with the bundled conversion lexicon,
skips particles and auxiliaries between content tokens, and emits a compact,
reviewable count table. It never consumes the conversion quality fixture.
"""

from __future__ import annotations

import argparse
import bz2
import hashlib
import json
import math
import sqlite3
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path


BOS_ID = 0
EOS_ID = 1
COPY_ID = 15
DEFAULT_CONNECTION_COST = 3_000
COPY_WORD_COST = 4_000
MAX_TOKEN_SCALARS = 16
MAX_CHUNK_SCALARS = 96
MAX_LEXEMES_PER_SURFACE = 4
CONTENT_POS_IDS = frozenset({2, 3, 4, 7, 8, 9, 12})
SKIPPED_CONTEXT_POS_IDS = frozenset({5, 6})


@dataclass(frozen=True)
class Lexeme:
    surface: str
    left_id: int
    right_id: int
    word_cost: int
    known: bool = True


@dataclass(frozen=True)
class State:
    cost: int
    token_count: int
    previous_position: int | None
    previous_right_id: int | None
    lexeme: Lexeme | None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def scalar_key(value: str) -> tuple[int, ...]:
    return tuple(ord(character) for character in value)


def is_token_character(character: str) -> bool:
    return unicodedata.category(character)[0] in {"L", "M", "N"} or character in "々〆ヶー"


def sentence_chunks(sentence: str) -> list[str]:
    chunks: list[str] = []
    current: list[str] = []
    for character in sentence:
        if is_token_character(character):
            current.append(character)
        elif current:
            chunks.extend(split_long_chunk("".join(current)))
            current.clear()
    if current:
        chunks.extend(split_long_chunk("".join(current)))
    return chunks


def split_long_chunk(value: str) -> list[str]:
    return [value[index:index + MAX_CHUNK_SCALARS]
            for index in range(0, len(value), MAX_CHUNK_SCALARS)]


def load_lexicon(database: sqlite3.Connection) -> dict[str, tuple[Lexeme, ...]]:
    output: dict[str, list[Lexeme]] = defaultdict(list)
    rows = database.execute(
        """SELECT surface, left_id, right_id, min(word_cost) AS best_cost
           FROM conversion_lexeme
           GROUP BY surface, left_id, right_id
           ORDER BY surface, best_cost, left_id, right_id"""
    )
    for surface, left_id, right_id, word_cost in rows:
        if not 0 < len(surface) <= MAX_TOKEN_SCALARS:
            continue
        if not all(is_token_character(character) for character in surface):
            continue
        values = output[surface]
        if len(values) < MAX_LEXEMES_PER_SURFACE:
            values.append(Lexeme(surface, left_id, right_id, word_cost))
    return {surface: tuple(values) for surface, values in output.items()}


def load_connections(database: sqlite3.Connection) -> dict[tuple[int, int], int]:
    return {
        (right_id, left_id): cost
        for right_id, left_id, cost in database.execute(
            "SELECT previous_right_id, next_left_id, cost FROM connection_cost"
        )
    }


def matching_lexemes(
    chunk: str,
    start: int,
    lexicon: dict[str, tuple[Lexeme, ...]],
) -> list[tuple[int, Lexeme]]:
    output: list[tuple[int, Lexeme]] = []
    for end in range(start + 1, min(len(chunk), start + MAX_TOKEN_SCALARS) + 1):
        token = chunk[start:end]
        output.extend((end, lexeme) for lexeme in lexicon.get(token, ()))
    output.append(
        (start + 1, Lexeme(chunk[start:start + 1], COPY_ID, COPY_ID, COPY_WORD_COST, False))
    )
    output.sort(
        key=lambda item: (
            item[1].word_cost,
            -(item[0] - start),
            scalar_key(item[1].surface),
            item[1].left_id,
            item[1].right_id,
        )
    )
    return output


def tokenize(
    chunk: str,
    lexicon: dict[str, tuple[Lexeme, ...]],
    connections: dict[tuple[int, int], int],
) -> list[Lexeme]:
    if not chunk:
        return []
    states: list[dict[int, State]] = [dict() for _ in range(len(chunk) + 1)]
    states[0][BOS_ID] = State(0, 0, None, None, None)
    for start in range(len(chunk)):
        if not states[start]:
            continue
        edges = matching_lexemes(chunk, start, lexicon)
        for previous_right_id in sorted(states[start]):
            previous = states[start][previous_right_id]
            for end, lexeme in edges:
                connection_cost = connections.get(
                    (previous_right_id, lexeme.left_id), DEFAULT_CONNECTION_COST
                )
                candidate = State(
                    cost=previous.cost + connection_cost + lexeme.word_cost,
                    token_count=previous.token_count + 1,
                    previous_position=start,
                    previous_right_id=previous_right_id,
                    lexeme=lexeme,
                )
                current = states[end].get(lexeme.right_id)
                if current is None or (candidate.cost, candidate.token_count) < (
                    current.cost, current.token_count
                ):
                    states[end][lexeme.right_id] = candidate

    final_right_id = min(
        states[len(chunk)],
        key=lambda right_id: (
            states[len(chunk)][right_id].cost
            + connections.get((right_id, EOS_ID), DEFAULT_CONNECTION_COST),
            states[len(chunk)][right_id].token_count,
            right_id,
        ),
    )
    position = len(chunk)
    right_id = final_right_id
    reversed_tokens: list[Lexeme] = []
    while position > 0:
        state = states[position][right_id]
        if state.lexeme is None or state.previous_position is None or state.previous_right_id is None:
            raise ValueError("broken tokenizer backpointer")
        reversed_tokens.append(state.lexeme)
        position = state.previous_position
        right_id = state.previous_right_id
    return list(reversed(reversed_tokens))


def add_tokens(
    tokens: list[Lexeme],
    unigrams: Counter[str],
    bigrams: Counter[tuple[str, str]],
) -> None:
    previous_content: str | None = None
    for token in tokens:
        if not token.known:
            previous_content = None
        elif token.left_id in SKIPPED_CONTEXT_POS_IDS:
            continue
        elif token.left_id in CONTENT_POS_IDS:
            unigrams[token.surface] += 1
            if previous_content is not None:
                bigrams[(previous_content, token.surface)] += 1
            previous_content = token.surface
        else:
            previous_content = None


def load_seeds(path: Path) -> dict[tuple[str, str], int]:
    seeds: dict[tuple[str, str], int] = {}
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "previous_surface\tnext_surface\tpseudo_count\tnote":
        raise ValueError(f"invalid seed header: {path}")
    for line_number, line in enumerate(lines[1:], 2):
        if not line:
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise ValueError(f"invalid seed line {line_number}: {line}")
        previous, following, encoded_count, _ = fields
        count = int(encoded_count)
        if not previous or not following or count <= 0:
            raise ValueError(f"invalid seed values on line {line_number}")
        seeds[(previous, following)] = seeds.get((previous, following), 0) + count
    return seeds


def select_bigrams(
    bigrams: Counter[tuple[str, str]],
    unigrams: Counter[str],
    seeds: dict[tuple[str, str], int],
    minimum_count: int,
    maximum_count: int,
) -> dict[tuple[str, str], int]:
    def association(item: tuple[tuple[str, str], int]) -> tuple[float, int]:
        (previous, following), count = item
        denominator = math.sqrt(unigrams[previous] * unigrams[following])
        return (math.log2(count + 1) * count / max(1.0, denominator), count)

    eligible = [item for item in bigrams.items() if item[1] >= minimum_count]
    eligible.sort(
        key=lambda item: (
            -association(item)[0],
            -association(item)[1],
            scalar_key(item[0][0]),
            scalar_key(item[0][1]),
        )
    )
    selected = dict(eligible[:maximum_count])
    for pair, pseudo_count in seeds.items():
        selected[pair] = selected.get(pair, 0) + pseudo_count
    return selected


def write_counts(
    output: Path,
    metadata: dict[str, str | int],
    unigrams: Counter[str],
    bigrams: dict[tuple[str, str], int],
    maximum_unigrams: int,
) -> None:
    selected_unigrams = sorted(
        unigrams.items(), key=lambda item: (-item[1], scalar_key(item[0]))
    )[:maximum_unigrams]
    lines = ["# FKCTX_COUNTS 1"]
    lines.extend(f"# {key}={value}" for key, value in sorted(metadata.items()))
    lines.append("kind\tprevious_surface\tnext_surface\tcount")
    lines.extend(f"U\t-\t{surface}\t{count}" for surface, count in selected_unigrams)
    lines.extend(
        f"B\t{previous}\t{following}\t{count}"
        for (previous, following), count in sorted(
            bigrams.items(), key=lambda item: (scalar_key(item[0][0]), scalar_key(item[0][1]))
        )
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def train(args: argparse.Namespace) -> dict[str, int | str]:
    manifest = json.loads(args.sources.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("unsupported source manifest")
    expected_corpus_hash = manifest["corpus"]["sha256"]
    actual_corpus_hash = sha256_file(args.corpus)
    if actual_corpus_hash != expected_corpus_hash:
        raise ValueError(
            f"corpus SHA-256 mismatch: expected {expected_corpus_hash}, got {actual_corpus_hash}"
        )
    expected_database_hash = manifest["dictionary"]["sha256"]
    actual_database_hash = sha256_file(args.database)
    if actual_database_hash != expected_database_hash:
        raise ValueError(
            f"database SHA-256 mismatch: expected {expected_database_hash}, got {actual_database_hash}"
        )

    with sqlite3.connect(f"file:{args.database}?mode=ro", uri=True) as database:
        lexicon = load_lexicon(database)
        connections = load_connections(database)
        unigrams: Counter[str] = Counter()
        bigrams: Counter[tuple[str, str]] = Counter()
        sentence_count = 0
        chunk_count = 0
        with bz2.open(args.corpus, "rt", encoding="utf-8", newline="") as corpus:
            for line_number, line in enumerate(corpus, 1):
                fields = line.rstrip("\n").split("\t", 2)
                if len(fields) != 3 or fields[1] != "jpn":
                    raise ValueError(f"invalid corpus line {line_number}")
                sentence_count += 1
                for chunk in sentence_chunks(fields[2]):
                    add_tokens(tokenize(chunk, lexicon, connections), unigrams, bigrams)
                    chunk_count += 1

    seeds = load_seeds(args.seeds)
    selected_bigrams = select_bigrams(
        bigrams,
        unigrams,
        seeds,
        args.minimum_bigram_count,
        args.maximum_bigrams,
    )
    for (previous, following), pseudo_count in seeds.items():
        unigrams[previous] += pseudo_count
        unigrams[following] += pseudo_count
    seed_hash = sha256_file(args.seeds)
    metadata: dict[str, str | int] = {
        "corpus_sha256": actual_corpus_hash,
        "database_sha256": actual_database_hash,
        "seed_sha256": seed_hash,
        "sentence_count": sentence_count,
        "chunk_count": chunk_count,
        "tokenizer_version": manifest["tokenizerVersion"],
    }
    write_counts(args.output, metadata, unigrams, selected_bigrams, args.maximum_unigrams)
    return {
        "sentences": sentence_count,
        "chunks": chunk_count,
        "lexiconSurfaces": len(lexicon),
        "unigrams": min(len(unigrams), args.maximum_unigrams),
        "bigrams": len(selected_bigrams),
        "outputSha256": sha256_file(args.output),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--database", type=Path, default=Path("app/src/main/assets/reading.db"))
    parser.add_argument("--sources", type=Path, default=Path("tools/context_model/sources.json"))
    parser.add_argument("--seeds", type=Path, default=Path("tools/context_model/seed-bigrams.tsv"))
    parser.add_argument("--output", type=Path, default=Path("tools/context_model/context-counts.tsv"))
    parser.add_argument("--minimum-bigram-count", type=int, default=2)
    parser.add_argument("--maximum-bigrams", type=int, default=8_192)
    parser.add_argument("--maximum-unigrams", type=int, default=2_048)
    arguments = parser.parse_args()
    if arguments.minimum_bigram_count <= 0:
        parser.error("--minimum-bigram-count must be positive")
    if arguments.maximum_bigrams <= 0 or arguments.maximum_unigrams <= 0:
        parser.error("model entry limits must be positive")
    return arguments


if __name__ == "__main__":
    print(json.dumps(train(parse_args()), ensure_ascii=False, sort_keys=True))
