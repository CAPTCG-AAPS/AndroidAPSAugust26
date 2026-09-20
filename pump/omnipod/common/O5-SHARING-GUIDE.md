# Omnipod 5 credential sharing — full procedure

This explains, end to end, how an Omnipod 5 credential gets from your pool onto a builder's
phone using a **token**. It has two audiences:

- **Part A / C / E — you (the admin).** You run a small server, hand out tokens, and manage them.
- **Part B / D — the builder.** They clone your branch, build the app, and paste the token.

Follow it top to bottom the first time. There are no secrets in this file (the server URL is
not secret; the token is what gates access).

---

## How it works in one paragraph

You keep a pool of **12 credentials** (indexes 0–11) in a private Cloudflare store. You create a
**token** that points at one credential index and give it to one builder. Their app (built from
your keyless branch) sends the token to your server; the server checks it, **binds it to that one
phone**, and returns the credential, which the app installs. Tokens are unlimited; credentials are
the scarce thing (12). Same index = same credential. One token works on one phone only.

---

## Key facts to keep straight

- **Tokens are unlimited; credentials are 12.** A token is just a claim ticket that points at a
  credential index (0–11).
- **Same index = same credential.** `make-token.js 1` always hands out the 2nd credential. The
  index is **zero-based**: `0` = 1st, `11` = 12th.
- **The pool is never used up.** The server *copies* the credential at that index; the pool stays
  intact and the same index can be handed out again.
- **Device binding:** the first phone to use a token pins it. A different phone gets refused
  ("already in use on another device"). Same token to two people → only the first works.
- **To give two people the same credential:** make **two** tokens with the **same index**.
- **No usage reporting.** The server only records *if* and *when* a token was first claimed. It
  never learns if a builder deletes the credential or stops using O5 — you only know if they tell
  you. Keep your own log of index → builder.
- **You must assign indexes yourself.** Nothing auto-picks an unused one. Track them.

---

## Part A — The server (already set up; reference only)

You already did this once. Do **not** repeat it unless you're rebuilding from scratch.

- Server code: `C:\Users\craig\o5-credential-server` (worker.js, wrangler.toml, make-pool.js,
  make-token.js, README.md).
- Live at: **`https://o5-credential-server.captcg-o5-8842.workers.dev`**
- Cloudflare KV namespace bound as `STORE` (id `aa66d08e1af24254a172b089acfaf7f0`) holds:
  - `pool` — the 12 credential strings (indexes 0–11)
  - `tok:<token>` — one record per token
- The app branches already point at this URL (`CREDENTIAL_SERVER_URL`), so builders don't touch
  any server setup.

Health check anytime:
```
curl.exe https://o5-credential-server.captcg-o5-8842.workers.dev/status
```
Expect `{"available":true}`.

### Windows gotchas (these cost real time — read once)
- New terminals lose Node from PATH. Start each session with:
  ```
  cd C:\Users\craig\o5-credential-server; $env:Path = "C:\Program Files\nodejs;" + $env:Path
  ```
- Every `wrangler kv key` command needs **`--remote`** or it hits a local test store your phones
  can't see.
- Passing JSON inline to `wrangler` **strips the quotes** and corrupts it. Always write JSON to a
  file and use `--path` (the helper does this for you).
- `wrangler kv key get` prints nothing to the console — redirect it to a file to read it:
  ```
  npx wrangler kv key get --binding=STORE "tok:SOMETOKEN" --remote > out.txt 2>&1; Get-Content out.txt
  ```
- Use `curl.exe`, not `curl` (the PowerShell alias fails TLS).

---

## Part B — Onboard a builder (you)

Do this once per builder.

1. **Give them the code.** Grant read access to the repo (or let them fork), so they can get the
   **`July26OmnipodKtClean`** branch.

2. **Pick an unused credential index** from your log (0–11). Give each builder a different one.

3. **Open a terminal in the server folder:**
   ```
   cd C:\Users\craig\o5-credential-server; $env:Path = "C:\Program Files\nodejs;" + $env:Path
   ```

4. **Generate the token** (replace `1` with the index you picked):
   ```
   node make-token.js 1
   ```
   It prints a random **token** and, right below it, the exact **put** command.

5. **Run the printed put command.** It already has the token, `--path`, and `--remote` filled in.
   You should see it write to namespace `aa66d08e1af24254a172b089acfaf7f0`, with no
   "Resource location: local".

6. **(Optional) verify it stored:**
   ```
   npx wrangler kv key get --binding=STORE "tok:PASTE_THE_TOKEN" --remote > out.txt 2>&1; Get-Content out.txt
   ```
   You should see `{"credentialIndex":1,"active":true,"boundDeviceId":null,"claimedAt":null}`.

7. **Send the token to the builder privately** (DM / Signal / email). Never post it publicly.

8. **Log it** so you know who has what:
   ```
   index 1 → builder: Jane   token: <first 6 chars>...   sent 2026-09-20
   ```

---

## Part C — Build and install (the builder)

The builder does all of this themselves.

1. **Get the branch.** Clone or download **`July26OmnipodKtClean`** from your repo.

2. **Set up the AAPS build** — Android Studio + JDK (the normal AAPS toolchain), and **their own
   signing keystore**. AAPS requires each person to sign with their own key.

3. **Build a signed APK** — flavor **full**, build type **release**, signed with **their** keystore.

4. **Install it on the phone they'll actually loop with:**
   - **Update (recommended, no data loss):** if their phone already runs an AAPS build signed with
     the **same keystore**, install this APK over it. Keeps all data and any paired pod.
   - **Fresh install (wipes data):** only if they have no AAPS yet, or a different keystore. A
     fresh install also resets the phone's internal device id.

> The build ships with **no credential** — that's expected. They get one with the token next.

---

## Part D — Get the credential and pair (the builder)

1. In AAPS, open **Omnipod 5 → settings gear → Certificate Store** (it also opens automatically
   when no credential is installed).
2. Type or paste the **token** into the **Token** field.
3. Tap **Download credential.**
   - Success: a green line "Imported credential for controller 0x…" and a new entry under
     **Installed credentials**.
   - "This token is not valid" → the token is wrong or wasn't stored — check with the admin.
   - "Already in use on another device" → the token was already claimed on another phone — the
     admin must reset it or issue a new one.
4. **Pair the O5 pod** as normal. The credential is now available for pairing.

---

## Part E — Manage tokens later (you)

Always in the server folder, always with `--remote`.

**Check a token (who claimed it, when):**
```
npx wrangler kv key get --binding=STORE "tok:THE_TOKEN" --remote > out.txt 2>&1; Get-Content out.txt
```
`boundDeviceId` filled + a `claimedAt` time = it was claimed on that device.

**Revoke a token (turn it off, keep the record):** build a file with `active:false` and put it:
```
@{credentialIndex=1;active=$false;boundDeviceId=$null;claimedAt=$null} | ConvertTo-Json -Compress | Set-Content -Encoding ascii -NoNewline token.json
npx wrangler kv key put --binding=STORE "tok:THE_TOKEN" --path=token.json --remote
```

**Move a token to a builder's new phone** (clear the binding, keep it active): same as revoke but
`active=$true` (and `boundDeviceId=$null`). The next phone to use it re-binds.

**Delete a token outright:**
```
npx wrangler kv key delete --binding=STORE "tok:THE_TOKEN" --remote
```

> Deleting or revoking a token does **not** remove the credential already on a builder's phone —
> the download already happened. It only stops future claims.

---

## Test walkthrough (do this yourself to prove it works)

A safe end-to-end test on your own phone. It never touches your live pod credential
(`0x002A24D0`).

1. **Make a token** for index 1:
   ```
   cd C:\Users\craig\o5-credential-server; $env:Path = "C:\Program Files\nodejs;" + $env:Path
   node make-token.js 1
   ```
   Run the printed `put` command.

2. **Get the token onto your phone** (email it to yourself, or use a short typed test token
   instead — see note below).

3. On the phone: **Certificate Store → paste token → Download credential.** A new credential
   appears in the list. **Do not** remove `0x002A24D0`.

4. **Confirm the binding** on the PC:
   ```
   npx wrangler kv key get --binding=STORE "tok:THE_TOKEN" --remote > out.txt 2>&1; Get-Content out.txt
   ```
   `boundDeviceId` is now filled = bound to your phone.

5. **Clean up:** remove the test credential on the phone (not `0x002A24D0`), and delete the token:
   ```
   npx wrangler kv key delete --binding=STORE "tok:THE_TOKEN" --remote
   ```

> **Short test token instead of a random one:** to skip copying a long string to the phone, build
> a token record and store it under an easy name:
> ```
> @{credentialIndex=1;active=$true;boundDeviceId=$null;claimedAt=$null} | ConvertTo-Json -Compress | Set-Content -Encoding ascii -NoNewline token.json
> npx wrangler kv key put --binding=STORE "tok:mytest" --path=token.json --remote
> ```
> Then just type `mytest` on the phone. Delete it afterward.
