## Project Overview

ThingsBoard is an open-source IoT platform (v4.3.1.1) for device management, data collection, processing, and visualization. It's a multi-module Maven project with a Java 17/Spring Boot 3.5 backend and Angular 20 frontend.

## Context Navigation (Graphify)

### 3-Layer Query Rule
1. **First:** query `graphify-out/graph.json` or `graphify-out/wiki/index.md`
   to understand code structure and connections
2. **Second:** query the Obsidian vault for decisions, progress, and project context
3. **Third:** only read raw code files when editing
   or when the first two layers don't have the answer

### When to rebuild the graph
- After structural changes (new modules, major refactors)
- Command: `graphify update .` (only processes modified files)
- The graph is persistent — NO need to rebuild every session

### Do NOT
- Don't manually modify files inside `graphify-out/`
- Don't re-read the entire codebase if the graph already has the information

## Obsidian Vault

- **Root:** `/Users/maverick/Office/Product/vault/`
- **Project subtree:** `vault/thingsboard/` — `architecture/` (decisions), `features/`, `logs/` (session logs), `pipeline/`, `data/`
- **Conventions:** see `vault/CLAUDE.md` — Zettelkasten, wikilinks `[[note]]`, YAML frontmatter, kebab-case filenames
- **Graphify auto-exports to** `vault/graphify/thingsboard/` — read-only, do not edit manually
- **Session commands** (`/resume`, `/save`) are defined in `vault/CLAUDE.md`