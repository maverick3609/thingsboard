#!/usr/bin/env bash
# Refuses a commit whose source files would fail `mvn license:check`.
#
# That goal runs only in a full reactor build, minutes in and typically only when someone is about
# to deploy — and when it fails at the ui-ngx module, maven has not yet cleaned application/target,
# so the PREVIOUS boot jar is still sitting there looking like a successful build. This has cost a
# build cycle four times on this fork (7b95cf84f8, 94a1ecbe8e, 10e2c3a9df, b557ff20c4).
#
# The check is exact because the plugin is. Measured on 2026-09-18 by running the real goal
# (license-maven-plugin 5.1.2, upstream's two-line SPDX header) over one-change copies of files it
# accepts:
#   accepted  CRLF; trailing whitespace; blank lines before the header; either owner below; no
#             blank line under a // or <!-- --> header, which is how upstream leaves them;
#   rejected  any other difference inside the header — an indent, a blank line added or missing, a
#             missing space after the marker, one line instead of two, the two lines swapped, a
#             changed identifier, another comment style, the pre-SPDX long-form header — and a .sql
#             header with no blank line after it (-- has no closing marker on the file, so the blank
#             line is how the end is found).
# The check this replaced looked only for the opener on line 1 and a copyright line near the top,
# and passed the four .html templates b557ff20c4 had to fix: indented two spaces instead of four.
# check-license-headers.test.sh holds those copies and the verdict the plugin gave each one.
#
# The expected text is rendered from the plugin's own templates, so a change there cannot leave this
# script behind. Owner: the root pom points <header> at license-header-inferrix.txt and lists
# upstream's license-header.txt under <validHeaders>, so both are accepted.
#
# Usage:
#   check-license-headers.sh            check staged files (pre-commit); exit 1 if any fail
#   check-license-headers.sh --fix      rewrite the header of each failing staged file, then stage it
#   check-license-headers.sh --all      check the whole worktree (pre-flight before a deploy build)
#   check-license-headers.sh --header FILE
#                                       print the header FILE's type needs (enforce-license-header.sh)

set -uo pipefail

MODE="${1:-staged}"
ROOT="$(git rev-parse --show-toplevel)" && cd "$ROOT" || exit 1
INFERRIX="The Inferrix Authors"
THINGSBOARD="The Thingsboard Authors"
if [[ ! -f license-header-inferrix.txt || ! -f license-header.txt ]]; then
  printf '[license-header] %s lacks the licence templates, so the expected header is unknown\n' "$ROOT" >&2
  exit 1
fi

# Only the extensions this fork actually authors. The maven plugin checks more; anything outside
# this list still gets caught at build time, which is the existing (slow) safety net.
is_checked() {
  case "$1" in
    *.java|*.ts|*.scss|*.html|*.sql) ;;
    *) return 1 ;;
  esac
  # Mirrors the root pom's <excludes> for the paths that can match the extensions above. Getting
  # this wrong in the other direction is worse than missing a file: a hook that blocks a commit the
  # real plugin would accept trains people to pass --no-verify.
  case "$1" in
    */target/*|*/node_modules/*|*/.angular/*) return 1 ;;
    */src/test/resources/*) return 1 ;;
    .claude/*) return 1 ;;
    */NetworkReceive.java) return 1 ;;
    */apache/cassandra/io/*) return 1 ;;
    */resources/html/*|*/resources/svg/*|*/resources/fonts/*) return 1 ;;
    */lwm2m-registry/*|*/resources/lwm2m/*) return 1 ;;
    */src/main/data/resources/*) return 1 ;;
    */src/main/resources/public/static/rulenode/*) return 1 ;;
    ui/*|*/src/vendor/*|*/src/font/*|*/src/sh/*) return 1 ;;
  esac
  return 0
}

# The template text for an owner. Both are the two SPDX lines upstream adopted in 4.3.1.5; the
# owner is the only difference, so there is nothing to substitute.
template_body() {
  if [[ "$1" == "$THINGSBOARD" ]]; then
    cat license-header.txt
  else
    cat license-header-inferrix.txt
  fi
}

# The header in the comment style the root pom's <mapping> and the plugin defaults give each type.
# printf '%s' for the dashes: a bare printf '--' is read as an end-of-options marker and errors.
header_for() {
  case "$1" in
    *.java|*.scss|*.ts|*.js)
      template_body "$2" | sed 's|^|// |; s| *$||' ;;
    *.html)
      printf '<!--\n\n'; template_body "$2" | sed 's|^|    |; s| *$||'; printf '\n-->\n' ;;
    *.sql)
      printf '%s\n' '--'; template_body "$2" | sed 's|^|-- |; s| *$||'; printf '%s\n' '--' ;;
  esac
}

# So enforce-license-header.sh stamps this rendering rather than keeping a copy of its own.
if [[ "$MODE" == "--header" ]]; then
  header_for "${2:?usage: check-license-headers.sh --header FILE}" "$INFERRIX"
  exit
fi

# Rendered once per type and owner; bash 3.2 has no associative arrays, hence the variable names.
for ext in java scss ts html sql; do
  printf -v "EXPECTED_INFERRIX_$ext" '%s' "$(header_for "x.$ext" "$INFERRIX")"
  printf -v "EXPECTED_THINGSBOARD_$ext" '%s' "$(header_for "x.$ext" "$THINGSBOARD")"
done

# Normalises what the plugin tolerates (CR, trailing whitespace, blank lines above), then compares
# line for line; the "" forces a string comparison. Reads no further than the longest header needs.
MATCH_AWK='
function same(want,    w, n, k) {
  n = split(want, w, "\n")
  if (count < n) return 0
  for (k = 1; k <= n; k++) if (got[k] "" != w[k] "") return 0
  if (blank_after && !(count > n && got[n + 1] == "")) return 0
  return 1
}
{
  sub(/\r$/, ""); sub(/[ \t]+$/, "")
  if (count == 0 && $0 == "") next
  got[++count] = $0
  if (count > 18) exit
}
END { exit !(same(ENVIRON["EXPECTED_A"]) || same(ENVIRON["EXPECTED_B"])) }'

# $1 names the file, and its extension picks the comment style; $2 is what to read, if not $1.
has_valid_header() {
  local ext="${1##*.}" a b blank=0
  a="EXPECTED_INFERRIX_$ext"; b="EXPECTED_THINGSBOARD_$ext"
  case "$ext" in sql) blank=1 ;; esac
  EXPECTED_A="${!a}" EXPECTED_B="${!b}" awk -v blank_after="$blank" "$MATCH_AWK" "${2:-$1}"
}

# Prints a file without its licence comment and says, by exit status, whose licence it was: 0 none
# or The Inferrix Authors', 10 The Thingsboard Authors', 11 someone else's — and then prints
# nothing, because swapping a third party's notice for ours is not a formatting fix (Apache-2.0
# section 4(c) requires keeping it).
# The licence is the first comment block, when a Copyright line is in it. Anything else there, such
# as a doc comment or a /// <reference> directive, is printed back. A // or -- block ends where the
# licence text does, so a comment written straight under a malformed licence is kept too.
STRIP_AWK='
function blank(s) { return s ~ /^[ \t\r]*$/ }
{ line[++n] = $0 }
END {
  i = 1
  while (i <= n && blank(line[i])) i++
  last = 0
  if (i <= n && line[i] ~ /^[ \t]*(<!--|\/\*)/) {
    closer = (line[i] ~ /^[ \t]*<!--/) ? "-->" : "\\*/"
    for (j = i; j <= n; j++) if (line[j] ~ closer) { last = j; break }
  } else if (i <= n && line[i] ~ /^[ \t]*(\/\/|--)/) {
    slash = (line[i] ~ /^[ \t]*\/\//)
    marker = slash ? "^[ \t]*//" : "^[ \t]*--"
    bare = slash ? "^[ \t]*///?[ \t\r]*$" : "^[ \t]*--[ \t\r]*$"
    for (j = i; j <= n && line[j] ~ marker; j++) {
      # A // or -- block has no closing marker, so it ends where the licence stops talking: the
      # next commented line that is neither bare nor licence text is someone\047s own comment. The
      # two SPDX lines are taken in either order, which a header that swapped them still needs.
      if (line[j] !~ bare && line[j] !~ /SPDX-|[Cc]opyright|[Ll]icen[cs]e|WARRANTIES|apache\.org/) break
      last = j
    }
  }
  licence = 0; status = 0
  for (j = i; j <= last; j++) {
    if (line[j] !~ /[Cc]opyright/) continue
    licence = 1
    who = tolower(line[j])
    if (who ~ /the thingsboard authors/) status = 10
    else if (who !~ /the inferrix authors/) exit 11
  }
  if (licence) {
    i = last + 1
    while (i <= n && blank(line[i])) i++
  }
  for (; i <= n; i++) print line[i]
  exit status
}'

TMP="$(mktemp -d)" || exit 1
trap 'rm -rf "$TMP"' EXIT

# A read loop rather than mapfile: macOS ships bash 3.2 as /bin/bash and mapfile is bash 4+.
if [[ "$MODE" == "--all" ]]; then
  list=$(git ls-files)
else
  list=$(git diff --cached --name-only --diff-filter=ACM)
fi

bad=""
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  is_checked "$f" || continue
  if [[ "$MODE" == "--all" ]]; then
    [[ -f "$f" ]] || continue
    has_valid_header "$f"
  else
    # The staged copy, not the worktree's: a header fixed on disk but never re-added would pass
    # here and still be committed broken.
    git cat-file blob ":$f" > "$TMP/blob" && has_valid_header "$f" "$TMP/blob"
  fi || bad="${bad}${f}"$'\n'
done <<< "$list"

if [[ -z "$bad" ]]; then
  exit 0
fi

if [[ "$MODE" == "--fix" ]]; then
  failed=0
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    awk "$STRIP_AWK" "$f" > "$TMP/body"
    case $? in
      0)  owner="$INFERRIX" ;;
      10) owner="$THINGSBOARD" ;;
      11) printf '[license-header] left %s alone: its first comment is a copyright notice that is not ours\n' "$f" >&2
          failed=1; continue ;;
      *)  printf '[license-header] could not read %s\n' "$f" >&2
          failed=1; continue ;;
    esac
    { header_for "$f" "$owner"
      case "$f" in *.sql) printf '\n' ;; esac
      cat "$TMP/body"; } > "$TMP/fixed"
    # Written through rather than moved over, which would give the file the temp file's mode.
    cat "$TMP/fixed" > "$f"
    git add -- "$f"
    if has_valid_header "$f"; then
      printf '[license-header] fixed %s\n' "$f"
    else
      printf '[license-header] rewrote %s and it still fails; the renderer here is wrong\n' "$f" >&2
      failed=1
    fi
  done <<< "$bad"
  exit "$failed"
fi

count=$(printf '%s' "$bad" | grep -c '')
printf '\n[license-header] %s file(s) would fail `mvn license:check`: the header is missing, or not in the exact form it accepts:\n\n' "$count" >&2
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  printf '  %s\n' "$f" >&2
done <<< "$bad"
printf '\nFix them with:\n  bash .claude/hooks/check-license-headers.sh --fix\n\n' >&2
exit 1
