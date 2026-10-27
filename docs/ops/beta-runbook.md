# Beta server runbook

For the project owner. Every step is run by you, on your own machines. Do the steps in order. Each step ends with a check and what you should see. If a check fails, stop and fix it before you go on.

This runbook uses the host names `chat.example.org`, `turn1.example.org`, `turn2.example.org` and `push.example.org`. Replace `example.org` with your domain everywhere. Commands run from the `deploy/` directory of the repository unless a step says otherwise. For what each setting means, see the [admin guide](../server/admin-guide.md).

Four machines, three kinds:

| Machine | Runs | Files |
|---|---|---|
| App VM | app server, postgres, Caddy | `deploy/compose.yaml`, `deploy/Caddyfile` |
| Relay VM 1 and 2 | coturn only | `deploy/relay/` |
| Gateway VM | push gateway, postgres, Caddy | `deploy/pushgw/` |

## 1. Machines

1. Rent one VM for the app: 2 vCPU, 4 GB memory, Linux. Check: `ssh <user>@<app ip> 'nproc; free -g | head -2'`. Expect 2 and about 4.
2. Rent two small VMs for relays, in two regions (for example one in Europe, one in the US). Pick a provider with included traffic: calls relay a lot of data. Check: you have two public IPv4 addresses, in two regions.
3. Rent one small VM for the gateway. Check: you have its public IPv4 address.
4. On every VM, install Docker with the compose plugin and clone this repository. Check: `docker compose version` prints a version.

## 2. DNS

1. Create an A record (and an AAAA record if the VM has an IPv6 address) for each name:
   - `chat.example.org` points to the app VM
   - `turn1.example.org` points to relay 1
   - `turn2.example.org` points to relay 2
   - `push.example.org` points to the gateway VM
2. Check each name: `dig +short chat.example.org`. Expect the VM's address. For IPv6, `dig +short AAAA chat.example.org`. Wait and retry if it is empty; DNS can take a few minutes.

## 3. Firewall

Set the firewall at the provider, or with `ufw` on the VM. Open only these ports:

| Machine | Open |
|---|---|
| App VM | TCP 80 and 443 |
| Relay VMs | UDP and TCP 3478, TCP 443, UDP 49152 to 65535, and TCP 80 for certificate renewal |
| Gateway VM | TCP 80 and 443 |

All VMs: SSH on TCP 22, with keys only.

1. On every VM, turn off SSH passwords: set `PasswordAuthentication no` in `/etc/ssh/sshd_config`, then `sudo systemctl reload ssh`. Check: `ssh -o PubkeyAuthentication=no <user>@<ip>` is refused with `Permission denied (publickey)`.
2. Open the ports in the table. Check from your laptop: `nc -zv chat.example.org 443` says `succeeded` after step 6 (before then, nothing listens). `nc -zv chat.example.org 9090` must fail: the admin port is never public.

## 4. TLS certificates

Caddy gets the certificates for the app and the gateway by itself. Nothing to do, but DNS (step 2) and ports 80 and 443 (step 3) must be right first.

The relays use `turns:` on 443, so coturn needs a certificate. On each relay VM (use its own name instead of `turn1.example.org`):

1. Install certbot: `sudo apt install certbot`. Check: `certbot --version`.
2. Get the certificate: `sudo certbot certonly --standalone -d turn1.example.org`. Expect `Successfully received certificate`.
3. Make the folder coturn reads: `mkdir -p deploy/relay/certs`.
4. Create the renewal hook `/etc/letsencrypt/renewal-hooks/deploy/coturn.sh` (change the paths to your clone). The hook copies the files for coturn and restarts it:

   ```
   #!/bin/sh
   set -eu
   D=turn1.example.org
   OUT=/home/<user>/<repo>/deploy/relay/certs
   install -m 0444 /etc/letsencrypt/live/$D/fullchain.pem $OUT/fullchain.pem
   install -o 65534 -g 65534 -m 0400 /etc/letsencrypt/live/$D/privkey.pem $OUT/privkey.pem
   docker compose -f /home/<user>/<repo>/deploy/relay/compose.yaml restart coturn || true
   ```

5. Make it runnable and run it once: `sudo chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/coturn.sh && sudo /etc/letsencrypt/renewal-hooks/deploy/coturn.sh`. Check: `ls -l deploy/relay/certs` shows both files.
6. Check that renewal works: `sudo certbot renew --dry-run`. Expect `Congratulations, all simulated renewals succeeded`.
7. Let coturn bind port 443 as a normal user: `echo 'net.ipv4.ip_unprivileged_port_start=0' | sudo tee /etc/sysctl.d/90-unprivileged-ports.conf && sudo sysctl --system`. Check: `sysctl net.ipv4.ip_unprivileged_port_start` prints `0`.

## 5. Secrets

Create secrets on the machine that uses them. Never put them in the repository, in chat, or in a shared note. In each shell session, run `umask 077` first so new files are private.

1. Create the TURN secret on the app VM: `openssl rand -base64 32`. Copy the output into `deploy/.env` as `APP_TURN_SECRET=...`. Check: `grep -c '^APP_TURN_SECRET=.\{20,\}' .env` prints `1`.
2. Put the same value on each relay VM, only in the shell where you render the config (step 7 of this section). Do not write it to a file other than the rendered config.
3. Create the push key on the app VM, after the first start (section 6), as in the admin guide: `docker compose exec appserver /appserver keygen push > secrets/push.pem`, then `sudo chown 65532:65532 secrets/push.pem && sudo chmod 0400 secrets/push.pem`. Check: `ls -l secrets/push.pem` shows owner `65532` and mode `-r--------`.
4. Register the push key with your gateway (after the first start, section 6): `docker compose exec appserver /appserver push register --gateway https://push.example.org --name "beta server" --contact you@example.org`. It prints a server ID. Put it in `.env` as `APP_PUSH_SERVER_ID`, and set `APP_PUSH_GATEWAY_URL=https://push.example.org`, `APP_PUSH_KEY_FILE=/secrets/push.pem`. Then `docker compose up -d`. Check: `docker compose logs appserver | tail -5` shows no error.
5. The escrow key, only if your policy allows server-readable chats (`"newConversations": "server_readable"`): follow "Escrow key" in the admin guide. Back it up separately from the database. If the policy keeps all chats end-to-end, skip this step.
6. On the gateway VM only, make the gateway's two key files. Run `umask 077` first, then `sudo mkdir -p /etc/pushgw/keys`.
   1. Make the HPKE key. The gateway has no command for it; it needs an X25519 key in PKCS#8 PEM form, which OpenSSL makes: `sudo sh -c 'umask 077; openssl genpkey -algorithm X25519 -out /etc/pushgw/keys/pushgw-hpke.pem'`. Check: `sudo head -1 /etc/pushgw/keys/pushgw-hpke.pem` prints `-----BEGIN PRIVATE KEY-----`. The key is created on the gateway VM only. Never copy it into the repository or paste it anywhere; do not print the whole file.
   2. Copy the APNs `.p8` file from Apple to `/etc/pushgw/keys/apns.p8` (for example with `scp` to your home folder, then `sudo mv`). It never leaves the gateway VM afterwards.
   3. Set owner and mode: `sudo chown 65532:65532 /etc/pushgw/keys/* && sudo chmod 0400 /etc/pushgw/keys/*`. Check: `ls -l /etc/pushgw/keys` shows two files, owner `65532`, mode `-r--------`.
   4. After the gateway starts (section 6), check that it loaded the key: `curl https://push.example.org/v1/gateway-key` returns JSON with the public key.
7. On each relay VM, render the coturn config in `deploy/relay/`. Use the relay's own name and public address. The secret is typed at a prompt, so it stays out of your shell history:

   ```
   cd deploy/relay
   umask 077
   read -r -s -p 'TURN secret: ' TURN_SECRET; export TURN_SECRET; echo
   TURN_REALM=turn1.example.org EXTERNAL_IP=<relay public ip> \
     envsubst '${TURN_SECRET} ${TURN_REALM} ${EXTERNAL_IP}' < turnserver.conf > turnserver.rendered.conf
   unset TURN_SECRET
   sudo chown 65534:65534 turnserver.rendered.conf && sudo chmod 0400 turnserver.rendered.conf
   ```

   coturn runs as user 65534 (`nobody`) inside its container, so that user must own the file. Mode 0400 means no other user on the host can read the TURN secret. Check: `ls -ln turnserver.rendered.conf` shows owner `65534` and mode `-r--------`, and `sudo grep -c '\${' turnserver.rendered.conf` prints `0`. To render again later, run `sudo rm turnserver.rendered.conf` first.

## 6. First start

On the app VM:

1. `cp .env.example .env`, edit it, and set `POSTGRES_PASSWORD`, `APP_HOST=chat.example.org`, `APP_TURN_SECRET`, `APP_RELAYS=turn1.example.org@eu,turn2.example.org@us`, `APP_APPLE_APP_IDS`. Check: `grep -c '^APP_HOST=chat.example.org' .env` prints `1`.
2. Start the stack with Caddy: `docker compose --profile caddy up -d postgres appserver caddy`. This beta does not run the single-host coturn: the relays do the calls. Check: `docker compose ps` shows `postgres`, `appserver` and `caddy` as running.
3. Check the server from outside: `curl https://chat.example.org/v1/server`. Expect JSON, and no certificate warning.
4. Make an invite: `docker compose exec appserver /appserver invite create --uses 5`. Expect a link like `https://chat.example.org/invite/ABCDE-FGHJK`. Share that link.

On the gateway VM, in `deploy/pushgw/`:

5. `cp .env.example .env`, edit it (host, database password, APNs key ID, team ID, bundle IDs), then `docker compose up -d`. Check: `curl https://push.example.org/v1/gateway-key` returns JSON with a key.

On each relay VM, in `deploy/relay/`:

6. `docker compose up -d`. Check: `docker compose ps` shows `coturn` running, and `sudo ss -ltnup | grep -E ':(3478|443)\b'` shows coturn on both ports.

## 7. Relay checks

1. Get call credentials. Sign in on a test phone, or ask a tester for a session token, then: `curl -H "Authorization: Bearer <token>" https://chat.example.org/v1/turn`. Expect JSON with `urls`, `username` and `credential`.
2. Open the [Trickle ICE test page](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/). Remove the default server, then add one server for each relay: `turn:turn1.example.org:3478`, `turns:turn1.example.org:443?transport=tcp`, and the same for `turn2`. Use the `username` and `credential` from step 1 for each. Click "Gather candidates". Expect candidates of type `relay` from both relays, over UDP and TLS.
3. Check the deny-list on a relay VM: run `docker run --rm --network host coturn/coturn:4.18 turnutils_uclient -t -u '<username>' -w '<credential>' -e 10.0.0.1 -r turn1.example.org turn1.example.org`. Expect the run to fail (`Forbidden IP`, `403` or no data sent). Sending to a public address must still work, so repeat it with `-e <a public address you control>` and expect it to succeed. The credentials last 12 hours; get new ones if the check is late.

## 8. Push check

1. Install a TestFlight build on a phone and sign in with the invite link from the first start (section 6, step 4).
2. Allow notifications when asked. Check: the app shows no error about notifications.
3. Send the phone a message from a second account, with the phone locked and the app closed. Expect a notification within a few seconds.

## 9. Backups

The scripts are in `deploy/scripts/`. A backup is a dated folder with the database dump, the attachments, and checksums. The script does not encrypt: keep the backups on encrypted storage. Adding a tool such as `age` needs the project owner's approval.

1. Make a first backup by hand: `sh scripts/backup.sh`. Expect `backup written to ./backups/<today>`. Check: `ls backups/$(date +%F)` shows `SHA256SUMS`, `appdata.tgz`, `db.dump`.
2. Run it every day. `crontab -e`, then add `30 3 * * * cd /home/<user>/<repo>/deploy && sh scripts/backup.sh >> backups/cron.log 2>&1`. The script keeps the 14 newest days and the 8 newest Sundays, and removes the rest. Check the next morning: `tail backups/cron.log` ends with `backup written to`.
3. Copy the backups off the VM, to a different provider, every day: for example `rsync -a --delete backups/ <backup host>:/srv/backups/app/`. Check: `ls` on the backup host shows today's folder.
4. Also keep a separate copy of `deploy/.env`, `deploy/secrets/`, the policy file, and the escrow key if you have one. They are not in the backup.
5. On the gateway VM, back up its database the same way: `cd deploy/pushgw && DB_USER=pushgw DB_NAME=pushgw SKIP_DATA=1 sh ../scripts/backup.sh`. Check: the new folder has `db.dump` and `SHA256SUMS`.
6. **Before you invite anyone, run a restore drill on a scratch VM, then repeat it every month.**
   1. On a new, empty VM with Docker, clone the repository and copy over one backup folder and your `.env` (or use the test `.env.example` values).
   2. `cd deploy && sh scripts/restore.sh <path to the backup folder>`. Expect `restore finished`.
   3. `docker compose up -d postgres appserver`. Check: `curl -s http://127.0.0.1:8080/v1/server` returns JSON, and `docker compose exec postgres psql -U appserver -d appserver -c 'select count(*) from accounts'` shows your accounts.
   4. Write down the date and the result (pass or fail) in your operations notes. If it fails, fix it and repeat the drill that day.
7. The project also runs an automatic drill on test data every week in GitHub Actions (`restore-drill`). Check that the latest run is green: `gh run list --workflow restore-drill -R <owner>/<repo> --limit 1`. That drill checks the scripts, not your real backups, so it does not replace the monthly drill.

## 10. Monitoring

Pick the tool yourself. A self-hosted tool needs the project owner's approval, which here is you recording the choice in the project decisions. A hosted uptime service does not.

1. Add an external HTTPS probe for `https://chat.example.org/v1/server` and one for `https://push.example.org/v1/gateway-key`. Alert if either fails twice in a row. Check: stop Caddy for a minute (`docker compose stop caddy`), see the alert arrive, then `docker compose start caddy`.
2. Add disk and memory alerts on all four VMs: disk over 80 percent used, memory over 90 percent. Check: the tool shows current values for each VM.
3. Once a week, look at the counters through an SSH tunnel to the admin port (never open port 9090 to the internet): `ssh -L 9090:127.0.0.1:9090 <user>@<app ip>`, then in another window `curl -s http://127.0.0.1:9090/debug/vars`. Look for growing error counts. Check: the command prints JSON.
4. Nothing logs client addresses, by design. The app server never logs addresses, tokens or message data (`docker compose logs --since 1h appserver`). coturn and Caddy are set to write no logs at all, so `docker compose logs coturn` and `docker compose logs caddy` are empty. That also means they will not tell you what went wrong. To debug, remove the logging lines for a short time (in `turnserver.conf`: `no-stdout-log`, `log-file=/dev/null` and `simple-log`, then add `verbose` and render again; in the `Caddyfile`: the global `log` block), restart, reproduce the problem, and put the lines back. Logs taken this way can hold addresses: do not keep them or share them.

## 11. Updates

1. Once a month, in a quiet hour, patch the operating system on all four VMs: `sudo apt update && sudo apt upgrade`, then reboot if asked (`sudo reboot`). Check: after the reboot, `docker compose ps` shows everything running, and the probes in step 10 are green.
2. When coturn publishes a security release, update each relay promptly (do not wait for the monthly window): change the tag in `deploy/relay/compose.yaml` to the new release, then `docker compose pull && docker compose up -d`. Check: `docker compose ps` shows `coturn` running, and repeat the Trickle ICE test of step 7.
3. To update the app or the gateway, follow "Backups, restore, upgrade, rollback" in the admin guide: take a backup first, then pull, build, migrate, and start. If something is wrong, use the rollback steps there. Check: `curl https://chat.example.org/v1/server` answers.

## 12. Incident basics

Keep this list where you can find it fast. Write down the time and what you did.

1. **The TURN secret leaked.**
   1. Make a new secret (`openssl rand -base64 32`, with `umask 077`).
   2. Put it in `APP_TURN_SECRET` in `deploy/.env` on the app VM and run `docker compose up -d appserver`.
   3. Render the relay config again on each relay VM (step 5.7, removing the old rendered file first) and run `docker compose restart coturn`.
   4. Check: new credentials from `GET /v1/turn` work in the Trickle ICE test, and old ones do not.
2. **The push key leaked.**
   1. Make a new key (step 5.3), replacing `secrets/push.pem`.
   2. Register it again (step 5.4) and set the new `APP_PUSH_SERVER_ID` in `.env`.
   3. `docker compose up -d appserver`. Check: a test message gives a notification on a backgrounded phone (step 8).
3. **A device is stolen or compromised.** Its owner (or you, with the owner's session) revokes it: `curl -X DELETE -H "Authorization: Bearer <token>" https://chat.example.org/v1/devices/<device id>`. Expect a 2xx status. Check: the device's old token now gets `401` on any call.
4. **The APNs key leaked.** Revoke it in your Apple developer account, make a new one, and place it on the gateway VM (step 5.6). Then `docker compose restart pushgw`.
5. **Someone reports a vulnerability.** Follow the [disclosure policy](../security/disclosure-policy.md): acknowledge within 3 business days, triage within 10, and agree a disclosure date. Do not discuss it in public.
6. **After any incident,** make a backup (`sh scripts/backup.sh`), and write down what happened and what you changed.
