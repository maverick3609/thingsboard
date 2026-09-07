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

year=$(date +%Y)
header="/**
 * Copyright © 2016-${year} The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the \"License\");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an \"AS IS\" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"

tmp=$(mktemp)
{
  printf '%s\n' "$header"
  cat "$file_path"
} > "$tmp"
mv "$tmp" "$file_path"

printf '[license-header] prepended Apache 2.0 header to %s\n' "$file_path" >&2
