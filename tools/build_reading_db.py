#!/usr/bin/env python3
"""Build the bundled read-only Japanese reading database.

Inputs are the official compressed KANJIDIC2/JMdict files plus the Tegaki
Zinnia model archive.  The generated SQLite file is deterministic for a fixed
set of inputs and contains only data needed by the IME.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import sqlite3
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


SCHEMA_VERSION = "2"

# The Tegaki JIS X 0208 label set contains the ditto mark 仝, which is not a
# standalone KANJIDIC2 entry. It is the old-form equivalent of 同.
MODEL_READING_OVERRIDES = {"仝": [("ドウ", 0), ("おな.じ", 1)]}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def is_han(value: str) -> bool:
    if len(value) != 1:
        return False
    cp = ord(value)
    return (
        0x3400 <= cp <= 0x4DBF
        or 0x4E00 <= cp <= 0x9FFF
        or 0xF900 <= cp <= 0xFAFF
        or 0x20000 <= cp <= 0x323AF
    )


def priority(tags: list[str]) -> int:
    ranks: list[int] = []
    for tag in tags:
        if tag.startswith("nf") and tag[2:].isdigit():
            ranks.append(int(tag[2:]))
        elif tag in {"news1", "ichi1", "spec1", "gai1"}:
            ranks.append(1)
        elif tag in {"news2", "ichi2", "spec2", "gai2"}:
            ranks.append(25)
    return min(ranks, default=100)


def create_schema(db: sqlite3.Connection) -> None:
    db.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA page_size=4096;

        CREATE TABLE metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE kanji_reading (
            literal TEXT NOT NULL,
            reading TEXT NOT NULL,
            kind INTEGER NOT NULL,
            position INTEGER NOT NULL,
            PRIMARY KEY (literal, kind, position)
        ) WITHOUT ROWID;

        CREATE TABLE kanji_priority (
            literal TEXT PRIMARY KEY,
            grade INTEGER NOT NULL,
            frequency INTEGER NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE word_reading (
            surface TEXT NOT NULL,
            reading TEXT NOT NULL,
            priority INTEGER NOT NULL,
            PRIMARY KEY (surface, reading)
        ) WITHOUT ROWID;

        CREATE INDEX word_reading_rank
            ON word_reading(surface, priority, reading);
        """
    )


def import_kanjidic(db: sqlite3.Connection, source: Path) -> tuple[int, int, int, str]:
    characters = 0
    readings = 0
    prioritized = 0
    creation_date = "unknown"
    batch: list[tuple[str, str, int, int]] = []
    priority_batch: list[tuple[str, int, int]] = []
    with gzip.open(source, "rb") as stream:
        for event, elem in ET.iterparse(stream, events=("end",)):
            if elem.tag == "date_of_creation" and elem.text:
                creation_date = elem.text
            elif elem.tag == "character":
                literal = elem.findtext("literal")
                if literal:
                    grade = int(elem.findtext("./misc/grade") or 0)
                    frequency = int(elem.findtext("./misc/freq") or 0)
                    priority_batch.append((literal, grade, frequency))
                    if grade or frequency:
                        prioritized += 1
                    position = {0: 0, 1: 0, 2: 0}
                    for node in elem.findall("./reading_meaning/rmgroup/reading"):
                        reading_type = node.attrib.get("r_type")
                        if reading_type not in {"ja_on", "ja_kun"} or not node.text:
                            continue
                        kind = 0 if reading_type == "ja_on" else 1
                        batch.append((literal, node.text, kind, position[kind]))
                        position[kind] += 1
                    for node in elem.findall("./reading_meaning/nanori"):
                        if node.text:
                            batch.append((literal, node.text, 2, position[2]))
                            position[2] += 1
                    # Radical-only entries such as 鬥 have no on/kun reading,
                    # but KANJIDIC2 provides their Japanese radical names.
                    if sum(position.values()) == 0:
                        for node in elem.findall("./misc/rad_name"):
                            if node.text:
                                batch.append((literal, node.text, 2, position[2]))
                                position[2] += 1
                    characters += 1
                    readings += sum(position.values())
                elem.clear()
            if len(batch) >= 20_000:
                db.executemany(
                    "INSERT OR IGNORE INTO kanji_reading VALUES (?, ?, ?, ?)", batch
                )
                batch.clear()
            if len(priority_batch) >= 20_000:
                db.executemany(
                    "INSERT OR REPLACE INTO kanji_priority VALUES (?, ?, ?)",
                    priority_batch,
                )
                priority_batch.clear()
    if batch:
        db.executemany("INSERT OR IGNORE INTO kanji_reading VALUES (?, ?, ?, ?)", batch)
    if priority_batch:
        db.executemany(
            "INSERT OR REPLACE INTO kanji_priority VALUES (?, ?, ?)", priority_batch
        )
    for literal, values in MODEL_READING_OVERRIDES.items():
        for pos, (reading, kind) in enumerate(values):
            db.execute(
                "INSERT OR IGNORE INTO kanji_reading VALUES (?, ?, ?, ?)",
                (literal, reading, kind, pos),
            )
            readings += 1
    return characters, readings, prioritized, creation_date


def import_jmdict(db: sqlite3.Connection, source: Path) -> tuple[int, int]:
    entries = 0
    pairs = 0
    batch: list[tuple[str, str, int]] = []
    with gzip.open(source, "rb") as stream:
        for event, elem in ET.iterparse(stream, events=("end",)):
            if elem.tag != "entry":
                continue
            entries += 1
            surfaces: dict[str, list[str]] = {}
            for k_ele in elem.findall("k_ele"):
                surface = k_ele.findtext("keb")
                if surface:
                    surfaces[surface] = [p.text or "" for p in k_ele.findall("ke_pri")]
            for r_ele in elem.findall("r_ele"):
                reading = r_ele.findtext("reb")
                if not reading or r_ele.find("re_nokanji") is not None:
                    continue
                restrictions = {r.text for r in r_ele.findall("re_restr") if r.text}
                reading_pri = [p.text or "" for p in r_ele.findall("re_pri")]
                for surface, surface_pri in surfaces.items():
                    if restrictions and surface not in restrictions:
                        continue
                    batch.append((surface, reading, priority(surface_pri + reading_pri)))
                    pairs += 1
            elem.clear()
            if len(batch) >= 40_000:
                db.executemany(
                    """INSERT INTO word_reading(surface, reading, priority)
                       VALUES (?, ?, ?)
                       ON CONFLICT(surface, reading) DO UPDATE SET
                         priority = min(priority, excluded.priority)""",
                    batch,
                )
                batch.clear()
    if batch:
        db.executemany(
            """INSERT INTO word_reading(surface, reading, priority)
               VALUES (?, ?, ?)
               ON CONFLICT(surface, reading) DO UPDATE SET
                 priority = min(priority, excluded.priority)""",
            batch,
        )
    unique_pairs = db.execute("SELECT count(*) FROM word_reading").fetchone()[0]
    return entries, unique_pairs


def model_labels(model_archive: Path) -> set[str]:
    with zipfile.ZipFile(model_archive) as archive:
        xml_name = next(name for name in archive.namelist() if name.endswith("handwriting-ja.xml"))
        with archive.open(xml_name) as stream:
            return {
                node.text or ""
                for event, node in ET.iterparse(stream, events=("end",))
                if node.tag == "utf8"
            }


def validate(db: sqlite3.Connection, labels: set[str]) -> tuple[int, list[str]]:
    han_labels = sorted(label for label in labels if is_han(label))
    missing = [
        label
        for label in han_labels
        if db.execute(
            "SELECT 1 FROM kanji_reading WHERE literal=? LIMIT 1", (label,)
        ).fetchone()
        is None
    ]
    return len(han_labels), missing


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kanjidic", required=True, type=Path)
    parser.add_argument("--jmdict", required=True, type=Path)
    parser.add_argument("--model-archive", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.unlink(missing_ok=True)
    db = sqlite3.connect(args.output)
    try:
        create_schema(db)
        chars, kanji_readings, kanji_priorities, kanjidic_date = import_kanjidic(
            db, args.kanjidic
        )
        entries, word_pairs = import_jmdict(db, args.jmdict)
        labels = model_labels(args.model_archive)
        han_labels, missing = validate(db, labels)
        metadata = {
            "schema_version": SCHEMA_VERSION,
            "kanjidic_date": kanjidic_date,
            "kanjidic_sha256": sha256(args.kanjidic),
            "jmdict_sha256": sha256(args.jmdict),
            "model_archive_sha256": sha256(args.model_archive),
            "kanji_characters": str(chars),
            "kanji_readings": str(kanji_readings),
            "kanji_priorities": str(kanji_priorities),
            "jmdict_entries": str(entries),
            "word_pairs": str(word_pairs),
            "model_labels": str(len(labels)),
            "model_han_labels": str(han_labels),
            "model_missing_readings": str(len(missing)),
        }
        db.executemany("INSERT INTO metadata VALUES (?, ?)", sorted(metadata.items()))
        db.commit()
        db.execute("VACUUM")
        print("\n".join(f"{key}={value}" for key, value in sorted(metadata.items())))
        if missing:
            print("Missing model readings: " + " ".join(missing), file=sys.stderr)
            return 2
        if chars < 12_000 or word_pairs < 180_000 or han_labels < 6_000:
            print("Generated data did not meet minimum coverage", file=sys.stderr)
            return 3
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
