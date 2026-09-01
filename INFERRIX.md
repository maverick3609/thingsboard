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

#### Recovering from exit code 18

Exit 18 does not always mean *this* node's clock is wrong. `checkClock` compares the current clock
against `high_water_ts`, a mark in `inferrix_license_state` that only ever moves forward. The usual cause
is a **different** node whose clock once ran ahead of real time: that node still passed `checkExpiry`
(its fast clock hadn't reached the licence's expiry yet) and `checkClock` (nothing had recorded a higher
mark yet), so it persisted its own future timestamp as the new high-water mark. Because the mark cannot
move backward, every node afterwards — including the offending one, once its clock is corrected — now
reads a real "now" behind that stale future mark, and fails `now < highWater - clock_tolerance_ms` for as
long as the gap lasts.

**Confirm it before touching anything.** The exit-18 log line prints both values it compared:

```
System clock moved backwards past the recorded high-water mark: clock reads <now> but the recorded high-water mark is <highWater>
```

Convert both to readable times and check them against a clock you trust (NTP, another host). If
`highWater` is the one in the future, this is the scenario above, not a live rollback attempt — proceed
to the recovery below. If instead `now` is the one that looks wrong, the check is doing its job: fix
*this* node's clock and let it re-verify normally. Do not run the recovery in that case — it would
recreate the same stuck mark for whoever checks in next.

**Blast radius:** `license.max_high_water_advance_ms` (default 24h) caps how far one check can push the
mark ahead, not how many checks can push it. A **transient** bad reading (a one-off NTP jump that
self-corrects, or a node that stops checking in) costs at most one clamp period of lockout, then the
install heals itself. A **persistent** skew (dead RTC battery, wrong timezone) instead walks the mark
forward by one clamp period per check until it reaches that node's own reading, then tracks it exactly —
the lockout then lasts as long as that node keeps running and checking in, not just one clamp period. The
real fix there is correcting or stopping the offending node's clock; the recovery below buys time, but a
still-running bad node will push the mark straight back up, so it may need repeating until the clock is
actually fixed. A WARN in the log (`DefaultLicenseService`, "high-water mark clamped") fires every time a
check gets clamped, so this is visible well before the lockout lands rather than only once it's too late.

**Recovery**, only once the clocks involved are confirmed good:

```sql
-- Only after confirming no clock is actually wound back. Sets the mark to the current true time.
UPDATE inferrix_license_state SET high_water_ts = <current epoch millis> WHERE singleton = TRUE;
```

This is a deliberate override of an anti-tamper control, applied directly against the database, bypassing
the platform entirely. Run it only once the clocks are known good — used on a node whose clock is
genuinely wound back, it is exactly the tampering this mechanism exists to catch, defeated silently by an
operator's own hand.

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
