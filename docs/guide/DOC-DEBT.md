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
- [ ] `392d2ccfd5` feat(inferrix): page the two dialog tables that could outgrow their dialog
- [ ] `bc80c34043` feat(gateway): configure points inside their parent, and add publishers
- [ ] `0a20f55230` feat(gateway): let the broker address be changed from Cortex
- [ ] `5da7be3e66` feat(controller): choose every enumerated config value, and say why Apply failed
- [ ] `0c07436a86` feat(controller): pick a point's channel, and name what QoS means
- [ ] `da58696668` feat(gateway): open a data source form on what the operator came for
- [ ] `d732e61eea` feat(gateway): show a virtual point the five fields it actually has
- [ ] `9aadbf192f` feat(gateway): stop offering to edit what a mesh node reports
- [ ] `0c577a9cff` feat(gateway): let a protocol bring a component, not just a layout
- [ ] `f0063b3bb9` feat(gateway): lay out a Modbus/IP source and its point the way the protocol works
- [ ] `8241c60f97` feat(gateway): give a Modbus serial line the settings it actually has
- [ ] `90631c3e09` feat(gateway): let a BACnet point say which object it reads, and how
- [ ] `03403f4d9a` feat(gateway): give BACnet MS/TP the BACnet form, and the locator type the gateway withholds
- [ ] `c2c8ed9744` feat(gateway): lay out an SNMP source around its version, and stop an add from becoming an update
- [ ] `ca21b1010c` feat(gateway): give a meta point a script box and a point picker
- [ ] `233eefa2e3` fix(gateway): stop an edit from erasing a credential the gateway will not show
- [ ] `33264c14a0` feat(gateway): lay out an MQTT source and its point, and refuse what the broker cannot parse
- [ ] `d7791ea3a8` feat(gateway): lay out an HTTP receiver around the two lists that are its security
- [ ] `f0262aef6f` fix(gateway): hand out a copy of a layout's defaults, not the table's own values
- [ ] `0681b77ab3` feat(gateway): lay out a JSON retriever, and seed the poll period every polling type needs
- [ ] `bc6bb134e1` fix(gateway): leave the bearer token clearable, because the gateway hands it back
- [ ] `408bc7503b` feat(gateway): let a layout carry a hint, since three types now describe nothing
- [ ] `7720f433ac` fix(gateway): stop offering a data type three locators cannot produce, and give a number a floor
- [ ] `edf4872a7c` feat(gateway): lay out the internal monitoring source, whose one useful field the gateway will not name
