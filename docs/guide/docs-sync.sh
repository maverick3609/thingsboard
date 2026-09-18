#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright The Inferrix Authors
# SPDX-License-Identifier: Apache-2.0
#
# Keeps the feature documentation honest around a commit. Install with
# docs/guide/install-hooks.sh; .git/hooks is not tracked.
#
#   --stage   (pre-commit)   INFERRIX.md is staged → re-render the published guide
#                            from it and stage the result, so the markdown and the
#                            HTML can never disagree. Blocks the commit only when the
#                            markdown itself is broken (a link to a section that no
#                            longer exists).
#
#   --audit   (post-commit)  A feature commit that changed Inferrix code without
#                            touching INFERRIX.md is recorded in docs/guide/DOC-DEBT.md.
#
# What it deliberately does NOT do: write prose, amend a commit, or commit anything
# itself. A hook cannot know what a feature does — it can only render what the
# markdown already says and name what is missing.
set -uo pipefail

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0

GUIDE_MD=INFERRIX.md
GUIDE_HTML=docs/guide/inferrix-guide.html
RENDERER=docs/guide/render-guide.py
DEBT=docs/guide/DOC-DEBT.md

# Paths whose behaviour the guide describes. A change under one of these that the
# guide does not mention is the drift worth recording.
DOCUMENTED='inferrix|/(scheduler|report|dashboardreport|license|role|wl|white-labeling)/|(License|Role|WhiteLabeling|SchedulerEvent|Report|ReportTemplate|DashboardReport)Controller\.java|schema-inferrix\.sql'

render() {
    if ! python3 -c 'import markdown' >/dev/null 2>&1; then
        echo "docs-sync: python3 'markdown' package not installed — guide not re-rendered" >&2
        return 2
    fi
    python3 "$RENDERER" >/dev/null
}

case "${1:---audit}" in
--stage)
    git diff --cached --name-only --diff-filter=ACM | grep -qx "$GUIDE_MD" || exit 0
    render
    case $? in
    0) git add "$GUIDE_HTML" ;;
    2) exit 0 ;;                        # renderer unavailable: never block a commit
    *)
        python3 "$RENDERER" >&2          # re-run to show the reason
        echo "docs-sync: $GUIDE_MD does not render — fix it or commit with --no-verify" >&2
        exit 1
        ;;
    esac
    ;;

--audit)
    git rev-parse -q --verify HEAD^2 >/dev/null 2>&1 && exit 0   # merge: upstream's work
    subject=$(git log -1 --pretty=%s)
    case "$subject" in feat*|fix*) ;; *) exit 0 ;; esac

    files=$(git diff-tree --no-commit-id --name-only -r HEAD)
    if grep -qx "$GUIDE_MD" <<<"$files"; then
        open=$(grep -c '^- \[ \]' "$DEBT" 2>/dev/null || true)
        [ "${open:-0}" -gt 0 ] &&
            echo "docs-sync: $open open item(s) in $DEBT — tick off what this commit documented"
        exit 0
    fi
    grep -qE "$DOCUMENTED" <<<"$files" || exit 0

    sha=$(git rev-parse --short HEAD)
    grep -q "$sha" "$DEBT" 2>/dev/null && exit 0
    [ -f "$DEBT" ] || cat > "$DEBT" <<'EOF'
# Documentation debt

Commits that changed a feature the guide describes without changing the guide.
Appended by `docs/guide/docs-sync.sh --audit`. Tick an item off once
`INFERRIX.md` covers it, or delete it if there was nothing to say.

EOF
    printf -- '- [ ] `%s` %s\n' "$sha" "$subject" >> "$DEBT"
    echo "docs-sync: $sha is not described in $GUIDE_MD — noted in $DEBT"
    ;;

*)
    echo "usage: docs-sync.sh [--stage|--audit]" >&2
    exit 64
    ;;
esac
