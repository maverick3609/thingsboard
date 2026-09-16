#!/usr/bin/env bash
# PostToolUse hook: prepend Apache 2.0 license header to newly-written *.java files
# so `mvn license:check` doesn't break the build.
#
# Owner is "The Inferrix Authors", matching the root pom's <owner>. A file created here is
# Inferrix-owned by the new-tb-core-patch skill's own Step 0 rule; the Thingsboard template stays
# accepted via <validHeaders>, so existing upstream files are unaffected.
#
# BLIND SPOT: this only fires on the Write tool, so a file created with a `cat > f <<EOF` heredoc
# through Bash is never seen — that is how controllers.component.scss reached a commit unheaded.
# It also only covers *.java. The catch-all is the pre-commit hook, check-license-headers.sh,
# which works on staged content and so does not care how a file was authored.
#
# Excludes:
#   - target/, node_modules/ (build output)
#   - files that already start with /** (header present)
#   - files outside a repository with the licence templates (no license:check there to satisfy)
#
# Hook receives the tool call JSON on stdin; we extract tool_input.file_path.

set -euo pipefail

input=$(cat)
file_path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // ""')

[[ -z "$file_path" ]] && exit 0
[[ "$file_path" != *.java ]] && exit 0
[[ "$file_path" == */target/* ]] && exit 0
[[ "$file_path" == */node_modules/* ]] && exit 0
[[ ! -f "$file_path" ]] && exit 0

first_line=$(head -n 1 "$file_path")
if [[ "$first_line" == /\*\** ]]; then
  exit 0
fi

# Rendered by the pre-commit check from the templates in the file's own repository, so the two hooks
# cannot disagree. The copy this hook kept took its end year from `date`: from 2027-01-01 it would
# have stamped 2016-2027, which license:check refuses, because the range ends where the template
# was last bumped, not at the current year.
hooks_dir=$(cd "$(dirname "$0")" && pwd)
header=$(cd "$(dirname "$file_path")" && bash "$hooks_dir/check-license-headers.sh" --header "$file_path" 2>/dev/null) || exit 0
[[ -n "$header" ]] || exit 0

tmp=$(mktemp)
{
  printf '%s\n\n' "$header"
  cat "$file_path"
} > "$tmp"
# Written through rather than moved over, which would leave the file with mktemp's 0600 mode.
cat "$tmp" > "$file_path"
rm -f "$tmp"

printf '[license-header] prepended Apache 2.0 header to %s\n' "$file_path" >&2
