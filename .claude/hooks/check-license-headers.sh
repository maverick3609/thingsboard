#!/usr/bin/env bash
# Refuses a commit whose source files would fail `mvn license:check`.
#
# That goal runs only in a full reactor build, minutes in and typically only when someone is about
# to deploy — and when it fails at the ui-ngx module, maven has not yet cleaned application/target,
# so the PREVIOUS boot jar is still sitting there looking like a successful build. This has cost a
# build cycle three times on this fork (7b95cf84f8, 94a1ecbe8e, 10e2c3a9df), every one of them a
# .scss file.
#
# Two distinct failure modes, both checked:
#   1. no header at all;
#   2. the right header text in the WRONG comment style — .scss is JAVADOC_STYLE per the root pom's
#      <mapping>, but a .ts habit produces ///, which is what bit 7b95cf84f8. Grepping for
#      "Copyright" alone would pass that file, so the opener is checked too.
#
# Owner: the root pom sets <owner>The Inferrix Authors</owner> as the default and lists the
# Thingsboard template under <validHeaders>, so both are accepted here.
#
# Usage:
#   check-license-headers.sh            check staged files (pre-commit); exit 1 if any fail
#   check-license-headers.sh --fix      prepend the correct header, in the right style, then stage
#   check-license-headers.sh --all      check the whole worktree (pre-flight before a deploy build)

set -uo pipefail

MODE="${1:-staged}"
YEAR_TO=2026
OWNER="The Inferrix Authors"

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

# Mirrors the root pom's <mapping> plus the plugin defaults; verified against the files in tree.
opener_for() {
  # printf '%s' throughout: a bare printf '--' is read as an end-of-options marker and errors.
  case "$1" in
    *.java|*.scss) printf '%s' '/**' ;;
    *.ts)          printf '%s' '///' ;;
    *.html)        printf '%s' '<!--' ;;
    *.sql)         printf '%s' '--' ;;
  esac
}

header_for() {
  local body="Copyright © 2016-${YEAR_TO} ${OWNER}

Licensed under the Apache License, Version 2.0 (the \"License\");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an \"AS IS\" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License."
  case "$1" in
    *.java|*.scss)
      printf '/**\n'; printf '%s\n' "$body" | sed 's|^| * |; s| *$||'; printf ' */\n' ;;
    *.ts)
      printf '///\n'; printf '%s\n' "$body" | sed 's|^|/// |; s| *$||'; printf '///\n' ;;
    *.html)
      printf '<!--\n\n'; printf '%s\n' "$body" | sed 's|^|    |; s| *$||'; printf '\n-->\n' ;;
    *.sql)
      printf '%s\n' "$body" | sed 's|^|-- |; s| *$||' ;;
  esac
}

# A file passes when it opens with the style its extension requires AND carries an accepted
# copyright line near the top.
has_valid_header() {
  local f="$1" opener
  opener="$(opener_for "$f")"
  head -n 1 "$f" | grep -qF -- "$opener" || return 1
  head -n 20 "$f" | grep -qE 'Copyright ©.*(The Inferrix Authors|The Thingsboard Authors)' || return 1
  return 0
}

# A read loop rather than mapfile: macOS ships bash 3.2 as /bin/bash and mapfile is bash 4+.
if [[ "$MODE" == "--all" ]]; then
  list=$(git ls-files)
else
  list=$(git diff --cached --name-only --diff-filter=ACM)
fi

bad=""
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  [[ -f "$f" ]] || continue
  is_checked "$f" || continue
  has_valid_header "$f" || bad="${bad}${f}"$'\n'
done <<< "$list"

if [[ -z "$bad" ]]; then
  exit 0
fi

if [[ "$MODE" == "--fix" ]]; then
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    tmp=$(mktemp)
    { header_for "$f"; printf '\n'; cat "$f"; } > "$tmp"
    mv "$tmp" "$f"
    git add "$f" 2>/dev/null || true
    printf '[license-header] fixed %s\n' "$f"
  done <<< "$bad"
  exit 0
fi

count=$(printf '%s' "$bad" | grep -c '' )
printf '\n[license-header] %s file(s) would fail `mvn license:check`:\n\n' "$count" >&2
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  printf '  %s   (needs a %s header)\n' "$f" "$(opener_for "$f")" >&2
done <<< "$bad"
printf '\nFix them with:\n  bash .claude/hooks/check-license-headers.sh --fix\n\n' >&2
exit 1
