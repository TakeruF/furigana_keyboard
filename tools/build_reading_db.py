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


SCHEMA_VERSION = "7"

# JMnedict contains many kinds of proper names. Keep this import focused on
# geographic names so person and organization names do not crowd normal IME
# conversion candidates.
GEOGRAPHIC_NAME_TYPES = {"place name", "railway station"}
GEOGRAPHIC_NAME_PRIORITY = 75

# These JMdict reading annotations are useful for historical reference and
# search, but should not be presented as normal modern input candidates.
EXCLUDED_READING_INFORMATION = {
    "out-dated or obsolete kana usage",
    "rarely-used kana form",
    "search-only kana form",
}

# The Tegaki JIS X 0208 label set contains the ditto mark 仝, which is not a
# standalone KANJIDIC2 entry. It is the old-form equivalent of 同.
MODEL_READING_OVERRIDES = {"仝": [("ドウ", 0), ("おな.じ", 1)]}

ICHIDAN_POS = {
    "Ichidan verb",
    "Ichidan verb - kureru special class",
}

GODAN_ENDINGS = {
    "Godan verb with 'u' ending": ("う", "って", "った", "わ", "い", "え"),
    "Godan verb with 'u' ending (special class)":
        ("う", "って", "った", "わ", "い", "え"),
    "Godan verb with 'ku' ending": ("く", "いて", "いた", "か", "き", "け"),
    "Godan verb with 'gu' ending": ("ぐ", "いで", "いだ", "が", "ぎ", "げ"),
    "Godan verb with 'su' ending": ("す", "して", "した", "さ", "し", "せ"),
    "Godan verb with 'tsu' ending": ("つ", "って", "った", "た", "ち", "て"),
    "Godan verb with 'nu' ending": ("ぬ", "んで", "んだ", "な", "に", "ね"),
    "Godan verb with 'bu' ending": ("ぶ", "んで", "んだ", "ば", "び", "べ"),
    "Godan verb with 'mu' ending": ("む", "んで", "んだ", "ま", "み", "め"),
    "Godan verb with 'ru' ending": ("る", "って", "った", "ら", "り", "れ"),
    "Godan verb with 'ru' ending (irregular verb)":
        ("る", "って", "った", "ら", "り", "れ"),
}

IKU_POS = "Godan verb - Iku/Yuku special class"
SURU_POS = {"suru verb - included", "suru verb - special class"}
SURU_NOUN_POS = "noun or participle which takes the aux. verb suru"
KURU_POS = "Kuru verb - special class"
I_ADJECTIVE_POS = {
    "adjective (keiyoushi)",
    "adjective (keiyoushi) - yoi/ii class",
}


def inflected_forms(
    surface: str, reading: str, pos_tags: set[str]
) -> list[tuple[str, str, int]]:
    """Return common modern inflections for a JMdict surface/reading pair.

    This deliberately covers only forms whose spelling follows directly from
    the JMdict conjugation class. Archaic and ambiguous classes remain ordinary
    dictionary entries rather than risking invented candidates.
    """
    forms: list[tuple[str, str, int]] = []

    def append(stem_surface: str, stem_reading: str, suffixes: list[tuple[str, int]]) -> None:
        forms.extend(
            (stem_surface + suffix, stem_reading + suffix, rank)
            for suffix, rank in suffixes
        )

    def append_ease_forms(
        stem_surface: str,
        stem_reading: str,
        surface_connector: str,
        reading_connector: str,
        first_rank: int,
    ) -> None:
        reading_value = stem_reading + reading_connector + "やすい"
        forms.extend(
            [
                (stem_surface + surface_connector + "やすい", reading_value, first_rank),
                (stem_surface + surface_connector + "易い", reading_value, first_rank + 1),
            ]
        )

    if pos_tags & ICHIDAN_POS and surface.endswith("る") and reading.endswith("る"):
        surface_stem = surface[:-1]
        reading_stem = reading[:-1]
        append(
            surface_stem,
            reading_stem,
            [
                ("て", 1), ("た", 2), ("ない", 3), ("なかった", 4),
                ("ます", 5), ("ました", 6), ("れば", 7),
            ],
        )
        append_ease_forms(surface_stem, reading_stem, "", "", 8)

    godan = next((value for key, value in GODAN_ENDINGS.items() if key in pos_tags), None)
    if IKU_POS in pos_tags:
        godan = ("く", "って", "った", "か", "き", "け")
    if godan is not None:
        ending, te, past, negative, polite, conditional = godan
        if surface.endswith(ending) and reading.endswith(ending):
            surface_stem = surface[:-1]
            reading_stem = reading[:-1]
            suffixes = [
                (te, 1),
                (past, 2),
                (negative + "ない", 3),
                (negative + "なかった", 4),
                (polite + "ます", 5),
                (polite + "ました", 6),
                (conditional + "ば", 7),
                # The e-row is both the modern imperative stem (使え) and
                # the base of the godan potential (使える). Include the
                # potential's common continuations so partial romaji input
                # can convert naturally as well.
                (conditional, 8),
                (conditional + "る", 9),
                (conditional + "て", 10),
                (conditional + "た", 11),
                (conditional + "ない", 12),
                (conditional + "なかった", 13),
                (conditional + "ます", 14),
                (conditional + "ました", 15),
            ]
            forms.extend(
                (surface_stem + suffix, reading_stem + suffix, rank)
                for suffix, rank in suffixes
            )
            append_ease_forms(
                surface_stem,
                reading_stem,
                polite,
                polite,
                16,
            )

    has_suru_stem = (
        bool(pos_tags & SURU_POS)
        and surface.endswith("する")
        and reading.endswith("する")
    )
    if has_suru_stem or SURU_NOUN_POS in pos_tags:
        surface_stem = surface[:-2] if has_suru_stem else surface
        reading_stem = reading[:-2] if has_suru_stem else reading
        suffixes = [
            ("して", 1), ("した", 2), ("しない", 3), ("しなかった", 4),
            ("します", 5), ("しました", 6), ("すれば", 7),
            ("したい", 8),
        ]
        forms.extend(
            (surface_stem + suffix, reading_stem + suffix, rank)
            for suffix, rank in suffixes
        )
        append_ease_forms(surface_stem, reading_stem, "し", "し", 9)

    if KURU_POS in pos_tags and reading.endswith("くる"):
        if surface.endswith("くる"):
            surface_stem = surface[:-2]
            surface_suffixes = ["きて", "きた", "こない", "こなかった", "きます", "きました", "くれば"]
        elif surface.endswith("来る"):
            surface_stem = surface[:-1]
            surface_suffixes = ["て", "た", "ない", "なかった", "ます", "ました", "れば"]
        else:
            surface_suffixes = []
            surface_stem = surface
        reading_stem = reading[:-2]
        reading_suffixes = ["きて", "きた", "こない", "こなかった", "きます", "きました", "くれば"]
        forms.extend(
            (surface_stem + surface_suffix, reading_stem + reading_suffix, rank)
            for rank, (surface_suffix, reading_suffix) in enumerate(
                zip(surface_suffixes, reading_suffixes), start=1
            )
        )
        if surface.endswith("くる"):
            append_ease_forms(surface_stem, reading_stem, "き", "き", 8)
        elif surface.endswith("来る"):
            append_ease_forms(surface_stem, reading_stem, "", "き", 8)

    if pos_tags & I_ADJECTIVE_POS and surface.endswith("い") and reading.endswith("い"):
        surface_stem = surface[:-1]
        reading_stem = reading[:-1]
        if reading == "いい":
            reading_stem = "よ"
        suffixes = [
            ("くて", 1), ("かった", 2), ("くない", 3),
            ("くなかった", 4), ("ければ", 7),
        ]
        forms.extend(
            (surface_stem + suffix, reading_stem + suffix, rank)
            for suffix, rank in suffixes
        )

    return forms


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
            reading_priority INTEGER NOT NULL,
            reading_position INTEGER NOT NULL,
            form_rank INTEGER NOT NULL,
            PRIMARY KEY (surface, reading)
        ) WITHOUT ROWID;

        CREATE INDEX word_reading_rank
            ON word_reading(
                surface, reading_priority, reading_position, form_rank,
                priority, reading
            );

        CREATE INDEX word_reading_reading_rank
            ON word_reading(reading, form_rank, priority, surface);
        """
    )


def import_kanjidic(db: sqlite3.Connection, source: Path) -> tuple[int, int, int, str]:
    characters = 0
    readings = 0
    prioritized = 0
    creation_date = "unknown"
    batch: list[tuple[str, str, int, int, int]] = []
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


def import_jmdict(db: sqlite3.Connection, source: Path) -> tuple[int, int, int]:
    entries = 0
    batch: list[tuple[str, str, int, int, int, int]] = []

    def flush() -> None:
        db.executemany(
            """INSERT INTO word_reading(
                   surface, reading, priority, reading_priority,
                   reading_position, form_rank
               ) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(surface, reading) DO UPDATE SET
                 priority = min(priority, excluded.priority),
                 reading_priority = min(reading_priority, excluded.reading_priority),
                 reading_position = min(reading_position, excluded.reading_position),
                 form_rank = min(form_rank, excluded.form_rank)""",
            batch,
        )
        batch.clear()

    with gzip.open(source, "rb") as stream:
        for event, elem in ET.iterparse(stream, events=("end",)):
            if elem.tag != "entry":
                continue
            entries += 1
            pos_tags = {node.text for node in elem.findall("./sense/pos") if node.text}
            surfaces: dict[str, list[str]] = {}
            for k_ele in elem.findall("k_ele"):
                surface = k_ele.findtext("keb")
                if surface:
                    surfaces[surface] = [p.text or "" for p in k_ele.findall("ke_pri")]
            has_surface_priority = any(tags for tags in surfaces.values())
            for reading_position, r_ele in enumerate(elem.findall("r_ele")):
                reading = r_ele.findtext("reb")
                if not reading or r_ele.find("re_nokanji") is not None:
                    continue
                reading_information = {
                    node.text for node in r_ele.findall("re_inf") if node.text
                }
                if reading_information & EXCLUDED_READING_INFORMATION:
                    continue
                restrictions = {r.text for r in r_ele.findall("re_restr") if r.text}
                reading_pri = [p.text or "" for p in r_ele.findall("re_pri")]
                for surface, surface_pri in surfaces.items():
                    if restrictions and surface not in restrictions:
                        continue
                    # When JMdict marks only one spelling as common, keep that
                    # surface ahead of historical/variant spellings even when
                    # all of them share a common reading tag.
                    pair_priority = priority(
                        surface_pri if has_surface_priority else reading_pri
                    )
                    reading_priority = priority(reading_pri)
                    batch.append(
                        (surface, reading, pair_priority, reading_priority,
                         reading_position, 0)
                    )
                    # Generating every obscure/historical variant would more
                    # than double the bundled mobile database and crowd normal
                    # IME candidates. JMdict-prioritized vocabulary covers the
                    # useful conversion set while base-form lookup stays full.
                    if pair_priority < 100:
                        batch.extend(
                            (inflected_surface, inflected_reading, pair_priority,
                             reading_priority, reading_position, form_rank)
                            for inflected_surface, inflected_reading, form_rank
                            in inflected_forms(surface, reading, pos_tags)
                        )
            elem.clear()
            if len(batch) >= 40_000:
                flush()
    if batch:
        flush()
    base_pairs = db.execute(
        "SELECT count(*) FROM word_reading WHERE form_rank=0"
    ).fetchone()[0]
    inflected_pairs = db.execute(
        "SELECT count(*) FROM word_reading WHERE form_rank>0"
    ).fetchone()[0]
    return entries, base_pairs, inflected_pairs


def import_jmnedict_places(db: sqlite3.Connection, source: Path) -> tuple[int, int]:
    """Import place and railway-station surface/reading pairs from JMnedict."""
    entries = 0
    batch: list[tuple[str, str, int, int, int, int]] = []

    def flush() -> None:
        db.executemany(
            """INSERT INTO word_reading(
                   surface, reading, priority, reading_priority,
                   reading_position, form_rank
               ) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(surface, reading) DO UPDATE SET
                 priority = min(priority, excluded.priority),
                 reading_priority = min(reading_priority, excluded.reading_priority),
                 reading_position = min(reading_position, excluded.reading_position),
                 form_rank = min(form_rank, excluded.form_rank)""",
            batch,
        )
        batch.clear()

    with gzip.open(source, "rb") as stream:
        for event, elem in ET.iterparse(stream, events=("end",)):
            if elem.tag != "entry":
                continue
            name_types = {
                node.text for node in elem.findall("./trans/name_type") if node.text
            }
            if not name_types & GEOGRAPHIC_NAME_TYPES:
                elem.clear()
                continue
            entries += 1
            surfaces = [node.text for node in elem.findall("./k_ele/keb") if node.text]
            for reading_position, r_ele in enumerate(elem.findall("r_ele")):
                reading = r_ele.findtext("reb")
                if not reading:
                    continue
                restrictions = {
                    node.text for node in r_ele.findall("re_restr") if node.text
                }
                for surface in surfaces:
                    if restrictions and surface not in restrictions:
                        continue
                    batch.append(
                        (surface, reading, GEOGRAPHIC_NAME_PRIORITY,
                         GEOGRAPHIC_NAME_PRIORITY, reading_position, 0)
                    )
            elem.clear()
            if len(batch) >= 40_000:
                flush()
    if batch:
        flush()
    place_pairs = db.execute(
        """SELECT count(*) FROM word_reading
           WHERE priority=? AND reading_priority=? AND form_rank=0""",
        (GEOGRAPHIC_NAME_PRIORITY, GEOGRAPHIC_NAME_PRIORITY),
    ).fetchone()[0]
    return entries, place_pairs


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
    parser.add_argument("--jmnedict", required=True, type=Path)
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
        entries, word_pairs, inflected_pairs = import_jmdict(db, args.jmdict)
        place_entries, place_pairs = import_jmnedict_places(db, args.jmnedict)
        labels = model_labels(args.model_archive)
        han_labels, missing = validate(db, labels)
        metadata = {
            "schema_version": SCHEMA_VERSION,
            "kanjidic_date": kanjidic_date,
            "kanjidic_sha256": sha256(args.kanjidic),
            "jmdict_sha256": sha256(args.jmdict),
            "jmnedict_sha256": sha256(args.jmnedict),
            "model_archive_sha256": sha256(args.model_archive),
            "kanji_characters": str(chars),
            "kanji_readings": str(kanji_readings),
            "kanji_priorities": str(kanji_priorities),
            "jmdict_entries": str(entries),
            "jmnedict_place_entries": str(place_entries),
            "geographic_name_pairs": str(place_pairs),
            "word_pairs": str(word_pairs),
            "inflected_pairs": str(inflected_pairs),
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
