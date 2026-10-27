#!/usr/bin/env python3
"""Render docs/legal/*.md to deploy/docs/*.html. Standard library only.

Supported Markdown: headings, paragraphs, bullet lists, blockquotes, simple
pipe tables, and inline links, **bold** and `code`. All other text is escaped.
Rendering fails when the lawyer-review banner is missing.

Usage: render-legal.py [--src DIR] [--out DIR]
"""
import argparse
import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BANNER = "> **Draft — needs lawyer review before use.**"
PAGES = {"privacy-policy.md": "privacy.html", "terms.md": "terms.html"}

PAGES_BY_SRC = None  # set below
LINK = re.compile(r"\[([^\]]+)\]\(([^)\s]+)\)")
BOLD = re.compile(r"\*\*(.+?)\*\*")
CODE = re.compile(r"`([^`]+)`")

PAGES_BY_SRC = dict(PAGES)


class MissingBanner(ValueError):
    pass


def inline(text):
    """Escape first, then apply inline markup to the escaped text."""
    out = html.escape(text, quote=True)
    out = CODE.sub(r"<code>\1</code>", out)
    out = BOLD.sub(r"<strong>\1</strong>", out)

    def link(m):
        url = html.unescape(m.group(2))
        if not re.match(r"^(https?://|mailto:|#|/|[\w./-]+$)", url):
            return m.group(0)
        url = PAGES_BY_SRC.get(url, url)
        return '<a href="%s">%s</a>' % (html.escape(url, quote=True), m.group(1))

    return LINK.sub(link, out)


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def render_body(md):
    lines = md.splitlines()
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
        elif line.startswith("#"):
            m = re.match(r"^(#{1,6})\s+(.*)$", line)
            n = len(m.group(1))
            out.append("<h%d>%s</h%d>" % (n, inline(m.group(2)), n))
            i += 1
        elif line.startswith(">"):
            buf = []
            while i < len(lines) and lines[i].startswith(">"):
                buf.append(lines[i][1:].strip())
                i += 1
            out.append("<blockquote><p>%s</p></blockquote>" % inline(" ".join(buf)))
        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(cells(lines[i]))
                i += 1
            head, body = rows[0], [r for r in rows[2:]]
            t = ["<table>", "<thead><tr>"]
            t += ["<th>%s</th>" % inline(c) for c in head]
            t.append("</tr></thead><tbody>")
            for r in body:
                t.append("<tr>" + "".join("<td>%s</td>" % inline(c) for c in r) + "</tr>")
            t.append("</tbody></table>")
            out.append("".join(t))
        elif re.match(r"^[-*]\s+", line):
            items = []
            while i < len(lines) and re.match(r"^[-*]\s+", lines[i]):
                items.append(re.sub(r"^[-*]\s+", "", lines[i]))
                i += 1
            out.append("<ul>" + "".join("<li>%s</li>" % inline(x) for x in items) + "</ul>")
        else:
            buf = []
            while (i < len(lines) and lines[i].strip()
                   and not re.match(r"^(#|>|\||[-*]\s)", lines[i])):
                buf.append(lines[i].strip())
                i += 1
            out.append("<p>%s</p>" % inline(" ".join(buf)))
    return "\n".join(out)


def render(md, title="Document"):
    if BANNER not in md:
        raise MissingBanner("lawyer-review banner missing")
    return (
        '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        "<title>%s</title>\n"
        "<style>body{font:1rem/1.5 system-ui,sans-serif;max-width:42rem;margin:0 auto;padding:1rem}"
        "table{border-collapse:collapse}td,th{border:1px solid #888;padding:.25rem .5rem;text-align:start}"
        "blockquote{margin-inline:0;padding-inline-start:1rem;border-inline-start:4px solid #c80}"
        "</style>\n</head>\n<body>\n<main>\n%s\n</main>\n</body>\n</html>\n"
    ) % (html.escape(title), render_body(md))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(ROOT / "docs" / "legal"))
    ap.add_argument("--out", default=str(ROOT / "deploy" / "docs"))
    a = ap.parse_args(argv)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for src, dst in PAGES.items():
        md = (Path(a.src) / src).read_text(encoding="utf-8")
        first = next((l[2:] for l in md.splitlines() if l.startswith("# ")), src)
        try:
            page = render(md, first)
        except MissingBanner as e:
            print("%s: %s" % (src, e), file=sys.stderr)
            return 1
        (out / dst).write_text(page, encoding="utf-8")
        print("wrote", out / dst)
    return 0


if __name__ == "__main__":
    sys.exit(main())
