---
title: The iOS keyboard hid a screen's own submit button
date: 2026-08-04
category: ui-bugs
module: ui/onboarding
problem_type: ui_bug
component: payments
symptoms:
  - "With the phrase field focused, \"Restore wallet\" sits underneath the keyboard and cannot be tapped"
  - "Return inserts a line break instead of dismissing, so the keyboard cannot be put away either"
  - "On the deposit screen the status line sits flush against the buttons with no gap, looking squished"
root_cause: incomplete_setup
resolution_type: code_fix
severity: high
tags: [compose-multiplatform, ios, keyboard, ime, layout, accessibility, onboarding]
---

# The iOS keyboard hid a screen's own submit button

## Problem

On the restore screen — where a user types their 12-word recovery phrase — focusing the
field slid the keyboard over "Restore wallet", and there was no way to dismiss it. A user
could type a correct phrase and be unable to reach the button that uses it, on the one
screen people reach for when they have lost a wallet.

## Symptoms

- The submit button is invisible/untappable while the field is focused.
- Return adds a newline rather than dismissing, because the field is multi-line so a long
  phrase can wrap. There is no other dismiss affordance.
- The related, milder symptom that surfaced first: the deposit screen's status line
  ("₿30,000 confirmed and ready to move in.") sat flush against the button row with no
  gap, reported as "squeezing towards the bottom … items look squished".

## What Didn't Work

- **Assuming iOS would resize the view.** It does not here. `iosApp/iosApp/ContentView.swift:21`
  sets `.ignoresSafeArea(.keyboard)`, which tells SwiftUI *not* to inset the Compose view
  when the keyboard appears. That flag is there for good reason (Compose owns its own
  layout), but it moves responsibility for keyboard insets onto the Compose side — and
  nothing on that side had taken it. Before this fix `imePadding` appeared nowhere in the
  codebase.
- **Reading the layout as a spacing problem.** The first instinct on the deposit screen was
  to increase the fixed gaps. That would not have helped: the gaps were not what collapsed.
- **Verifying on the simulator.** The keyboard-specific behaviour could not be reproduced
  there — the simulator stayed in hardware-keyboard mode through disabling
  `ConnectHardwareKeyboard`, relaunching Simulator, and re-focusing the field, so the
  software keyboard never appeared. The layout half was verified on the simulator (forcing
  overflow with `accessibility-extra-large` text); the keyboard half was verified only on a
  physical device.

## Solution

Three separate defects, one per failure mode.

**1. Insets — the Compose side must do it, because the host opted out.**

```kotlin
// RestoreScreen.kt:76
Column(
    modifier = modifier
        .fillMaxSize()
        .imePadding()   // the host set .ignoresSafeArea(.keyboard); nothing else insets
        .padding(/* … */),
)
```

**2. Dismissal — Done instead of a newline.** A multi-line field's Return key inserts a line
break by default, which on a space-separated phrase is useless and leaves no way out:

```kotlin
// RestoreScreen.kt:161-163
keyboardOptions = KeyboardOptions(
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
    imeAction = ImeAction.Done,
),
keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
```

**3. Squeeze — a weighted `Spacer` is not a layout.** Both screens separated their content
from their bottom actions with `Spacer(Modifier.weight(1f))`. A weighted spacer distributes
*leftover* space; when content exceeds the viewport there is none, it collapses to zero, and
only the small fixed paddings remain. Replaced with a scrolling content region and a pinned
bottom block:

```kotlin
// The shape ReceiveScreen.kt:96 already used, now also in DepositScreen.kt:85 and RestoreScreen.kt:91
Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
    // title, body, QR, address — scrolls when it does not fit
}
StatusLine()   // pinned: it explains why the button is enabled or disabled
BottomActions()
```

A fourth, found only by stress-testing: the shared pill buttons used a fixed
`Modifier.height(height)`, which clipped "Check again" mid-word once the label wrapped at an
accessibility text size. Now `heightIn(min = height)` (`Buttons.kt:48,68`) — identical at the
design's sizes, growing rather than clipping at large ones.

## Why This Works

The three fixes correspond to the three things that were missing, and none substitutes for
another: `imePadding` reserves the space, `ImeAction.Done` provides the exit, and the scroll
region removes the dependency on leftover space existing.

The keyboard case is where they compound. With the keyboard up, usable height roughly halves
— which is exactly when a weighted spacer collapses. So on a typing screen the squeeze bug
and the occlusion bug are the same event, and fixing only one leaves a screen that is either
cramped or unusable depending on the device.

Pinning the status line rather than letting it scroll matters for a specific reason: it is
the sentence explaining why the button is disabled. A disabled button whose explanation has
scrolled off screen reads as broken software.

## Prevention

- **A screen with a text field near the bottom needs `imePadding()`.** In this project that is
  not optional or platform-provided: the iOS host sets `.ignoresSafeArea(.keyboard)`, so any
  screen that does not inset for itself will be covered.
- **A multi-line text field needs an explicit dismissal.** `ImeAction.Done` wired to
  `clearFocus()`. Without it the return key types a newline and the keyboard is a trap.
- **Prefer a scroll region to a weighted `Spacer` for "content, then actions".** The spacer
  looks correct on a tall device and fails on a short one, or with large text, or with a
  keyboard — i.e. it fails on the configurations least likely to be tested.
- **Stress-test layout with `xcrun simctl ui <udid> content_size accessibility-extra-large`.**
  It costs one command, needs no extra device, and forced overflow on a tall simulator here —
  which is what surfaced the clipped button label nobody was looking for.
- **Fixed heights clip; minimum heights grow.** Prefer `heightIn(min = …)` for any control
  whose label is text.
- **This class of bug cannot be caught by the test suite as it stands.** There is no
  screenshot or layout test infrastructure in this repo, so all four fixes rest on eyes-on
  verification and nothing prevents regression. That is the gap worth closing if layout bugs
  recur.

## Related Issues

- Deposit-screen squeeze and the pill clipping shipped in PR #38; the restore-screen keyboard
  fixes in PR #39, confirmed on a physical device.
- [Boarding a whole balance cannot pay its own fee](../logic-errors/boarding-a-whole-balance-cannot-pay-its-own-fee.md)
  — the same screen's other defect, found the same way: by using the flow rather than reading it.
