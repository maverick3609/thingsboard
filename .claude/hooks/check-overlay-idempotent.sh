#!/usr/bin/env bash
# PostToolUse hook: guard the Inferrix schema overlay + upgrade SQL against
# NON-IDEMPOTENT DDL.
#
# Why: `dao/.../sql/schema-inferrix.sql` and every `upgrade/**/schema_update.sql`
# are re-applied on EVERY database upgrade run (see INFERRIX-PATCHES.md
# "§Inferrix schema overlay"). A statement that is not idempotent — e.g.
# `CREATE TABLE` without `IF NOT EXISTS` — succeeds on the first install and then
# FAILS the second upgrade with "relation already exists", silently aborting the
# rest of the migration. This hook catches that at edit time.
#
# It only inspects those two file kinds; every other file exits 0 immediately.
# On a violation it exits 2 so Claude Code feeds the message back to the agent.
#
# Hook receives the tool call JSON on stdin; we extract tool_input.file_path.

set -euo pipefail

input=$(cat)
file_path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // ""')

[[ -z "$file_path" ]] && exit 0

# Only the idempotent-required SQL: the Inferrix overlay + any upgrade schema_update.sql.
case "$file_path" in
  *schema-inferrix.sql) ;;
  *upgrade/*schema_update.sql) ;;
  *) exit 0 ;;
esac

[[ ! -f "$file_path" ]] && exit 0

# Scan: strip `--` line comments, split into statements on `;`, and flag any
# statement whose leading DDL keyword has no idempotent guard. Reports to stdout,
# one block per offending statement (empty output == clean).
problems=$(awk '
{
  line = $0
  sub(/--.*/, "", line)                 # drop SQL line comments (header, inline)
  buf = buf " " line
  while ((p = index(buf, ";")) > 0) {
    check(substr(buf, 1, p - 1))
    buf = substr(buf, p + 1)
  }
}
function check(s,   t, disp) {
  t = toupper(s)
  gsub(/[ \t\r\n]+/, " ", t); sub(/^ /, "", t)
  if (t == "") return
  disp = s
  gsub(/[ \t\r\n]+/, " ", disp); sub(/^ /, "", disp)
  disp = substr(disp, 1, 90)

  if (t ~ /^CREATE +TABLE +/ && t !~ /^CREATE +TABLE +IF +NOT +EXISTS/)
    flag(disp, "CREATE TABLE without IF NOT EXISTS")
  else if (t ~ /^CREATE +(UNIQUE +)?INDEX +/ && t !~ /IF +NOT +EXISTS/)
    flag(disp, "CREATE INDEX without IF NOT EXISTS")
  else if (t ~ /^CREATE +SEQUENCE +/ && t !~ /IF +NOT +EXISTS/)
    flag(disp, "CREATE SEQUENCE without IF NOT EXISTS")
  else if (t ~ /^CREATE +TYPE +/)
    flag(disp, "CREATE TYPE has no IF NOT EXISTS in PostgreSQL — wrap in a guarded DO $$ ... $$ block")
  else if (t ~ / ADD +COLUMN +/ && t !~ / ADD +COLUMN +IF +NOT +EXISTS/)
    flag(disp, "ALTER ... ADD COLUMN without IF NOT EXISTS")
  else if (t ~ / ADD +CONSTRAINT +/)
    flag(disp, "ALTER ... ADD CONSTRAINT is not idempotent — DROP CONSTRAINT IF EXISTS first, or use a guarded DO block")
  else if (t ~ /^INSERT +INTO +/ && t !~ /ON +CONFLICT/)
    flag(disp, "INSERT without ON CONFLICT — add ON CONFLICT DO NOTHING/UPDATE")
}
function flag(d, why) { printf "  x %s\n      %s\n", why, d }
' "$file_path")

if [[ -n "$problems" ]]; then
  {
    printf '[overlay-idempotency] Non-idempotent SQL in %s\n' "$file_path"
    printf '  This file re-runs on EVERY upgrade — each statement must be idempotent.\n'
    printf '%s\n' "$problems"
    printf '  Fix with IF NOT EXISTS / ON CONFLICT / guarded DO blocks, then re-save.\n'
  } >&2
  exit 2
fi

exit 0
