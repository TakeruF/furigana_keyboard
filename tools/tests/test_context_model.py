import hashlib
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from tools.context_model import compile_context_model as compiler
from tools.context_model import train_context_model as trainer


ROOT = Path(__file__).resolve().parents[2]
COUNTS = ROOT / "tools/context_model/context-counts.tsv"
MODEL = ROOT / "app/src/main/assets/context-model.bin"


class ContextModelTest(unittest.TestCase):
    def test_checked_in_model_is_deterministic(self) -> None:
        encoded, metadata = compiler.compile_model(COUNTS)

        self.assertEqual(encoded, MODEL.read_bytes())
        self.assertEqual(metadata["formatVersion"], 1)
        self.assertEqual(metadata["modelVersion"], 1)
        self.assertEqual(metadata["unigramCount"], 2_048)
        self.assertEqual(metadata["bigramCount"], 8_194)
        self.assertEqual(metadata["modelBytes"], 219_009)
        self.assertEqual(
            metadata["sourceSha256"],
            "5275f10c2273647da817b8ba49558d82406e9b1435ca5246094b4d67097001ba",
        )
        self.assertEqual(
            metadata["modelSha256"],
            "22e83b94a259d900d5d60c058d9ba6773506ee43fc81b4f644da02a6495b2fc3",
        )

    def test_integer_costs_are_bounded_and_backoff_is_neutral(self) -> None:
        self.assertEqual(compiler.unigram_cost(1), 0)
        self.assertEqual(compiler.unigram_cost(1_000_000), 0)
        self.assertEqual(compiler.bigram_cost(1), -1_900)
        self.assertEqual(compiler.bigram_cost(1_000_000), -3_400)

    def test_counts_parser_rejects_duplicate_pair(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "counts.tsv"
            path.write_text(
                "# FKCTX_COUNTS 1\n"
                "kind\tprevious_surface\tnext_surface\tcount\n"
                "B\t学校\t行く\t2\n"
                "B\t学校\t行く\t3\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "duplicate bigram"):
                compiler.parse_counts(path)

    def test_context_counting_carries_across_particle_and_resets_on_copy(self) -> None:
        noun = trainer.Lexeme("学校", 3, 3, 0)
        particle = trainer.Lexeme("へ", 5, 5, 0)
        verb = trainer.Lexeme("行きます", 7, 7, 0)
        copy = trainer.Lexeme("?", trainer.COPY_ID, trainer.COPY_ID, 0, False)
        unigrams: Counter[str] = Counter()
        bigrams: Counter[tuple[str, str]] = Counter()

        trainer.add_tokens([noun, particle, verb, copy, noun], unigrams, bigrams)

        self.assertEqual(unigrams, Counter({"学校": 2, "行きます": 1}))
        self.assertEqual(bigrams, Counter({("学校", "行きます"): 1}))

    def test_count_table_records_all_fixed_input_hashes(self) -> None:
        header = COUNTS.read_text(encoding="utf-8").split(
            "kind\tprevious_surface\tnext_surface\tcount", 1
        )[0]
        self.assertIn(
            "# corpus_sha256=6f363cf9acc1efc0bf7bad645d0fb693a6e08bf3110fbdadcfc123a9e9612b6f",
            header,
        )
        database = ROOT / "app/src/main/assets/reading.db"
        self.assertIn(
            f"# database_sha256={hashlib.sha256(database.read_bytes()).hexdigest()}",
            header,
        )
        seeds = ROOT / "tools/context_model/seed-bigrams.tsv"
        self.assertIn(
            f"# seed_sha256={hashlib.sha256(seeds.read_bytes()).hexdigest()}",
            header,
        )


if __name__ == "__main__":
    unittest.main()
