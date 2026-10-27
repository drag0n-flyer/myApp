"""Tests for check-threat-model.py. Standard library only.

Run from the repository root:
    python3 -m unittest scripts/test_check_threat_model.py
"""

import importlib.util
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parent / "check-threat-model.py"


def load_checker():
    spec = importlib.util.spec_from_file_location("check_threat_model", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


README = """# Roadmap

| # | Plan | Phase | Depends on |
|---|---|---|---|
| 1 | [App UI](app-ui.md) | 0 | none |
| 2 | [Go server](go-server.md) | 1 | none |
"""

PLAN_2 = """# Go server

### Task A3: Passkeys and sessions

Text.

### Task F1: Module and config

Text.
"""

PLAN_1 = """# App UI

### Task 1: Project

Text.
"""

TABLE_HEADER = (
    "| ID | STRIDE | Threat | Impact | Mitigation | Residual |\n"
    "|---|---|---|---|---|---|\n"
)


def row(id_, mitigation, stride="S"):
    return f"| {id_} | {stride} | A threat | An impact | {mitigation} | Some residual |\n"


def document(rows, open_risks="### OPEN-1 Something\n\nText.\n", extra=""):
    return (
        "# Threat model\n\n"
        "## 1. Scope and method\n\nText.\n\n"
        "## 5. Threats\n\n"
        "### Server API\n\n" + TABLE_HEADER + rows + "\n" + extra +
        "## 8. Open risks\n\n" + open_risks + "\n"
        "## 9. Review log\n\nNone yet.\n"
    )


class CheckThreatModelTests(unittest.TestCase):
    def setUp(self):
        self.checker = load_checker()
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = Path(self._tmp.name)
        (self.dir / "README.md").write_text(README, encoding="utf-8")
        (self.dir / "go-server.md").write_text(PLAN_2, encoding="utf-8")
        (self.dir / "app-ui.md").write_text(PLAN_1, encoding="utf-8")

    def run_check(self, text):
        doc = self.dir / "threat-model.md"
        doc.write_text(text, encoding="utf-8")
        return self.checker.check(doc, self.dir / "README.md")

    def test_rejects_empty_mitigation(self):
        errors = self.run_check(document(row("SRV-S-01", "")))
        self.assertTrue(any("SRV-S-01" in e and "Mitigation" in e for e in errors), errors)

    def test_rejects_unknown_task(self):
        errors = self.run_check(document(row("SRV-S-01", "plan 2 Z9")))
        self.assertTrue(any("Z9" in e for e in errors), errors)

    def test_rejects_open_without_entry(self):
        errors = self.run_check(document(row("SRV-S-01", "OPEN-7")))
        self.assertTrue(any("OPEN-7" in e for e in errors), errors)

    def test_rejects_duplicate_ids(self):
        rows = row("SRV-S-01", "plan 2 A3") + row("SRV-S-01", "plan 2 F1")
        errors = self.run_check(document(rows))
        self.assertTrue(any("duplicate" in e.lower() and "SRV-S-01" in e for e in errors), errors)

    def test_accepts_valid(self):
        rows = row("SRV-S-01", "plan 2 A3") + row("SRV-T-01", "OPEN-1", stride="T")
        self.assertEqual(self.run_check(document(rows)), [])

    # Cases beyond the five the plan lists.

    def test_rejects_unknown_plan_number(self):
        errors = self.run_check(document(row("SRV-S-01", "plan 9 A3")))
        self.assertTrue(any("plan 9" in e for e in errors), errors)

    def test_rejects_prose_in_mitigation(self):
        errors = self.run_check(document(row("SRV-S-01", "rate limits")))
        self.assertTrue(any("rate limits" in e for e in errors), errors)

    def test_accepts_several_references(self):
        rows = row("SRV-S-01", "plan 2 A3; plan 2 F1; plan 1 1; OPEN-1")
        self.assertEqual(self.run_check(document(rows)), [])

    def test_task_must_belong_to_the_named_plan(self):
        # A3 exists in plan 2 only; plan 1 has no such task.
        errors = self.run_check(document(row("SRV-S-01", "plan 1 A3")))
        self.assertTrue(any("A3" in e for e in errors), errors)

    def test_rejects_open_reference_outside_threats(self):
        extra = "## 6. Mode B\n\nSee OPEN-9 for details.\n\n"
        rows = row("SRV-S-01", "plan 2 A3")
        errors = self.run_check(document(rows, extra=extra))
        self.assertTrue(any("OPEN-9" in e for e in errors), errors)

    def test_rejects_id_that_disagrees_with_stride(self):
        errors = self.run_check(document(row("SRV-T-01", "plan 2 A3", stride="S")))
        self.assertTrue(any("SRV-T-01" in e for e in errors), errors)

    def test_rejects_missing_threats_section(self):
        errors = self.run_check("# Threat model\n\n## 8. Open risks\n\n### OPEN-1 x\n")
        self.assertTrue(any("Threats" in e for e in errors), errors)

    def test_rejects_wrong_table_header(self):
        text = document(row("SRV-S-01", "plan 2 A3")).replace("| Mitigation |", "| Fix |")
        errors = self.run_check(text)
        self.assertTrue(any("header" in e.lower() for e in errors), errors)


if __name__ == "__main__":
    unittest.main()
