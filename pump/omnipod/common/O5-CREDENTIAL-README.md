# Omnipod 5 credential: how to get one into the app

Personal reference for getting an Omnipod 5 credential into this build without pasting it by
hand every time. Written so future-me can follow the steps cold.

> **This file's sibling `o5credential.txt` holds a PRIVATE KEY.** Keep it in this private repo
> only. Never let it ride into a public branch or the PR to premnirmal. (The public PR is the
> `omnipod5` module on the other fork, so a file here in `common` will not follow it - but do
> not add it there by hand.)

---

## The short version

- Put one or more credential strings in `pump/omnipod/common/o5credential.txt`.
- The build bakes them into the app (`BuildConfig.O5_EMBEDDED_CREDENTIAL`).
- On a **fresh install**, the app installs one automatically (a random one if there are
  several) - no pasting.
- On an **update**, nothing changes; whatever credential is already installed stays.
- You can always manage credentials by hand on the **Certificate Store** screen in the app
  (Remove, or paste a new one).

---

## The file: `pump/omnipod/common/o5credential.txt`

- One credential, or several separated by a **blank line**.
- Each credential is either:
  - the **packed** form `controllerId|privB64|pubB64|icaB64|tlsB64` (one line), or
  - the **o5keypair JSON** object (`controllerId`, `privateKey`, `publicKey`,
    `intermediateCA`, `tlsCertificate`). Pretty-printed is fine - it has no blank lines inside.
- Packed and JSON can be mixed. No blank line *inside* a credential.
- If the file is missing, the field is empty and nothing is embedded - the app behaves as if
  this feature were not there (you would paste a credential by hand).
- Size limit: the embedded value is one string constant, capped at ~64 KB. A JSON credential
  is about 3.1 KB once encoded, so ~20 is the rough ceiling; packed is smaller.

Example (three, blank-line separated):

```
2761936|<privB64>|<pubB64>|<icaB64>|<tlsB64>

{"controllerId":"2733996","privateKey":"...","publicKey":"...","intermediateCA":"...","tlsCertificate":"..."}

<third credential>
```

---

## How the app decides (the rules)

Seeding runs once per install, tracked by a stored flag (`EmbeddedCredentialSeeded`, which
survives updates and clears only on a fresh install):

| Situation | What happens |
|---|---|
| **Fresh install**, store empty | Installs one embedded credential (random if several). Marks seeded. |
| **Update** over an existing app | Nothing seeds. The installed credential stays. |
| A credential is already installed | Does not override it; just marks seeded. |
| You tap **Remove** in the app | Stays removed until the next fresh install (then it re-seeds). |
| Build has no `o5credential.txt` | Nothing embedded, nothing seeds. |

---

## Steps: put a credential (or a pool) into a build

1. In Android Studio, Project view -> `pump` -> `omnipod` -> `common`. Create/open
   `o5credential.txt` right in the `common` folder. Confirm the path is exactly
   `...\pump\omnipod\common\o5credential.txt` (Copy Path/Reference -> Absolute Path). A Scratch
   file or any other folder will NOT be read by the build.
2. Paste the credential string(s). Several? Separate each with a blank line.
3. **Save** (Ctrl+S).
4. **File -> Sync Project with Gradle Files.** Required - the file is read at Gradle
   configuration time. Skip this and the embedded value stays empty.
5. Build -> **Generate Signed App Bundle / APK -> APK**, your keystore, flavor **full**, build
   type **release**.

### Confirm it embedded (optional)
Check the generated `BuildConfig`:
`pump/omnipod/common/build/generated/source/buildConfig/full/release/app/aaps/pump/omnipod/common/BuildConfig.java`
- `O5_EMBEDDED_CREDENTIAL` should be a long non-empty string.
- Base64-decode it and it should be your credential text (split on blank lines = your pool).

---

## Installing

- **Fresh install** (uninstall first, or a clean device): seeds one credential automatically.
  Open **Certificate Store** and it is listed with source **Built_in** and a **Remove** button.
  **A fresh install WIPES all app data, including the pod pairing** - only do it when replacing
  the pod anyway, or on a spare device.
- **Update** (install over the existing app): keeps the pod and everything else, applies code
  changes, does not touch the credential. The update APK must be **signed with the same
  keystore** as the installed app, or Android refuses it (do NOT uninstall to force it - that
  loses the pod).

---

## Changing / replacing a credential

- **On a live phone (keep the pod):** do it in the app - Certificate Store -> **Remove** ->
  paste the new one. This wins over the built-in default and sticks across updates. Editing
  `o5credential.txt` + updating will NOT change a running app's credential.
- **Change the default for future fresh installs:** edit `o5credential.txt`, rebuild. Takes
  effect only on a fresh install.

---

## If a credential is ever revoked (the reason the pool exists)

Bake in several credentials (the pool). If one is revoked:
- A fresh install of the pool build picks a random one - likely a different, valid cert
  (1-in-N chance of landing on the revoked one; fresh-install again if it does), or
- Cleaner: delete the revoked entry from `o5credential.txt`, rebuild, and future fresh
  installs draw only from the valid ones.

Keep a signed pool APK on hand so you can fresh-install it at a pod change if needed.

---

## Where the code is (if it ever needs changing)

- `pump/omnipod/common/build.gradle.kts` - reads `o5credential.txt` into
  `BuildConfig.O5_EMBEDDED_CREDENTIAL`.
- `SecureO5RegistrationStorage.seedEmbeddedCredentialIfNeeded(...)` - the once-per-install
  seeding, called from `O5BleManagerImpl`'s init after `loadAndInstallAll()`.
- `O5RegistrationData.installOneFromPool(...)` / `installFromText(...)` - parse a pool / a
  single credential (packed or JSON).
- `O5StringNonPreferenceKey.EmbeddedCredentialSeeded` - the "already seeded" flag
  (non-exportable, so it never syncs to Nightscout; neither does the credential itself).
- `ui/O5CredentialImportScreen.kt` + `O5CredentialImportViewModel.kt` - the in-app
  Certificate Store screen (paste / Remove).
