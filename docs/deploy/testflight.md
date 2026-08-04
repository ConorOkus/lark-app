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

1. **Apple Developer Program membership.** The machine currently has only an *Apple Development*
   certificate for individual team `2LD486V4AU` — no distribution certificate and no provisioning
   profiles. A paid membership is what lets Xcode create the distribution certificate that
   `CODE_SIGN_STYLE: Automatic` expects at archive time.
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
- **First launch is slow.** Creating the wallet takes about 75 seconds against the real chain
  (opening an existing one takes a few seconds). The onboarding screens are readable during it
  because the wallet is created when the funding step is entered, not at the end.
- **Write the 12 words down.** There is no cloud backup in this build (M2 U5–U8 is deferred), so
  Settings → Backup is the only copy. Deleting the app without them loses the wallet.
- **Funding is faucet → deposit → move it in.** "Add money" → "Move bitcoin in" shows an on-chain
  address; send at least 20,000 sat to it from <https://mutinynet.com/faucet>, wait for three
  confirmations (~90s), then "Move it in". The screen distinguishes "arrived" from "confirmed", so a
  disabled button always has a stated reason.
- **Open it at least every few weeks.** Nothing refreshes while the app is closed. The safe bound is
  ~25 days and the app warns ~3.75 days ahead — see [liveness-envelope.md](../liveness-envelope.md).

## Known gaps in this build

- Advanced shows em-dashes for VTXO count, expiry and chain tip: the crate exposes no VTXO listing
  yet, and inventing numbers would be worse than saying nothing.
- Fiat amounts use a fixed 10 sats/cent stand-in — there is no price source on device.
- Health shows "Offline" during the initial wallet open. It is the only one of the four design states
  that does not claim the money is spendable; a real "opening" state is a design change.
- Unilateral exit (#19) and background refresh (#28) are still absent. Both matter more here than
  they did with a gateway, and both are why this stays on a test network.
