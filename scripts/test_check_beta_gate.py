import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-beta-gate.py")

HEADER = "| Item | Source | Evidence | Status |\n|---|---|---|---|\n"
BANNER = "> **Draft — needs lawyer review before use.**\n"


def make_root(rows, protocol="Clean.\n", review_log=None, legal_reviewed=False):
    root = tempfile.mkdtemp()

    def w(rel, text):
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)

    w("docs/release/beta-gate.md", "# Gate\n\n" + HEADER + rows)
    w("docs/protocol/README.md", protocol)
    if review_log is None:
        review_log = "| 2026-10-27 | none | Draft | Not reviewed. |\n"
    w("docs/security/threat-model.md",
      "# TM\n\n## 9. Review log\n\n| Date | Reviewer | Scope | Result |\n|---|---|---|---|\n" + review_log)
    after = "lawyer reviewed 2026-11-01\n" if legal_reviewed else ""
    w("docs/legal/terms.md", "# Terms\n\n" + BANNER + "\n" + after + "Text.\n")
    return root


def run(root, *args):
    return subprocess.run([sys.executable, SCRIPT, "--root", root, *args],
                          capture_output=True, text=True)


GOOD_REVIEW = "| 2026-11-02 | owner | All | 👤 2026-11-02 AB |\n"


class BetaGateTests(unittest.TestCase):
    def test_done_without_evidence_fails(self):
        root = make_root("| A | GOAL | | done |\n", review_log=GOOD_REVIEW, legal_reviewed=True)
        r = run(root)
        self.assertEqual(r.returncode, 1)
        self.assertIn("done without evidence", r.stdout)
        # still an error with --report: a malformed row is not just "open"
        self.assertEqual(run(root, "--report").returncode, 1)

    def test_done_with_link_passes(self):
        root = make_root("| A | GOAL | [run](https://example.org/run/1) | done |\n"
                         "| B | GOAL | 👤 2026-11-02 AB | done |\n",
                         review_log=GOOD_REVIEW, legal_reviewed=True)
        r = run(root)
        self.assertEqual(r.returncode, 0, r.stdout)

    def test_waived_requires_reason(self):
        root = make_root("| A | GOAL | | waived |\n"
                         "| B | GOAL | | waived (because, owner 2026-10-28) |\n")
        r = run(root, "--report")
        self.assertEqual(r.returncode, 1)
        self.assertIn("A:", r.stdout)
        self.assertNotIn("B:", r.stdout.split("errors")[-1] if "errors" in r.stdout else "")
        ok = make_root("| B | GOAL | | waived (because, owner 2026-10-28) |\n",
                       review_log=GOOD_REVIEW, legal_reviewed=True)
        self.assertEqual(run(ok).returncode, 0, run(ok).stdout)

    def test_unknown_status_fails(self):
        root = make_root("| A | GOAL | | maybe |\n")
        self.assertEqual(run(root, "--report").returncode, 1)

    def test_todo_in_protocol_fails(self):
        root = make_root("| A | GOAL | [r](https://e.org/1) | done |\n",
                         protocol="x TODO(plan 7): fix\n",
                         review_log=GOOD_REVIEW, legal_reviewed=True)
        r = run(root)
        self.assertEqual(r.returncode, 1)
        self.assertIn("TODO(plan 7)", r.stdout)

    def test_threat_model_without_owner_line_fails(self):
        root = make_root("| A | GOAL | [r](https://e.org/1) | done |\n", legal_reviewed=True)
        r = run(root)
        self.assertEqual(r.returncode, 1)
        self.assertIn("threat model", r.stdout)

    def test_legal_without_review_line_reports_open(self):
        root = make_root("| A | GOAL | [r](https://e.org/1) | done |\n", review_log=GOOD_REVIEW)
        r = run(root)
        self.assertEqual(r.returncode, 1)
        self.assertIn("terms.md", r.stdout)
        self.assertIn("open", r.stdout)

    def test_all_open_reports_but_exit_zero_with_report_flag(self):
        root = make_root("| A | GOAL | | open |\n| B | GOAL | | open |\n",
                         protocol="TODO(plan 7)\n")
        strict = run(root)
        self.assertEqual(strict.returncode, 1)
        rep = run(root, "--report")
        self.assertEqual(rep.returncode, 0, rep.stdout)
        self.assertIn("A", rep.stdout)
        self.assertIn("open", rep.stdout)


if __name__ == "__main__":
    unittest.main()
