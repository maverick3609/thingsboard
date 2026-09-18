#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright The Inferrix Authors
# SPDX-License-Identifier: Apache-2.0
#
# Self-test for docs-sync.sh, run against a throwaway repository:
#   bash docs/guide/docs-sync.test.sh
set -uo pipefail

HOOK=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/docs-sync.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fails=0

check() { # name expected actual
    if [ "$2" = "$3" ]; then
        echo "ok   $1"
    else
        echo "FAIL $1: expected [$2], got [$3]"
        fails=$((fails + 1))
    fi
}

cd "$work"
git init -q .
git config user.email t@t; git config user.name t
mkdir -p docs/guide application/src/main/java/org/thingsboard/server/service/inferrix
printf 'doc\n' > INFERRIX.md
cat > docs/guide/render-guide.py <<'PY'
import pathlib, sys
if pathlib.Path("BREAK").exists():
    sys.exit("broken link")
pathlib.Path("docs/guide/inferrix-guide.html").write_text(pathlib.Path("INFERRIX.md").read_text())
PY
git add -A; git commit -qm init

# --stage: markdown not staged -> no-op
bash "$HOOK" --stage >/dev/null 2>&1
check "stage/no-op when markdown untouched" 0 $?

# --stage: markdown staged -> html rendered and staged
printf 'doc v2\n' > INFERRIX.md; git add INFERRIX.md
bash "$HOOK" --stage >/dev/null 2>&1
check "stage/renders" 0 $?
check "stage/stages the html" "docs/guide/inferrix-guide.html" \
    "$(git diff --cached --name-only | grep inferrix-guide.html)"

# --stage: renderer refuses -> commit blocked
touch BREAK
bash "$HOOK" --stage >/dev/null 2>&1
check "stage/blocks on a broken guide" 1 $?
rm BREAK
bash "$HOOK" --stage >/dev/null 2>&1
git commit -qm "docs: guide"

# --audit: feature commit that skipped the guide
echo x > application/src/main/java/org/thingsboard/server/service/inferrix/A.java
git add -A; git commit -qm "feat(controllers): a thing"
bash "$HOOK" --audit >/dev/null 2>&1
check "audit/records undocumented feature work" 1 "$(grep -c '^- \[ \]' docs/guide/DOC-DEBT.md)"

# ...and does not record it twice
bash "$HOOK" --audit >/dev/null 2>&1
check "audit/deduplicates" 1 "$(grep -c '^- \[ \]' docs/guide/DOC-DEBT.md)"

# --audit: a commit that did touch the guide adds nothing
printf 'doc v3\n' > INFERRIX.md
echo y >> application/src/main/java/org/thingsboard/server/service/inferrix/A.java
git add -A; git commit -qm "feat(controllers): documented thing"
bash "$HOOK" --audit >/dev/null 2>&1
check "audit/silent when the guide moved too" 1 "$(grep -c '^- \[ \]' docs/guide/DOC-DEBT.md)"

# --audit: chore commits are not feature work
echo z >> application/src/main/java/org/thingsboard/server/service/inferrix/A.java
git add -A; git commit -qm "chore: tidy"
bash "$HOOK" --audit >/dev/null 2>&1
check "audit/ignores chore" 1 "$(grep -c '^- \[ \]' docs/guide/DOC-DEBT.md)"

# --audit: code outside the documented paths is not drift
mkdir -p common/other; echo q > common/other/B.java
git add -A; git commit -qm "feat(other): unrelated"
bash "$HOOK" --audit >/dev/null 2>&1
check "audit/ignores undocumented areas" 1 "$(grep -c '^- \[ \]' docs/guide/DOC-DEBT.md)"

# --audit: merge commits carry upstream's work
git checkout -q -b side HEAD~1
echo m > application/src/main/java/org/thingsboard/server/service/inferrix/C.java
git add -A; git commit -qm "feat(controllers): side"
git checkout -q -
git merge -q --no-ff -m "Merge branch 'side'" side >/dev/null
bash "$HOOK" --audit >/dev/null 2>&1
check "audit/ignores merges" 1 "$(grep -c '^- \[ \]' docs/guide/DOC-DEBT.md)"

echo
[ "$fails" -eq 0 ] && echo "all checks passed" || echo "$fails check(s) failed"
exit $((fails > 0))
