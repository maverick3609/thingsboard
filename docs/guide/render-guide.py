#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: Copyright The Inferrix Authors
# SPDX-License-Identifier: Apache-2.0
#

"""Render INFERRIX.md into the styled feature guide published as an Artifact.

The markdown is the source of truth; this script only dresses it, so the
published guide can never say something the repository's own documentation
does not. Deterministic and offline: same markdown in, same HTML out.

    python3 docs/guide/render-guide.py [--check]

--check renders and exits 1 if the committed HTML is out of date, without
writing anything. Requires the `markdown` package (python3 -m pip install markdown).
"""

import pathlib
import re
import sys

import markdown

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "INFERRIX.md"
TEMPLATE = ROOT / "docs" / "guide" / "template.html"
OUTPUT = ROOT / "docs" / "guide" / "inferrix-guide.html"


def strip_front_matter(md: str) -> str:
    """Drop the H1 and the in-document Contents list — the page has its own."""
    md = re.sub(r"\A# .*?\n", "", md, count=1)
    md = re.sub(r"\n## Contents\n.*?\n---\n", "\n", md, count=1, flags=re.S)
    return md


def facts(md: str) -> str:
    """The masthead fact list, read out of the Overview tables."""

    def cell(label: str) -> str:
        m = re.search(rf"^\| {re.escape(label)} \| (.+?) \|$", md, re.M)
        return re.sub(r"\s*\(.*?\)", "", m.group(1)).replace("`", "").strip() if m else "—"

    features = md.split("### The features", 1)[1].split("###", 1)[0]
    count = sum(1 for line in features.splitlines() if line.startswith("| [")) or "—"
    rows = [
        ("version", cell("Current version")),
        ("branch", cell("Integration branch")),
        ("base", cell("Base")),
        ("features", str(count)),
    ]
    return "\n".join(f"      <span>{k}</span><b>{v}</b>" for k, v in rows)


def sections(html: str) -> tuple[str, str]:
    """Split "N. Title" H2s into a numbered heading, and build the nav rail."""
    rail = []

    def head(m: re.Match) -> str:
        anchor, num, title = m.group(1), m.group(2), m.group(3)
        rail.append(
            f'<li><a href="#{anchor}"><span class="n">{num}</span>'
            f'<span class="t">{title}</span></a></li>'
        )
        return (
            f'<h2 id="{anchor}"><span class="sec-num">{num}</span><span>{title}</span></h2>'
        )

    html = re.sub(r'<h2 id="([^"]+)">(\d+)\.\s*(.*?)</h2>', head, html)
    return html, "\n".join(rail)


def dress(html: str) -> str:
    """Table wrappers and callouts — the two things markdown cannot express."""
    html = html.replace("<hr />\n", "")
    html = re.sub(r"<table>(.*?)</table>", r'<div class="tw"><table>\1</table></div>', html, flags=re.S)

    def aside(m: re.Match) -> str:
        body = m.group(1).strip()
        danger = "[!WARNING]" in body
        body = re.sub(r"<p>\[!WARNING\]\s*(<br\s*/?>)?\s*", "<p>", body)
        body = body.replace("<p></p>", "").strip()
        klass = "callout danger" if danger else "callout"
        return f'<aside class="{klass}">\n{body}\n</aside>'

    return re.sub(r"<blockquote>(.*?)</blockquote>", aside, html, flags=re.S)


def check_links(html: str) -> None:
    """A renumbered section that left a link behind is the guide's one rot mode."""
    ids = set(re.findall(r'<h[1-6] id="([^"]+)"', html))
    dangling = sorted({a for a in re.findall(r'href="#([^"]+)"', html) if a not in ids})
    if dangling:
        raise SystemExit(
            "INFERRIX.md links to headings that do not exist: " + ", ".join(dangling)
        )


def render() -> str:
    md = strip_front_matter(SOURCE.read_text(encoding="utf-8"))
    body = markdown.markdown(
        md, extensions=["tables", "fenced_code", "toc", "sane_lists", "md_in_html"]
    )
    body, rail = sections(body)
    check_links(body)
    page = TEMPLATE.read_text(encoding="utf-8")
    page = page.replace("{{FACTS}}", facts(SOURCE.read_text(encoding="utf-8")))
    page = page.replace("{{NAV}}", rail)
    return page.replace("{{ARTICLE}}", dress(body))


if __name__ == "__main__":
    page = render()
    if "--check" in sys.argv:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if current != page:
            print(f"{OUTPUT.relative_to(ROOT)} is out of date — run docs/guide/render-guide.py")
            sys.exit(1)
        print(f"{OUTPUT.relative_to(ROOT)} is current")
    else:
        OUTPUT.write_text(page, encoding="utf-8")
        print(f"wrote {OUTPUT.relative_to(ROOT)} ({len(page):,} bytes)")
