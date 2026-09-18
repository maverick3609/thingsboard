#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright The Inferrix Authors
# SPDX-License-Identifier: Apache-2.0
#

# Installs this repository's git hooks. .git/hooks is not tracked, so every clone
# needs this once:  bash docs/guide/install-hooks.sh

set -euo pipefail

root=$(git rev-parse --show-toplevel)
hooks="$root/.git/hooks"

# The licence-header check lives in .claude/, which is gitignored — it is agent
# tooling that happens to also be a useful hook. Wire it in when it is there.
licence=""
if [ -f "$root/.claude/hooks/check-license-headers.sh" ]; then
    licence='bash "$root/.claude/hooks/check-license-headers.sh" || exit 1'
else
    echo "note: .claude/hooks/check-license-headers.sh is absent — licence headers are NOT checked" >&2
    echo "      on commit here. mvn license:check still catches a bad header, at build time." >&2
fi

cat > "$hooks/pre-commit" <<EOF
#!/usr/bin/env bash
# Installed by docs/guide/install-hooks.sh — edit that, not this.
root=\$(git rev-parse --show-toplevel)
$licence
bash "\$root/docs/guide/docs-sync.sh" --stage || exit 1
EOF

cat > "$hooks/post-commit" <<'EOF'
#!/usr/bin/env bash
# Installed by docs/guide/install-hooks.sh — edit that, not this.
root=$(git rev-parse --show-toplevel)
bash "$root/docs/guide/docs-sync.sh" --audit
EOF

chmod +x "$hooks/pre-commit" "$hooks/post-commit"
echo "installed: pre-commit (${licence:+licence headers + }guide render), post-commit (doc-debt audit)"
