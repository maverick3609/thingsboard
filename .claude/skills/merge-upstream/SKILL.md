---
name: merge-upstream
description: Use when integrating commits from `upstream/release-4.3` (or another upstream ref) into the Inferrix fork, and to verify the `INFERRIX-PATCHES.md` ledger anchors survived the merge.
disable-model-invocation: true
---

# merge-upstream

Walks the upstream-merge workflow for the Inferrix fork and runs the ledger drift audit afterwards. Default upstream ref is `upstream/release-4.3`; accept a different ref as an argument when the user names one (e.g. `/merge-upstream upstream/master`).

## When to use
- After `git fetch upstream` shows new commits on the tracked branch.
- After cherry-picking a feature branch from upstream that targets the same line as a ledger entry.
- Whenever the user types `/merge-upstream` or asks to "pull in upstream".

Don't use this skill for:
- Forward-porting commits between Inferrix branches (e.g. master → inferrix-release-4.3). Use `git cherry-pick` directly.
- Resolving a merge that's already in progress — pick up at step 5.

## Procedure

### 1. Pre-flight checks
Run these in parallel:
```bash
git status --short
git branch --show-current
git rev-parse --verify INFERRIX-PATCHES.md >/dev/null 2>&1 || ls INFERRIX-PATCHES.md
```

- Working tree must be clean (untracked-only is OK, but surface it). If staged/unstaged changes exist, stop and ask the user whether to stash, commit, or abort.
- Confirm current branch is `inferrix-release-4.3` (or whatever the ledger names as the integration branch). Mismatch → ask before merging.
- `INFERRIX-PATCHES.md` must exist at repo root. Missing → stop; merge survival depends on it.

### 2. Fetch upstream and show scope
```bash
git fetch upstream
git log --oneline HEAD..<upstream-ref> | head -20
git rev-list --count HEAD..<upstream-ref>
```

Report the commit count and a short sample. If 0, exit — nothing to merge.

### 3. Surface known-risky overlap (optional but cheap)
For each file path appearing in `INFERRIX-PATCHES.md` row 2 (the File column), check whether it changed in the incoming range:
```bash
git diff --name-only HEAD..<upstream-ref> | grep -F -f <(grep -oE '`[^`]+\.(java|ts|sql|yml|xml|html|json)`' INFERRIX-PATCHES.md | tr -d '`' | sort -u)
```
Report the intersection. These are the files most likely to either conflict or silently drift. Do not block on this — just surface it.

### 4. Run the merge
```bash
git merge <upstream-ref> --no-edit
```

### 5. Handle conflicts (if any)
- List conflicted files: `git diff --name-only --diff-filter=U`
- Cross-reference each against `INFERRIX-PATCHES.md`. Anchored files take the ledger's "merge-recovery procedure" as guidance; non-anchored files are normal conflicts.
- Recommend `git merge --abort` only if the user wants to restart. Otherwise pause and let the user resolve.

### 6. Run the ledger audit (success path)
Dispatch the `upstream-merge-auditor` subagent. Its job is to walk every TB-core row in every "committed on this branch" feature section and verify the anchor survived. The agent returns a structured punch-list of present / changed / missing.

```
Agent({
  description: "Walk INFERRIX-PATCHES.md ledger and report anchor drift",
  subagent_type: "upstream-merge-auditor",
  prompt: "Audit ledger drift on current branch after merging <upstream-ref>. Walk INFERRIX-PATCHES.md and report per-row status. Skip features whose status line says they are not on this branch."
})
```

Relay the report verbatim — do not summarize away missing/changed entries.

### 7. Recommend build verification
On clean audit, recommend (do not run unless asked):
- `mvn clean install -DskipTests` for any backend module touched in step 3
- `cd ui-ngx && yarn ng build --configuration=inferrix` for frontend overlap

Note the user's preference: Maven builds run from IntelliJ, not CLI.

## Common pitfalls
- **Auto-commit on `--no-edit`**: a clean merge commits immediately. If you wanted to inspect the merge before committing, use `--no-commit --no-ff`.
- **"Zero conflicts" ≠ "zero drift"**: upstream can refactor a file in a way that auto-merges textually but breaks the WL patch semantically. The audit subagent catches this; do not skip step 6.
- **Untracked files**: do not stash them implicitly. Surface and ask.
- **Forward-port confusion**: cherry-picking from master onto this branch is *not* this skill's job. Suggest a separate plan.

## Quick reference

| Step | Command / Action |
|------|-------|
| 1 | `git status`, `git branch --show-current`, ledger exists |
| 2 | `git fetch upstream` + commit count |
| 3 | Diff `<upstream-ref>` against ledger file list |
| 4 | `git merge <ref> --no-edit` |
| 5 | List conflicts, cross-ref ledger, pause |
| 6 | Dispatch `upstream-merge-auditor` |
| 7 | Suggest build verification |
