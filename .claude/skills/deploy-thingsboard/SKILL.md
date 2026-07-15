---
name: deploy-thingsboard
description: Build the ThingsBoard boot jar (clean install) and deploy it to a remote server over SSH — upload, checksum-verify, back up the running jar, swap it in, apply the Inferrix schema overlay, restart, and health-check. Destructive with downtime; user-invoked only. Runs on `/deploy-thingsboard`.
disable-model-invocation: true
---

# deploy-thingsboard

Builds `application/target/thingsboard-<ver>-boot.jar` and cuts it over on a remote ThingsBoard server. Default target is `192.168.221.77` (user `inferrix`); accept another `user@host` as an argument. **This stops and restarts a live service — treat every run as a production change and confirm before Stage 2.**

## Credentials — never commit, never write to a file
The SSH **and** sudo password come from the operator at run time (ask, or read `$TB_DEPLOY_PASS`). It is passed to the bundled `expect` helpers **as an argument** and appears only transiently in process args — never in this skill, a temp file, git, or a remote file. macOS has no `sshpass`; the helpers (`ssh-run.exp`, `ssh-scp.exp`) drive password auth with the built-in `expect`.

## Prerequisites
- Build toolchain (this machine): `JAVA_HOME=/Users/maverick/Library/Java/JavaVirtualMachines/azul-17.0.19/Contents/Home`; IntelliJ Maven `"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"`; `PATH` prepend `/opt/homebrew/opt/node@22/bin` (frontend build needs Node 22).
- `/usr/bin/expect` (ships with macOS); network reachability to `<host>:22`.

## When to use
- The user asks to build + deploy/upload the TB jar to a server.
- `/deploy-thingsboard [user@host]`.

## Don't use for
- A local-only build (just run the mvn line).
- A **schema version jump** (e.g. DB at 4.2.x, jar at 4.3.x). This skill applies only the idempotent Inferrix overlay; a real upgrade needs the TB `install/upgrade.sh` path, which is gated by `SUPPORTED_VERSIONS_FOR_UPGRADE={4.2.1,4.2.2}`. Check `SELECT schema_version FROM tb_schema_settings` first — if it already equals the jar's version, the overlay is the correct and only DB step.

## Safety model
1. **Confirm before Stage 2** — it stops the live service (a few minutes' downtime).
2. Always **back up the running jar** and **checksum-verify the upload** before swapping.
3. Keep the backup path for **rollback**.

## Variables (set once)
```bash
H=192.168.221.77 ; U=inferrix ; PASS='<operator-provided>'   # PASS never committed
D=.claude/skills/deploy-thingsboard
```

## Build
```bash
export JAVA_HOME=/Users/maverick/Library/Java/JavaVirtualMachines/azul-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/node@22/bin:$PATH"
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
"$MVN" clean install -DskipTests            # full reactor incl. ui-ngx frontend; ~ tens of minutes
JAR=$(ls application/target/thingsboard-*-boot.jar) ; N=$(basename "$JAR")
```

## Stage 1 — upload + audit + backup (safe, no downtime)
```bash
expect -f $D/ssh-scp.exp $U $H "$PASS" "$JAR" "/home/$U/$N"
expect -f $D/ssh-scp.exp $U $H "$PASS" dao/src/main/resources/sql/schema-inferrix.sql "/home/$U/schema-inferrix.sql"

# remote md5 must equal `md5 -q "$JAR"`; back up running jar; audit DB
expect -f $D/ssh-run.exp $U $H "$PASS" '
  echo "'"$PASS"'" | sudo -S -v >/dev/null 2>&1 || { echo SUDO_FAIL; exit 7; }
  md5sum /home/'$U'/'$N'
  echo "'"$PASS"'" | sudo -S cp -a /usr/share/thingsboard/bin/thingsboard.jar /usr/share/thingsboard/bin/thingsboard.jar.bak-preswap
  echo "'"$PASS"'" | sudo -S -u postgres psql -d thingsboard -tAc "SELECT schema_version FROM tb_schema_settings;"
  echo "'"$PASS"'" | sudo -S -u postgres psql -d thingsboard -tAc "SELECT to_regclass('"'"'public.scheduler_event'"'"');"
'
```
Verify: remote md5 matches local; backup listed; note the schema version and whether `scheduler_event` already exists.

## Stage 2 — cutover (DOWNTIME — confirm first)
Give **each** sudo its own password; `psql` reads the overlay via `-f` (never stdin) so the password pipe and SQL never collide, and nothing depends on sudo's cache:
```bash
expect -f $D/ssh-run.exp $U $H "$PASS" '
  P="'"$PASS"'"
  echo "$P" | sudo -S systemctl stop thingsboard ; sleep 3
  echo "$P" | sudo -S install -o thingsboard -g thingsboard -m 500 /home/'$U'/'$N' /usr/share/thingsboard/bin/thingsboard.jar
  echo "$P" | sudo -S md5sum /usr/share/thingsboard/bin/thingsboard.jar
  echo "$P" | sudo -S cp /home/'$U'/schema-inferrix.sql /tmp/ovl.sql
  echo "$P" | sudo -S chmod 644 /tmp/ovl.sql
  echo "$P" | sudo -S -u postgres psql -d thingsboard -v ON_ERROR_STOP=1 -f /tmp/ovl.sql
  echo "$P" | sudo -S -u postgres psql -d thingsboard -tAc "SELECT to_regclass('"'"'public.scheduler_event'"'"');"
  echo "$P" | sudo -S rm -f /tmp/ovl.sql
  echo "$P" | sudo -S systemctl start thingsboard
'
```
Confirm: installed md5 matches; the `to_regclass` line prints `scheduler_event` (not empty).

## Stage 3 — health check
```bash
expect -f $D/ssh-run.exp $U $H "$PASS" '
  for i in $(seq 1 20); do c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 4 http://localhost:8080/login); echo "http=$c"; [ "$c" = 200 ] && break; sleep 5; done
  echo "'"$PASS"'" | sudo -S grep -aE "Started ThingsboardServerApplication|relation .* does not exist|FATAL" /var/log/thingsboard/thingsboard.log | tail -5
'
```
Green = HTTP **200** + a fresh `Started ThingsboardServerApplication` line + **no** `relation ... does not exist` / `FATAL`.

## Rollback (if Stage 3 fails)
```bash
expect -f $D/ssh-run.exp $U $H "$PASS" '
  P="'"$PASS"'"
  echo "$P" | sudo -S systemctl stop thingsboard
  echo "$P" | sudo -S install -o thingsboard -g thingsboard -m 500 /usr/share/thingsboard/bin/thingsboard.jar.bak-preswap /usr/share/thingsboard/bin/thingsboard.jar
  echo "$P" | sudo -S systemctl start thingsboard
'
```
(The overlay is idempotent, so a rolled-back older jar coexists with the already-created `scheduler_event`.)

## Pitfalls (learned in production)
- **Per-command `sudo -S`.** The sudo credential cache does NOT survive across commands over a non-TTY ssh session. Piping the password once (`sudo -v`) then using bare `sudo` later fails mid-run — it once aborted a cutover right before the restart, leaving the service **down**. Pipe `$P` to every `sudo`.
- **Never gate the restart on a `sudo` verify that can itself fail auth.** A false-negative check must not leave the service stopped. If a post-swap verify errors, still start the service, then diagnose.
- **`psql` input vs sudo password.** `sudo -S` consumes stdin for the password; `psql < file` also wants stdin. Use `psql -f <file>` (copied to a `644` `/tmp` path readable by the `postgres` user) so they never collide.
- **Checksum every upload** before swapping; a stalled/short scp is silent otherwise.
- **`expect -f <script>`**, not `./script` — avoids the executable-bit dependency (a missing `chmod +x` silently fails the transfer).

## Quick reference
| Stage | Action | Downtime |
|-------|--------|----------|
| Build | `mvn clean install -DskipTests` → boot jar | no |
| 1 | scp jar+overlay, md5 verify, backup jar, DB audit | no |
| 2 | stop → swap (owner `thingsboard`, mode 500) → overlay `-f` → verify → start | **yes** |
| 3 | HTTP 200 + boot marker + no `does not exist`/`FATAL` | no |
| RB | swap back `*.bak-preswap`, restart | yes |
