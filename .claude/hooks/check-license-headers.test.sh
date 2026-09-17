#!/usr/bin/env bash
# Self-test for check-license-headers.sh, with no maven. Every fixture is a one-change copy of a
# header the plugin accepts, and every verdict in TABLE is what `mvn license:check`
# (license-maven-plugin 5.1.2) reported for exactly that file on 2026-09-18, when this fork took
# upstream's two-line SPDX headers.
#
#   bash .claude/hooks/check-license-headers.test.sh             exit 1 on any disagreement
#   bash .claude/hooks/check-license-headers.test.sh --emit DIR  only write the fixtures to DIR
#
# Re-record after a plugin upgrade: --emit ui-ngx/src/app/zz-fixtures, run
# `mvn -o -pl ui-ngx license:check`, make TABLE match its "Missing header in:" lines, and delete the
# directory before anything is built or committed.

set -uo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
HOOK="$REPO/.claude/hooks/check-license-headers.sh"

# + the plugin accepted it, - it refused it, . no such fixture. Columns: java scss html ts sql.
EXTS="java scss html ts sql"
TABLE='
ok                         +  +  +  +  +
other_owner                +  +  +  +  +
crlf                       +  +  +  +  +
trailing_ws                +  +  +  +  +
blank_first                +  +  +  +  +
blank_first_spaces         +  +  +  +  +
two_blank_after            +  +  +  +  +
spaces_after               +  +  +  +  +
no_blank_after             +  +  +  +  -
wrong_identifier           -  -  -  -  -
one_line                   -  -  -  -  -
swapped                    -  -  -  -  -
extra_blank_inside         -  -  -  -  -
no_space                   -  -  .  -  -
long_form                  -  -  -  -  -
indent2                    .  .  -  .  .
indent5                    .  .  -  .  .
no_inner_blanks            .  .  -  .  .
indent2_no_inner_blanks    .  .  -  .  .
no_outer                   .  .  .  .  -
foreign                    -  .  .  .  .
directive                  .  .  .  -  .
directive_under_malformed  .  .  .  -  .
thingsboard_malformed      -  .  .  .  .
'

# Prints "<verdict> <case>.<ext>" for every fixture.
cases() {
  printf '%s\n' "$TABLE" | awk -v exts="$EXTS" '
    NF == 6 { split(exts, x, " "); for (i = 2; i <= 6; i++) if ($i != ".") print $i, $1 "." x[i - 1] }'
}

# The header of a tracked file the plugin checks on every build, so known good, and owned by The
# Inferrix Authors, so swapping the owner tests the other one.
sample() {
  local path
  case "$1" in
    java) path=application/src/main/java/org/thingsboard/server/service/inferrix/ilb/IlbBlockCompiler.java ;;
    sql)  path=dao/src/main/resources/sql/schema-inferrix.sql ;;
    *)    path=ui-ngx/src/app/modules/home/pages/inferrix/controller/controller-tune.component.$1 ;;
  esac
  if [[ ! -f "$REPO/$path" ]]; then
    printf 'sample %s is gone; point sample() at another file with this header\n' "$path" >&2
    return 1
  fi
  case "$1" in
    java|scss|ts) head -n 2 "$REPO/$path" ;;
    html)         awk '{ print } $0 == "-->" { exit }' "$REPO/$path" ;;
    sql)          awk 'NR > 1 && $0 == "--" { print; exit } { print }' "$REPO/$path" ;;
  esac
}

# The long-form Apache header this fork carried until 4.3.1.5, in the style each type used then. A
# file that was never converted must still be refused.
long_form() {
  local body='Copyright © 2016-2026 The Inferrix Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.'
  case "$1" in
    java|scss) printf '/**\n'; printf '%s\n' "$body" | sed 's|^| * |; s| *$||'; printf ' */' ;;
    ts)        printf '///\n'; printf '%s\n' "$body" | sed 's|^|/// |; s| *$||'; printf '///' ;;
    html)      printf '<!--\n\n'; printf '%s\n' "$body" | sed 's|^|    |; s| *$||'; printf '\n-->' ;;
    sql)       printf '%s\n' '--'; printf '%s\n' "$body" | sed 's|^|-- |; s| *$||'; printf '%s' '--' ;;
  esac
}

DROP_INNER_BLANKS='{ l[NR] = $0 } END { for (i = 1; i <= NR; i++) if (i != 2 && i != NR - 1) print l[i] }'
DIRECTIVE='/// <reference types="node" />'

lines() { printf '%s\n' "$head" | "$@"; }

# fixture <case> <ext>: prints the file.
fixture() {
  local top="" gap=$'\n' body
  head="$(sample "$2")" || return 1
  case "$2" in
    java) body='package fixture;' ;;
    scss) body='.fixture { color: red; }' ;;
    html) body='<div></div>' ;;
    ts)   body='export const fixture = 1;' ;;
    sql)  body='SELECT 1;' ;;
  esac
  case "$1" in
    ok|crlf) ;;
    other_owner)        head="$(lines sed 's/The Inferrix Authors/The Thingsboard Authors/')" ;;
    trailing_ws)        head="$(lines sed 's/$/  /')" ;;
    blank_first)        top=$'\n\n' ;;
    blank_first_spaces) top=$'  \n\t\n' ;;
    two_blank_after)    gap=$'\n\n' ;;
    spaces_after)       gap=$'   \n' ;;
    no_blank_after)     gap='' ;;
    wrong_identifier)   head="$(lines sed 's/Apache-2\.0/Apache-2/')" ;;
    one_line)           head="$(lines grep -v 'SPDX-License-Identifier')" ;;
    swapped)            head="$(lines awk '/SPDX-FileCopyrightText/ { copyright = $0; next } /SPDX-License-Identifier/ { print; print copyright; next } { print }')" ;;
    extra_blank_inside) head="$(lines awk '{ print } /SPDX-FileCopyrightText/ { print "" }')" ;;
    no_space)           head="$(lines sed -e 's|^// |//|' -e 's|^-- |--|')" ;;
    long_form)          head="$(long_form "$2")" ;;
    indent2)            head="$(lines sed 's/^    /  /')" ;;
    indent5)            head="$(lines sed 's/^    /     /')" ;;
    no_inner_blanks)    head="$(lines awk "$DROP_INNER_BLANKS")" ;;
    indent2_no_inner_blanks)
                        head="$(lines sed 's/^    /  /')"; head="$(lines awk "$DROP_INNER_BLANKS")" ;;
    no_outer)           head="$(lines sed '1d;$d')" ;;
    foreign)            head=$'// Copyright (c) 2020 Example Corp.\n// SPDX-License-Identifier: Apache-2.0' ;;
    directive)          head="$DIRECTIVE"; gap='' ;;
    directive_under_malformed)
                        head="$(lines sed 's|^// |//|')"$'\n'"$DIRECTIVE"; gap='' ;;
    thingsboard_malformed)
                        head="$(lines sed -e 's/The Inferrix Authors/The Thingsboard Authors/' -e 's|^// |//|')" ;;
    *)                  printf 'no fixture called %s\n' "$1" >&2; return 1 ;;
  esac
  if [[ "$1" == crlf ]]; then
    printf '%s\n%s%s\n' "$head" "$gap" "$body" | awk '{ printf "%s\r\n", $0 }'
  else
    printf '%s%s\n%s%s\n' "$top" "$head" "$gap" "$body"
  fi
}

emit() {
  local verdict file
  mkdir -p "$1" || return 1
  while read -r verdict file; do
    fixture "${file%.*}" "${file##*.}" > "$1/$file" || return 1
  done <<< "$(cases)"
}

if [[ "${1:-}" == "--emit" ]]; then
  [[ -n "${2:-}" ]] || { printf 'usage: %s --emit DIR\n' "$0" >&2; exit 2; }
  emit "$2"
  exit
fi

T="$(mktemp -d)" || exit 1
trap 'rm -rf "$T"' EXIT
git init -q "$T" 2>/dev/null || exit 1
cp "$REPO/license-header-inferrix.txt" "$REPO/license-header.txt" "$T/" || exit 1
emit "$T/fx" || exit 1
git -C "$T" add -A || exit 1

failures=0
fail() { printf 'FAIL  %s\n' "$*"; failures=$((failures + 1)); }

# The hook names each file it refuses on stderr, indented two spaces.
refused() { (cd "$T" && bash "$HOOK" "$@" 2>&1 >/dev/null) | sed -n 's|^  fx/||p' | sort; }

want="$(cases | awk '$1 == "-" { print $2 }' | sort)"
for mode in staged --all; do
  got="$(refused "$mode")"
  if [[ "$got" != "$want" ]]; then
    fail "$mode: the hook disagrees with the plugin (< plugin refused, hook passed; > hook refused, plugin passed)"
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") | grep '^[<>]'
  fi
done

(cd "$T" && bash "$HOOK" --fix >/dev/null 2>&1)
[[ $? -eq 1 ]] || fail "--fix exited 0 though it left a file alone"
[[ "$(refused)" == "foreign.java" ]] || fail "after --fix the hook still refuses: $(refused | tr '\n' ' ')"
git -C "$T" diff --quiet -- fx/foreign.java || fail "--fix rewrote a third party's copyright notice"
for file in $(cases | awk '$1 == "-" && $2 != "foreign.java" { print $2 }'); do
  [[ "$(grep -c Copyright "$T/fx/$file")" == 1 ]] || fail "$file: --fix left more than one copyright line"
  [[ "$(tail -n 1 "$T/fx/$file")" == "$(fixture ok "${file##*.}" | tail -n 1)" ]] \
    || fail "$file: --fix lost the code under the header"
done
for file in directive.ts directive_under_malformed.ts; do
  grep -qxF "$DIRECTIVE" "$T/fx/$file" || fail "$file: --fix dropped the /// <reference> directive"
done
grep -q 'The Thingsboard Authors' "$T/fx/thingsboard_malformed.java" || fail "--fix changed a Thingsboard owner"

# What is committed is the staged copy, so a header fixed on disk but not re-added must still fail.
fixture long_form java > "$T/fx/late.java" && git -C "$T" add fx/late.java && fixture ok java > "$T/fx/late.java"
[[ "$(refused)" == $'foreign.java\nlate.java' ]] || fail "a header fixed on disk but not staged passed"

# enforce-license-header.sh stamps the same rendering the pre-commit check expects.
printf 'package fixture;\n' > "$T/fx/Stamped.java"
printf '{"tool_input":{"file_path":"%s"}}' "$T/fx/Stamped.java" \
  | bash "$REPO/.claude/hooks/enforce-license-header.sh" 2>/dev/null
git -C "$T" add fx/Stamped.java
[[ "$(refused)" == $'foreign.java\nlate.java' ]] \
  || fail "enforce-license-header.sh did not stamp a header license:check accepts"

if (( failures )); then
  printf '%s check(s) failed\n' "$failures"
  exit 1
fi
printf 'ok: %s fixtures agree with the plugin; --fix keeps code, directives, owners and notices not ours\n' \
  "$(cases | grep -c '')"
