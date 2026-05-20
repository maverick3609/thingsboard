#!/usr/bin/env bash
# PostToolUse hook: prepend Apache 2.0 license header to newly-written *.java files
# so `mvn license:check` doesn't break the build.
#
# Excludes:
#   - inferrix-reporting/   (Inferrix-owned, license excluded in root pom.xml)
#   - target/, node_modules/ (build output)
#   - files that already start with /** (header present)
#
# Hook receives the tool call JSON on stdin; we extract tool_input.file_path.

set -euo pipefail

input=$(cat)
file_path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // ""')

[[ -z "$file_path" ]] && exit 0
[[ "$file_path" != *.java ]] && exit 0
[[ "$file_path" == */inferrix-reporting/* ]] && exit 0
[[ "$file_path" == */target/* ]] && exit 0
[[ "$file_path" == */node_modules/* ]] && exit 0
[[ ! -f "$file_path" ]] && exit 0

first_line=$(head -n 1 "$file_path")
if [[ "$first_line" == /\*\** ]]; then
  exit 0
fi

year=$(date +%Y)
header="/**
 * Copyright © 2016-${year} The Thingsboard Authors
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
