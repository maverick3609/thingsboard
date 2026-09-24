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
- [ ] `b5ea073a0f` fix(gateway): switch a schedule the same way its list does
- [x] `68a03db4fb` feat(gateway): let an operator move a gateway to a new address
- [ ] `9450fc6ae1` fix(gateway): let the broker address be repaired from Cortex
- [ ] `254a310113` feat(gateway): provision a gateway's data sources onto the platform
- [ ] `4d0a10e54e` fix(gateway): make the provisioning tab readable and its dialog name the device
