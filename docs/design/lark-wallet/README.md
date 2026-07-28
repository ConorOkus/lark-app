# LARK Wallet — imported design reference

Imported 2026-07-28 from claude.ai/design project `5a78d049-5f0b-4f24-a645-d06672831882`
("LARK Bitcoin wallet design") for implementation in the lark-app KMP/Compose Multiplatform repo.

## Files

- `LARK Wallet.dc.html` — THE design source of truth. An interactive prototype covering every
  screen of the app plus its full demo logic (routes, health states, keypad math, formatting)
  in the `<script data-dc-script>` block at the bottom.
- `LARK Tab Bar.dc.html` — the shared bottom tab bar component (Scan icon / WALLET pill /
  ACTIVITY pill / Settings icon). Selected pill: bg #F4F1EA, text #0B0C0E; inactive text
  rgba(244,241,234,.45).
- `support.js` — dc-runtime for the design canvas (template binding). Not relevant to the
  Compose implementation; only explains the `{{ ... }}` / `sc-if` / `sc-for` syntax.
- ios-frame.jsx (not saved) — iOS device frame used for canvas preview only; not app UI.
- colors_and_type.css (not saved) — Block Interface design-system tokens. NOTE: the wallet
  design does NOT use these tokens; it carries its own inline styles (verified: no `.t-*`
  classes or `var(--…)` references in the dc.html). Treat the inline styles as the spec.

## Design language (from the dc.html inline styles)

- Background app: #0B0C0E (canvas #08090A); surface cards: #14161A, border rgba(255,255,255,.07),
  hover #1C1F24; radius 16 (cards), 9999 (pills/CTAs), 20 (home action tiles), 14 (list groups).
- Text: primary #F4F1EA; secondary rgba(244,241,234,.5)/(.45); tertiary ~.35–.4.
- Gold accent #E8C15C (hover #F3D68C), on-gold text #14100A. RULE: one gold moment per screen.
- Danger/warn orange #FF7A4D (on-orange #241008); success green #6FE3A8.
- Fonts: Manrope (body/UI), Bricolage Grotesque (display numerals/headlines), JetBrains Mono
  (codes/addresses). Tabular numerals for money.
- Animations: lk-spin (spinner), lk-rise (fade-up .24–.3s cubic-bezier(.22,.61,.36,1)), lk-pulse.

## Screens (route keys from the prototype)

welcome, howitworks, fund, boarding, restore · home, activity, txdetail, txtech ·
sendinput, scan, amount, review, sending, sent, failed · receive ·
settings, backup, health, advanced, exit

## Behavioral spec encoded in the prototype's Component class

- Navigation: `push(route)`/`back()` with a stack; `go(route)` resets the stack (tab-level nav).
- Health states: ready / tidying / stale / offline — each defines home indicator word+dot color,
  banner (stale/offline only), status screen title/body/action, ASP status, and whether a send
  fails (offline ⇒ 'failed'). Colors: ready/tidying dot #6FE3A8; stale/offline #FF7A4D.
- Denomination toggle btc/fiat everywhere money shows: ₿412,350 (BIP 177, no "sats" suffix)
  with $412.35 secondary; demo rate: 1 sat = $0.001 (sats/10 = cents).
- Balance hide/show (•••• when hidden).
- Keypad: integer digits, max 8, leading-0 suppressed; over-balance → amount + avail line turn
  #FF7A4D and Review disabled (opacity .35). Send mode → Review; receive mode → "Make the code".
- Send: confirm → 'sending' (1.5s) → 'sent' (or 'failed' when health=offline).
- Backup: 12 words blurred (7px) until "Tap to reveal", auto-hide after 60s countdown;
  "I've written them down" sets backedUp (Settings row: "Not done yet" #FF7A4D → "Done").
- Receive: one QR + `ark1qf7…lark.money` code, Copy (→"Copied" 1.6s), Set amount via keypad.
- Recents/Activity mock data: Jack jack@lark.money, Maya, Ferry Building Coffee, Dani, Added money.
- Advanced: FUNDS (VTXOs 4 · ₿412,350, soonest expiry, last refresh, on-chain reserve ₿6,200,
  deposit address) + NETWORK (Ark server, next round, Lightning bridge, chain tip) +
  "Refresh funds now" and "Move everything on-chain" (orange, → exit screen).
- SETTLED (stated in the design): 1) BIP 177 ₿ format, dollars demoted to second line;
  2) on-chain address lives in Advanced only, "Get paid" is one code; 3) refresh is silent —
  wallet says "Ready" while tidying; it only speaks when stale.
- Demo affordances in the canvas (state rail, jump chips, "Simulate a scan", "Skip ahead (demo)")
  are prototype tooling, not product UI — but scan simulation & settling skip exist inside the
  phone frame and may need demo equivalents until a real backend/camera exists.
