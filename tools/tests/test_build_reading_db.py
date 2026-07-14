import gzip
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from tools import build_reading_db as builder


REQUIRED_PARTICLES = (
    "は", "が", "を", "に", "で", "と", "の", "へ", "も", "や", "か", "ね", "よ",
)
REQUIRED_AUXILIARIES = ("た", "だ", "です", "ます", "ない", "たい")


def kana_function_entries() -> str:
    entries = []
    readings = (
        [(reading, "particle") for reading in REQUIRED_PARTICLES if reading != "の"]
        + [(reading, "auxiliary verb") for reading in REQUIRED_AUXILIARIES if reading != "ます"]
    )
    for sequence, (reading, pos) in enumerate(readings, start=100):
        entries.append(f"""
  <entry>
    <ent_seq>{sequence}</ent_seq>
    <r_ele><reb>{reading}</reb><re_pri>spec1</re_pri></r_ele>
    <sense><pos>{pos}</pos></sense>
  </entry>""")
    return "".join(entries)


JMDICT_FIXTURE = f"""<?xml version="1.0" encoding="UTF-8"?>
<JMdict>
  <entry>
    <ent_seq>1</ent_seq>
    <k_ele><keb>甲</keb><ke_pri>ichi1</ke_pri></k_ele>
    <k_ele><keb>乙</keb></k_ele>
    <r_ele><reb>こう</reb><re_pri>ichi1</re_pri></r_ele>
    <r_ele><reb>おつ</reb><re_restr>乙</re_restr></r_ele>
    <sense><stagk>甲</stagk><stagr>こう</stagr><pos>pronoun</pos></sense>
    <sense><stagk>乙</stagk><stagr>おつ</stagr><pos>particle</pos></sense>
  </entry>
  <entry>
    <ent_seq>2</ent_seq>
    <k_ele><keb>此</keb></k_ele>
    <r_ele><reb>これ</reb><re_pri>ichi1</re_pri></r_ele>
    <sense>
      <pos>pronoun</pos>
      <misc>word usually written using kana alone</misc>
    </sense>
  </entry>
  <entry>
    <ent_seq>3</ent_seq>
    <r_ele><reb>かなだけ</reb><re_pri>ichi1</re_pri></r_ele>
    <sense><pos>expressions (phrases, clauses, etc.)</pos></sense>
  </entry>
  <entry>
    <ent_seq>4</ent_seq>
    <k_ele><keb>仮</keb></k_ele>
    <r_ele><reb>カリ</reb><re_nokanji/></r_ele>
    <sense><pos>noun (common) (futsuumeishi)</pos></sense>
  </entry>
  <entry>
    <ent_seq>5</ent_seq>
    <k_ele><keb>読む</keb><ke_pri>ichi1</ke_pri></k_ele>
    <r_ele><reb>よむ</reb><re_pri>ichi1</re_pri></r_ele>
    <sense><pos>Godan verb with 'mu' ending</pos></sense>
  </entry>
  <entry>
    <ent_seq>6</ent_seq>
    <k_ele><keb>乃</keb><ke_inf>search-only kanji form</ke_inf></k_ele>
    <k_ele><keb>之</keb><ke_inf>search-only kanji form</ke_inf></k_ele>
    <r_ele><reb>の</reb><re_pri>spec1</re_pri></r_ele>
    <sense><pos>particle</pos></sense>
  </entry>
  <entry>
    <ent_seq>7</ent_seq>
    <k_ele><keb>〼</keb><ke_inf>search-only kanji form</ke_inf></k_ele>
    <r_ele><reb>ます</reb></r_ele>
    <sense><pos>auxiliary verb</pos></sense>
  </entry>
  <entry>
    <ent_seq>8</ent_seq>
    <k_ele><keb>辺</keb></k_ele>
    <r_ele><reb>へ</reb></r_ele>
    <sense><pos>suffix</pos><misc>archaic</misc></sense>
  </entry>
  <entry>
    <ent_seq>9</ent_seq>
    <k_ele><keb>他</keb><ke_pri>ichi1</ke_pri></k_ele>
    <k_ele><keb>田</keb><ke_pri>ichi1</ke_pri></k_ele>
    <r_ele><reb>た</reb><re_pri>ichi1</re_pri></r_ele>
    <sense><pos>noun (common) (futsuumeishi)</pos></sense>
  </entry>
  {kana_function_entries()}
</JMdict>
"""


class BuildReadingDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.source = Path(self.temp_dir.name) / "JMdict_e.gz"
        with gzip.GzipFile(filename=self.source, mode="wb", mtime=0) as stream:
            stream.write(JMDICT_FIXTURE.encode())

    def tearDown(self):
        self.temp_dir.cleanup()

    def build_conversion(self):
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        builder.create_schema(db)
        builder.import_jmdict_conversion(db, self.source)
        return db

    def test_fixed_pos_and_connection_matrix(self):
        db = self.build_conversion()
        self.assertEqual(list(builder.CONVERSION_POS.items()), db.execute(
            "SELECT id, name FROM conversion_pos ORDER BY id"
        ).fetchall())
        self.assertEqual(256, db.execute("SELECT count(*) FROM connection_cost").fetchone()[0])
        preferred = db.execute(
            "SELECT cost FROM connection_cost WHERE previous_right_id=2 AND next_left_id=5"
        ).fetchone()[0]
        generic = db.execute(
            "SELECT cost FROM connection_cost WHERE previous_right_id=2 AND next_left_id=3"
        ).fetchone()[0]
        self.assertLess(preferred, generic)

    def test_sense_pos_and_spelling_restrictions_do_not_leak(self):
        db = self.build_conversion()
        rows = db.execute(
            "SELECT reading, surface, left_id FROM conversion_lexeme WHERE surface IN ('甲', '乙')"
        ).fetchall()
        self.assertEqual([("おつ", "乙", 5), ("こう", "甲", 2)], sorted(rows))

    def test_kana_only_nokanji_usually_kana_and_inflection(self):
        db = self.build_conversion()
        self.assertIsNotNone(db.execute(
            "SELECT 1 FROM conversion_lexeme WHERE reading='かなだけ' AND surface='かなだけ' AND left_id=12"
        ).fetchone())
        self.assertIsNotNone(db.execute(
            "SELECT 1 FROM conversion_lexeme WHERE reading='カリ' AND surface='カリ' AND left_id=3"
        ).fetchone())
        self.assertIsNone(db.execute(
            "SELECT 1 FROM conversion_lexeme WHERE reading='カリ' AND surface='仮'"
        ).fetchone())
        costs = dict(db.execute(
            "SELECT surface, word_cost FROM conversion_lexeme WHERE reading='これ'"
        ))
        self.assertLess(costs["これ"], costs["此"])
        self.assertIsNotNone(db.execute(
            "SELECT 1 FROM conversion_lexeme WHERE reading='よんで' AND surface='読んで' AND left_id=7 AND form_rank>0"
        ).fetchone())

    def test_required_modern_function_words_have_preferred_kana_lexemes(self):
        db = self.build_conversion()
        for reading, pos_id in (
            [(reading, 5) for reading in REQUIRED_PARTICLES]
            + [(reading, 6) for reading in REQUIRED_AUXILIARIES]
        ):
            row = db.execute(
                """SELECT word_cost, source FROM conversion_lexeme
                   WHERE reading=? AND surface=? AND left_id=?""",
                (reading, reading, pos_id),
            ).fetchone()
            self.assertIsNotNone(row, f"missing kana function word {reading}/{pos_id}")
            self.assertEqual("jmdict_kana", row[1])
            self.assertLess(row[0], 420, f"kana function word is too costly: {reading}")

    def test_historical_function_spellings_are_retained_but_demoted(self):
        db = self.build_conversion()
        builder.limit_conversion_lexemes(db)
        kana_no = db.execute(
            """SELECT word_cost FROM conversion_lexeme
               WHERE reading='の' AND surface='の' AND left_id=5"""
        ).fetchone()[0]
        for historical in ("乃", "之"):
            row = db.execute(
                """SELECT word_cost FROM conversion_lexeme
                   WHERE reading='の' AND surface=? AND left_id=5""",
                (historical,),
            ).fetchone()
            self.assertIsNotNone(row, f"historical spelling was removed: {historical}")
            self.assertGreater(row[0], kana_no)

        kana_masu = db.execute(
            """SELECT word_cost FROM conversion_lexeme
               WHERE reading='ます' AND surface='ます' AND left_id=6"""
        ).fetchone()[0]
        historical_masu = db.execute(
            """SELECT word_cost FROM conversion_lexeme
               WHERE reading='ます' AND surface='〼' AND left_id=6"""
        ).fetchone()[0]
        self.assertGreater(historical_masu, kana_masu)

        particle_he = db.execute(
            """SELECT word_cost FROM conversion_lexeme
               WHERE reading='へ' AND surface='へ' AND left_id=5"""
        ).fetchone()[0]
        archaic_suffix = db.execute(
            """SELECT word_cost FROM conversion_lexeme
               WHERE reading='へ' AND surface='辺' AND left_id=11"""
        ).fetchone()[0]
        self.assertGreater(archaic_suffix, particle_he)

    def test_past_auxiliary_outranks_common_noun_homophones(self):
        db = self.build_conversion()
        auxiliary_cost = db.execute(
            """SELECT word_cost FROM conversion_lexeme
               WHERE reading='た' AND surface='た' AND left_id=6"""
        ).fetchone()[0]
        noun_costs = [
            row[0] for row in db.execute(
                """SELECT word_cost FROM conversion_lexeme
                   WHERE reading='た' AND surface IN ('他', '田') AND left_id=3"""
            )
        ]
        self.assertEqual(2, len(noun_costs))
        self.assertTrue(all(auxiliary_cost < cost for cost in noun_costs))

    def test_vocabulary_cap_reserves_kana_function_words(self):
        db = self.build_conversion()
        rows = [
            ("ほご", f"候補{number:02d}", 3, 3, 0, 0, "test")
            for number in range(20)
        ]
        rows.append(("ほご", "ほご", 5, 5, 9_999, 0, "jmdict_kana"))
        builder.insert_conversion_lexemes(db, rows)
        builder.limit_conversion_lexemes(db)
        self.assertIsNotNone(db.execute(
            """SELECT 1 FROM conversion_lexeme
               WHERE reading='ほご' AND surface='ほご' AND left_id=5"""
        ).fetchone())
        self.assertEqual(
            builder.MAX_CONVERSION_LEXEMES_PER_READING,
            db.execute(
                "SELECT count(*) FROM conversion_lexeme WHERE reading='ほご'"
            ).fetchone()[0],
        )

    def test_vocabulary_cap_and_content_are_deterministic(self):
        snapshots = []
        for _ in range(2):
            db = self.build_conversion()
            rows = [
                ("おなじ", f"候補{number:02d}", 3, 3, 1_000, 0, "test")
                for number in range(15, -1, -1)
            ]
            builder.insert_conversion_lexemes(db, rows)
            builder.limit_conversion_lexemes(db)
            self.assertLessEqual(
                db.execute(
                    "SELECT max(n) FROM (SELECT count(*) AS n FROM conversion_lexeme GROUP BY reading)"
                ).fetchone()[0],
                builder.MAX_CONVERSION_LEXEMES_PER_READING,
            )
            snapshots.append("\n".join(db.iterdump()))
        self.assertEqual(snapshots[0], snapshots[1])

    def test_android_normalization_keeps_a_valid_database(self):
        path = Path(self.temp_dir.name) / "normalized.db"
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE sample(value TEXT PRIMARY KEY) WITHOUT ROWID")
        db.execute("INSERT INTO sample VALUES ('ok')")
        db.commit()
        db.close()

        builder.normalize_for_android(path)

        with closing(sqlite3.connect(path)) as normalized:
            self.assertEqual("ok", normalized.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual("ok", normalized.execute("SELECT value FROM sample").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
