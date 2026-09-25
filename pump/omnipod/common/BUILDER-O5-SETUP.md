# Build AndroidAPS with Omnipod 5

This is for a **builder** who wants to run Omnipod 5 on AndroidAPS. You build this branch
yourself, then get a certificate so the app can talk to a pod.

A certificate is what lets the app connect to a pod and sign the commands it sends. **Everyone
needs their own** - it is key material, so treat the file like a password and do not share it.

## What you need
- Access to this repository.
- A **GitHub account with 2FA enabled**, and a fork of the AndroidAPS repository. That is how the
  key manager decides who may be issued a certificate.

## 1. Get the code
Clone or download this repository (its default branch).

## 2. Set up to build
- Android Studio and the normal AndroidAPS build toolchain (JDK, SDK).
- Your **own signing keystore**. AndroidAPS requires you to sign with your own key. Keep it safe -
  you need the same key for every future update.

## 3. Build the app
Build a **signed** APK: flavor **full**, build type **release**, signed with your keystore.

## 4. Install it
- **If you already run AndroidAPS signed with the same keystore:** install this APK over it (an
  update). Nothing is lost - your settings and any paired pod stay.
- **If you don't have AndroidAPS yet, or used a different key:** install fresh. (A fresh install
  starts empty.)

The app ships with **no** Omnipod 5 certificate - that is expected. You get one next.

## 5. Get your certificate

Open **AndroidAPS -> Omnipod 5 -> settings gear -> Certificate Store**. (It also opens by itself
if no certificate is installed yet.) Then use whichever of these suits you.

### Option A - sign in from the app (simplest)
1. Tap **Get a certificate (sign in)**.
2. Sign in with GitHub when the page asks.
3. The page hands the certificate straight back to the app and the window closes. You should see
   "Imported credential for controller 0x..." and a new row in **Installed credentials**.

Signing in again later is safe: it issues you the **same** certificate rather than a new one, so
this is also how you recover if you ever lose it.

### Option B - fetch it in a browser, then import the file
Use this if the in-app sign-in does not work on your phone, for example if its WebView is too old.

1. Go to **https://api.osaid-keymanager.org/o5/aaps/start** in a browser and sign in with GitHub.
2. Press **Download certificate**. You get a file ending in `.o5keypair`. The download link is
   only good for 60 minutes and only in that browser - if it expires, just sign in again.
3. If you downloaded it on a computer, move the file to your phone, for example over USB into the
   phone's **Downloads** folder. Avoid sending it through chat or email that other people can see.
4. In the Certificate Store, tap **Import from file (.o5keypair)** and pick the file.
5. Delete the copy afterwards. It is key material.

### Option C - a token from the branch maintainer
Only if you were given one, and only if the options above are unavailable to you. Type or paste
the **token** into the **Token** field and tap **Download credential**.

- "This token is not valid" -> the token is wrong; check with the person who sent it.
- "Already in use on another device" -> it was already used on another phone; ask for a new one.

A token works **once, on one phone**, and locks to the first phone that uses it. Prefer options A
or B: they issue you your own certificate instead.

## 6. Pair your pod
Pair the Omnipod 5 as normal. The certificate is now available for pairing.

## Notes
- **Keep your keystore.** Losing it means you cannot update without a fresh install.
- **Do not remove the certificate your pod is using.** The app refuses this while a pod is active,
  because the running pod needs it to keep accepting commands. Other certificates can be removed
  freely.
- A fresh install wipes app data. With options A and B you simply get your certificate again;
  with a token you would have to ask for a reset or a new one.
- The app contacts the key manager only to fetch your certificate. It does not contact Insulet.
