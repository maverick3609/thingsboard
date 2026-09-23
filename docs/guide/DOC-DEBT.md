# Documentation debt

Commits that changed a feature the guide describes without changing the guide.
Appended by `docs/guide/docs-sync.sh --audit`. Tick an item off once
`INFERRIX.md` covers it, or delete it if there was nothing to say.

- [x] `cf3f553754` feat(controllers): page the config sections and pick ids instead of typing them
- [x] `509612a38e` feat(gateways): configure an Inferrix gateway from Cortex (G1-G6)
- [x] `e21455f4a8` fix(gateway): read the version the gateway actually reports
- [ ] `36db410f6a` fix(controllers): stop the Draft/Active toggle being truncated
- [ ] `ed5240a62a` fix(gateway): lay the schema forms out like the gateway's own editor
- [ ] `e59ab8f82e` fix(gateway): make the detector list load at all
- [ ] `af724e12d0` fix(gateway): stop the forms nagging before they are touched
- [ ] `ccb8071b18` feat(gateway): name a detector's type the way the gateway names it
