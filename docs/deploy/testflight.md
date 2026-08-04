# Shipping lark to TestFlight

The build is a **keys-on-device** wallet on **mutinynet**: each tester's phone generates its own
seed, keeps it in the Keychain, and talks to the team's captaind directly. No shared wallet, no
gateway. Test coins only.

Everything mechanical is in the repo. What is left needs an Apple account and cannot be scripted
from here — that part is in "One-time setup" below.

## Every build

```sh
# 1. The Rust core for device + simulator (slow: builds bark + LDK in release)
scripts/build-xcframework.sh

# 2. Bump the build number — App Store Connect rejects one it has seen before
#    iosApp/project.yml → CURRENT_PROJECT_VERSION

# 3. Archive, export, upload
export ASC_KEY_ID=...  ASC_ISSUER_ID=...  ASC_KEY_PATH=~/keys/AuthKey_XXXX.p8
scripts/testflight.sh
```

`scripts/testflight.sh archive` and `... upload` run separately if you want to inspect the `.ipa`
first. The upload step validates before uploading, which catches the whole class of rejections
(missing icon, duplicate build number, entitlement mismatch) without burning a build number.

Sanity numbers for a good build: ~62 MB `.app`, `arm64` only, `CFBundleIcons` present,
`ITSAppUsesNonExemptEncryption = false`.

## One-time setup (needs the Apple account)

1. **A distribution certificate, created once from the Xcode UI.** This is the real gate, and it has
   to be done interactively at least once — measured, not assumed:

   - The machine has only an *Apple Development* identity. With no distribution certificate present,
     `xcodebuild archive` does not fail; it quietly signs with
     `Apple Development: Conor Okus` and `iOS Team Provisioning Profile: *`. The archive succeeds and
     then `-exportArchive -exportOptionsPlist … app-store-connect` cannot produce an App Store ipa,
     because that needs an *Apple Distribution* identity.
   - `-allowProvisioningUpdates` does not rescue a headless run: it needs an authenticated Apple ID
     session and **blocks indefinitely** (observed: 12 minutes at 0% CPU, no output) rather than
     failing, because it cannot show the sign-in prompt.
   - `codesign` can also block waiting for keychain authorisation on the private key the first time.

   So: open `iosApp/iosApp.xcodeproj` in Xcode (after `xcodegen generate`), make sure the account is
   signed in under Settings → Accounts, and run **Product → Archive** once. Xcode creates the
   distribution certificate and the App Store profile, and grants codesign access to the key. Every
   later build can then use `scripts/testflight.sh` headlessly.
2. **An App Store Connect app record** for bundle id `xyz.lark.app`, name and SKU of your choosing.
   The archive will build without it; the upload will not.
3. **An App Store Connect API key** (Users and Access → Integrations). Download the `.p8` once —
   Apple does not offer it again — and keep it out of the repo. The three env vars above point at it.
4. **Internal testers only, to begin with.** Internal TestFlight (up to 100 App Store Connect users
   on the team) skips Beta App Review. External testing does not, and review guideline 3.1.5(b)
   expects a wallet app to come from an **organization** account rather than an individual one. So
   external testing is a separate conversation, not a checkbox.

## Things to tell testers

- **It is test money.** mutinynet coins, worth nothing.
- **First launch takes a moment.** Creating the wallet is a couple of seconds of chain work
  (measured 1.9s on the simulator against real mutinynet), and it happens when the funding step is
  entered rather than at the end of onboarding, so it overlaps with reading that screen. Every
  launch after that re-opens the existing wallet in about the same time.
- **Write the 12 words down.** There is no cloud backup in this build (M2 U5–U8 is deferred), so
  Settings → Backup is the only copy. Deleting the app without them loses the wallet.
- **Funding is faucet → deposit → move it in.** "Add money" → "Move bitcoin in" shows an on-chain
  address; send at least 20,000 sat to it from <https://mutinynet.com/faucet>, wait for three
  confirmations (~90s), then "Move it in". The screen distinguishes "arrived" from "confirmed", so a
  disabled button always has a stated reason.
- **Open it at least every few weeks.** Nothing refreshes while the app is closed. The safe bound is
  ~18 days and the app warns ~2.6 days ahead — see [liveness-envelope.md](../liveness-envelope.md).

## Known gaps in this build

- Advanced shows em-dashes for VTXO count, expiry and chain tip: the crate exposes no VTXO listing
  yet, and inventing numbers would be worse than saying nothing.
- Fiat amounts use a fixed 10 sats/cent stand-in — there is no price source on device.
- Health shows "Offline" during the initial wallet open. It is the only one of the four design states
  that does not claim the money is spendable; a real "opening" state is a design change.
- Unilateral exit (#19) and background refresh (#28) are still absent. Both matter more here than
  they did with a gateway, and both are why this stays on a test network.
