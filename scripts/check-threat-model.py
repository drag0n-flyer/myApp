#!/usr/bin/env python3
"""Check docs/security/threat-model.md. Standard library only.

Rules (plan 9, Task 2):
  * Every row of every table under "Threats" has the columns
    ID | STRIDE | Threat | Impact | Mitigation | Residual.
  * The Mitigation cell is not empty. It holds one or more references
    separated by ";". A reference is either `plan <N> <TaskID>` or `OPEN-<n>`.
  * `plan N TaskID` must match a "Task <TaskID>:" heading in the plan file that
    row N of docs/superpowers/plans/README.md links to.
  * Every OPEN-n used anywhere outside "Open risks" is defined under "Open risks".
  * Row IDs are unique, look like `SRV-S-01`, and the letter is one of the row's
    STRIDE letters.

Usage: check-threat-model.py [--doc PATH] [--readme PATH]
Exit status: 0 when there are no findings, 1 otherwise.
"""

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DOC = REPO_ROOT / "docs" / "security" / "threat-model.md"
DEFAULT_README = REPO_ROOT / "docs" / "superpowers" / "plans" / "README.md"

COLUMNS = ["ID", "STRIDE", "Threat", "Impact", "Mitigation", "Residual"]

HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
ROADMAP_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*\[[^\]]*\]\(([^)\s]+)\)")
# The plans use "### Task X:" (plan 5 uses "## Task X:"), so accept two to four hashes.
TASK_HEADING = re.compile(r"^#{2,4}\s+Task\s+([A-Za-z0-9]+):")
PLAN_REF = re.compile(r"^plan\s+(\d+)\s+([A-Za-z0-9]+)$")
OPEN_REF = re.compile(r"^OPEN-(\d+)$")
OPEN_ANY = re.compile(r"\bOPEN-\d+\b")
ID_FORMAT = re.compile(r"^[A-Z]{2,5}-([STRIDE])-\d{2,}$")


def section_name(title):
    """'5. Threats' -> 'threats'."""
    return re.sub(r"^\d+[.)]?\s*", "", title).strip().lower()


def split_sections(lines):
    """Return [(level, title, first_body_line_index, lines)] for every heading."""
    headings = []
    in_fence = False
    for i, line in enumerate(lines):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = HEADING.match(line)
        if m:
            headings.append((i, len(m.group(1)), m.group(2)))
    return headings


def section_lines(lines, headings, wanted):
    """Lines of the level-2 section called `wanted`, including its subsections."""
    for idx, (start, level, title) in enumerate(headings):
        if level == 2 and section_name(title) == wanted:
            end = len(lines)
            for later_start, later_level, _ in headings[idx + 1:]:
                if later_level <= 2:
                    end = later_start
                    break
            return start, end
    return None


def split_row(line):
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    return cells


def is_separator(cells):
    return all(re.fullmatch(r":?-{3,}:?", c) for c in cells if c) and any(cells)


def load_plan_tasks(readme_path):
    """Map plan number -> set of task IDs (or None if the plan file is missing)."""
    plans = {}
    for line in readme_path.read_text(encoding="utf-8").splitlines():
        m = ROADMAP_ROW.match(line)
        if not m:
            continue
        number = int(m.group(1))
        plan_file = readme_path.parent / m.group(2)
        if not plan_file.is_file():
            plans[number] = None
            continue
        tasks = set()
        for plan_line in plan_file.read_text(encoding="utf-8").splitlines():
            t = TASK_HEADING.match(plan_line)
            if t:
                tasks.add(t.group(1))
        plans[number] = tasks
    return plans


def check(doc_path, readme_path):
    """Return a list of findings. An empty list means the document passes."""
    errors = []
    lines = Path(doc_path).read_text(encoding="utf-8").splitlines()
    plans = load_plan_tasks(Path(readme_path))
    headings = split_sections(lines)

    threats = section_lines(lines, headings, "threats")
    open_risks = section_lines(lines, headings, "open risks")

    # OPEN-n definitions: every OPEN-n mentioned inside "Open risks".
    defined_open = set()
    if open_risks is None:
        errors.append('missing section "Open risks"')
    else:
        defined_open = set(OPEN_ANY.findall("\n".join(lines[open_risks[0]:open_risks[1]])))

    # Every OPEN-n mentioned outside "Open risks" must be defined there.
    for number, line in enumerate(lines, start=1):
        if open_risks and open_risks[0] <= number - 1 < open_risks[1]:
            continue
        for ref in OPEN_ANY.findall(line):
            if ref not in defined_open:
                errors.append(f"line {number}: {ref} is not listed under Open risks")

    if threats is None:
        errors.append('missing section "Threats"')
        return dedupe(errors)

    seen_ids = {}
    rows_seen = 0
    header_ok = False
    for number in range(threats[0], threats[1]):
        line = lines[number]
        if not line.lstrip().startswith("|"):
            header_ok = False
            continue
        cells = split_row(line)
        if cells and [c.lower() for c in cells] == [c.lower() for c in COLUMNS]:
            header_ok = True
            continue
        if is_separator(cells):
            continue
        where = f"line {number + 1}"
        if not header_ok:
            errors.append(
                f"{where}: table header must be: " + " | ".join(COLUMNS)
            )
            header_ok = True  # report once per table
            continue
        rows_seen += 1
        if len(cells) != len(COLUMNS):
            errors.append(f"{where}: expected {len(COLUMNS)} cells, found {len(cells)}")
            continue
        row_id, stride, _threat, _impact, mitigation, _residual = cells
        label = row_id or where
        errors.extend(check_id(label, where, row_id, stride, seen_ids, number + 1))
        errors.extend(check_mitigation(label, where, mitigation, plans, defined_open))

    if rows_seen == 0:
        errors.append("the Threats section has no table rows")
    return dedupe(errors)


def check_id(label, where, row_id, stride, seen_ids, line_number):
    errors = []
    m = ID_FORMAT.match(row_id)
    if not m:
        errors.append(f"{where}: ID {row_id!r} does not look like SRV-S-01")
    else:
        letters = set(re.findall(r"[STRIDE]", stride.upper()))
        if m.group(1) not in letters:
            errors.append(
                f"{where}: {row_id} has STRIDE letter {m.group(1)} but the STRIDE cell is {stride!r}"
            )
    if row_id in seen_ids:
        errors.append(
            f"{where}: duplicate ID {row_id} (first used on line {seen_ids[row_id]})"
        )
    else:
        seen_ids[row_id] = line_number
    return errors


def check_mitigation(label, where, mitigation, plans, defined_open):
    errors = []
    if not mitigation.strip():
        return [f"{where}: {label} has an empty Mitigation cell"]
    for part in (p.strip() for p in mitigation.split(";")):
        if not part:
            errors.append(f"{where}: {label} has an empty reference in Mitigation")
            continue
        plan_match = PLAN_REF.match(part)
        open_match = OPEN_REF.match(part)
        if plan_match:
            number = int(plan_match.group(1))
            task = plan_match.group(2)
            if number not in plans:
                errors.append(
                    f"{where}: {label} cites plan {number}, which is not in the roadmap table"
                )
            elif plans[number] is None:
                errors.append(f"{where}: {label} cites plan {number}, but its plan file is missing")
            elif task not in plans[number]:
                errors.append(
                    f"{where}: {label} cites {part!r}, but plan {number} has no task {task}"
                )
        elif open_match:
            if part not in defined_open:
                errors.append(f"{where}: {label} cites {part}, which is not listed under Open risks")
        else:
            errors.append(
                f"{where}: {label} Mitigation {part!r} is not 'plan <N> <TaskID>' or 'OPEN-<n>'"
            )
    return errors


def dedupe(errors):
    seen = set()
    out = []
    for e in errors:
        if e not in seen:
            seen.add(e)
            out.append(e)
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--doc", type=Path, default=DEFAULT_DOC)
    parser.add_argument("--readme", type=Path, default=DEFAULT_README)
    args = parser.parse_args(argv)
    errors = check(args.doc, args.readme)
    for e in errors:
        print(f"{args.doc}: {e}", file=sys.stderr)
    if errors:
        print(f"{len(errors)} finding(s)", file=sys.stderr)
        return 1
    print(f"{args.doc}: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
