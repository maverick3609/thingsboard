# Inferrix — feature documentation

Narrative documentation for Inferrix-specific features on this fork. For the TB-core merge-survival
ledger (which upstream files were changed, and how to recover a patch after `git merge
upstream/release-4.3`), see `INFERRIX-PATCHES.md` instead.

This file is the feature documentation for `inferrix-release-4.3`. `master` separately carries an
unrelated, older `INFERRIX.md` (commit `f2e6ce9aa9`) documenting the superseded "Inferrix Synapse"
`src/inferrix/` branding overlay — the two branches are divergent, these are not versions of the same
document, and they should not be merged or reconciled as if they were.

## License Control

Offline, install-wide licence enforcement. A signed key caps the number of devices and assets a
deployment may create and expires on a schedule; there is no per-tenant licensing and no phone-home.

### The key

Inferrix issues each deployment an Ed25519-signed licence key: a customer name, an expiry date, and a
device/asset cap, bound to that one deployment's instance UUID (see below) so a key cannot be copied to a
second install. The key is a single string.

It goes in `thingsboard.conf` as an environment variable:

```
export INFERRIX_LICENSE_KEY="..."
```

`thingsboard.yml` reads it via `license.key: "${INFERRIX_LICENSE_KEY:}"`. Two more tunables live in the
same `license:` block: `license.enforcement.enabled` (the on/off switch — must stay `true`, i.e. absent
or explicit `true`, in every real deployment; see `INFERRIX-PATCHES.md`'s License Control section for
why a `false` default would be a critical misconfiguration) and `license.check_interval_ms`/
`license.clock_tolerance_ms` (how often the key is re-verified at runtime, and how far the system clock
is allowed to move backwards before that counts as tampering).

### First boot

On its first boot, the platform generates a random instance UUID and stores it in a single-row table
(`inferrix_license_state`) — this is the deployment's permanent identity for licensing purposes.

If `INFERRIX_LICENSE_KEY` is not set, the platform logs that instance UUID and exits (see exit codes
below). The operator sends that UUID to Inferrix, receives a key issued specifically for it, sets
`INFERRIX_LICENSE_KEY`, and restarts. From then on the platform re-verifies the key at boot and on a
timer (`license.check_interval_ms`, default 1 hour) for as long as the process runs.

### Exit codes

A licence problem is treated as fatal: the platform logs the reason and terminates. Each failure mode
has its own exit code so the cause is visible without reading logs (e.g. from a process supervisor or an
orchestrator's restart history):

| Code | Meaning |
|------|---------|
| 13 | No licence key is configured |
| 14 | The licence key is malformed (not a valid key at all) |
| 15 | The licence key's signature does not verify (payload was altered, or it's not a genuine Inferrix key) |
| 16 | The key was issued for a different deployment's instance UUID |
| 17 | The licence has expired |
| 18 | The system clock moved backwards past the point the platform last recorded — beyond the configured tolerance, which is treated as an attempt to defeat the expiry check |

Reaching a device/asset cap does **not** exit the process — it only blocks further creates (existing
devices/assets keep operating normally); only the six conditions above terminate the platform.

The systemd unit template sets `RestartPreventExitStatus=13 14 15 16 17 18` so a licence exit stops the
service instead of restarting it. Docker Compose's `restart:` directive has no equivalent exit-code
predicate, so under `restart: always` a licence exit **will** loop the container every restart delay
instead of stopping. If a container is exiting on a licence code, stop it (`docker compose stop`) rather
than waiting on it, and fix the licence before starting it again.

### No internet access required

Verification is entirely offline: the public key used to verify signatures is compiled into the
platform, and the only persisted state is the single `inferrix_license_state` row. The platform never
calls out to Inferrix or anywhere else to check a licence.

### Install and upgrade are unlicensed

The installer and the upgrade tool run under Spring's `install` profile, which selects a separate
no-op licence bean that never checks a key and never exits. Licence enforcement only activates once the
platform starts in its normal (non-install) profile. This means a fresh install or a version upgrade
always completes regardless of whether a key is configured yet — the key is only needed to start serving
traffic afterward.

### Issuing keys

Keys are produced by a separate tool, not by this repository: `/Users/maverick/Office/Product/inferrix-license-keygen/`.

The generator's private signing key lives **only in the password manager** — it is not checked into
either repository. Both repositories share a test-only "golden vector" fixture (a throwaway keypair used
purely to pin the key format in tests); that fixture's private key is likewise confined to the generator
repo's copy and is deliberately absent from this repo's copy.
