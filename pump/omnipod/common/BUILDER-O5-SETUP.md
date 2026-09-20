# Build AndroidAPS with Omnipod 5 (using a token)

This is for a **builder** who was given a **token** to run Omnipod 5 on AndroidAPS. You build this
branch yourself and use the token once to download a credential into the app.

## What you need from the person who sent you here
- Access to this repository.
- A **token** (a long string), sent to you privately.

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

The app ships with **no** Omnipod 5 credential - that is expected. You add one next.

## 5. Download your credential
1. Open **AndroidAPS -> Omnipod 5 -> settings gear -> Certificate Store**. (It also opens by
   itself if no credential is installed yet.)
2. Type or paste your **token** into the **Token** field.
3. Tap **Download credential**.
   - Success: "Imported credential for controller 0x..." appears, and it shows in the
     **Installed credentials** list.
   - "This token is not valid" -> the token is wrong; check with the person who sent it.
   - "Already in use on another device" -> that token was already used on another phone; ask for
     a new one.

Use the token on the phone you will actually loop with - it locks to the **first** phone that uses
it.

## 6. Pair your pod
Pair the Omnipod 5 as normal. The downloaded credential is now available for pairing.

## Notes
- The token works **once, on one phone**. Re-installing as an **update** (same keystore) is fine.
  A **fresh install** resets the phone, so the token would then be refused - ask for a reset or a
  new token.
- Keep your keystore. Losing it means you can't update without a fresh install.
- This does not send anything about you anywhere except the one request that downloads the
  credential from the builder's own server. It does not contact Insulet.
