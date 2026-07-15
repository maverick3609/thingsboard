---
name: new-tb-core-patch
description: Use immediately after modifying a ThingsBoard-core (upstream-owned) file on the Inferrix fork — any file NOT under an Inferrix-owned additive path — to record it as an INFERRIX-PATCHES.md ledger row plus a merge-recovery bullet so the change survives upstream merges. Also runs on `/new-tb-core-patch`.
---

# new-tb-core-patch

Records a change to an upstream (ThingsBoard-core) file in `INFERRIX-PATCHES.md` so it is not silently lost the next time `upstream/*` is merged. The `upstream-merge-auditor` only catches drift *after* a merge — this skill closes the loop at the moment the edit is made, so there is a row for the auditor to check.

## When to use
- Right after you edit a file that ships from upstream ThingsBoard (a file you did **not** create) — a `case` arm, an enum constant, a `put(...)`, an annotation, a `.yml` key, a proto field, etc.
- When the user types `/new-tb-core-patch`.

## Don't use for
- **Inferrix-owned additive files** — anything under a path listed as a "New additive path" in the ledger (e.g. `.../scheduler/*`, `SchedulerEventController.java`, `ui-ngx/.../pages/scheduler/*`, `inferrix-reporting/**`). Those are 100% ours; upstream never touches them, so they need no ledger row. Adding them just bloats the ledger.
- **New DDL.** Do not add a table/column/index by editing `schema-entities.sql` or a `schema_update.sql`. Append it (idempotently) to the Inferrix schema overlay `dao/src/main/resources/sql/schema-inferrix.sql` instead — that needs no new ledger row at all (the overlay seams O1–O4 are already recorded). See the ledger's "§Inferrix schema overlay". The `check-overlay-idempotent.sh` hook enforces idempotency there.

## Step 0 — is this file TB-core or Inferrix-owned?
The gate for everything below. **TB-core** = a file that ships from ThingsBoard and you *edited*; **Inferrix-owned** = a file you *created*. You usually already know which — confirm mechanically only when unsure.

Authoritative check (works for **any** file type, including `.json`/`.yml`). It needs the upstream integration ref fetched — `upstream/release-4.3` by default (see the `merge-upstream` skill); run `git fetch upstream` first if it is stale:
```bash
git cat-file -e "upstream/release-4.3:<path>" 2>/dev/null \
  && echo "TB-core → ledger row needed" \
  || echo "Inferrix-owned (not on upstream) → no row"
```

Quick aid for source files that carry a license header (`.java`/`.ts`/`.scss`/`.sql`/`.html`) — the author line already encodes it (not present in `.json`):
```bash
grep -m1 'Copyright ' <path>   # "The Thingsboard Authors" = TB-core · "The Inferrix Authors" = ours
```

If Inferrix-owned → stop, no ledger row. If TB-core → continue.

## Procedure

### 1. Find or create the feature section
Open `INFERRIX-PATCHES.md`. Each feature is a `## Feature: <name>` section with a "TB-core files modified" table and a "Merge-recovery procedure" list. Put the row in the section for the feature you are working on; if it is a brand-new feature, add a new section following the same shape (see the ledger's "How to extend this ledger" at the bottom).

### 2. Assign the next row id
Use the next free id in that section's series (e.g. `S24` after `S23`). If the change is genuinely part of a distinct mechanism, start a new short series (the overlay uses `O1..O4`). Ids are just labels — keep them unique within the file.

### 3. Add the table row
`| <id> | \`<path>\` | <precise anchor> — <what changed> | <why> |`

The **anchor** must let a future reader re-apply the change by hand without a diff:
- name the method / enum / map / switch and the **insertion point** ("after the `API_KEY` case", "appended to the `features` array", "~line 230"),
- describe the **intent**, not the literal patch (diffs rot against upstream churn),
- if the change deviated from an obvious approach, say why (the ledger's S14 is a good model).

### 4. Add the merge-recovery bullet
In that section's "Merge-recovery procedure" list, add a bullet naming the anchor and — critically — classify how a lost patch would show up, because that dictates how hard you must look after a merge:

| Class | Examples | Symptom if dropped | Recovery note to write |
|-------|----------|--------------------|------------------------|
| **Compiler-enforced** | exhaustive `switch` arm, `@Override` signature, proto field-number uniqueness, explicit-constructor param type | build fails loudly | "a dropped arm fails the build — unmissable" |
| **Silent-drop** | annotation (`@DiscriminatorMapping`), map/enum registration (`Resource`, permissions maps, `entityTypeTranslations`), `.yml` key, non-exhaustive `switch` with `default`, SQL content, TS export | no build error; wrong behavior at runtime | "not compiler-enforced — check by hand; grep for `<token>`" |

When unsure, treat it as **silent-drop** and give a concrete grep to re-verify.

### 5. Verify
```bash
git diff INFERRIX-PATCHES.md          # row + recovery bullet present, nothing else churned
grep -n "<id>" INFERRIX-PATCHES.md    # id is unique
```

## Common pitfalls
- **Recording an additive file.** If you created it, it is not a patch — Step 0 catches this.
- **Pasting a diff as the anchor.** Upstream reformats; a line-diff anchor is dead on arrival. Describe intent + insertion point.
- **Forgetting the recovery bullet.** The table row documents *what*; the bullet documents *how to notice it vanished*. The auditor relies on the bullet's compiler-enforced/silent classification.
- **DDL via upstream schema files.** Route it through the overlay (see "Don't use for").

## Quick reference

| Step | Action |
|------|--------|
| 0 | `git cat-file -e upstream/<ref>:<path>` → TB-core or ours? |
| 1 | Locate/create the `## Feature:` section |
| 2 | Next free row id (`S24`, or a new series) |
| 3 | Table row: `\`path\`` + precise anchor + intent + why |
| 4 | Recovery bullet: compiler-enforced vs silent-drop + grep token |
| 5 | `git diff` the ledger; confirm id unique |
