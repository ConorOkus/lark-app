# Residual Review Findings — feat/lark-wallet-ui

Source: ce-code-review run `20260728-191323-5e85c537` (multi-agent review + independent cross-model adversarial pass via codex, requested GPT-5.6-luna at xhigh; serving identity unverified on that route). Verdict: Ready with fixes. Three findings were applied on-branch in `fix(review)` (send-flow race guards, config-change survival, empty-history-safe transaction detail); the four below were validated but fell below the autonomous-apply bar (single-reviewer confidence 75) and are tracked as issues.

## Residual Review Findings

- P1 · `composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt:225` · Backup words can stay revealed past 60s across app suspension — [#2](https://github.com/ConorOkus/lark-app/issues/2)
- P2 · `composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt:237` · finishBackup leaves reveal countdown running; words stay revealed on return — [#3](https://github.com/ConorOkus/lark-app/issues/3)
- P3 · `composeApp/src/commonMain/kotlin/xyz/lark/app/App.kt` (ExitRoute binding) · Exit screen shows masked amount when balance is hidden (design parity) — [#4](https://github.com/ConorOkus/lark-app/issues/4)
- P3 · `composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt:181` · Fiat-mode scan sends 10x: invoice digits interpreted as cents — [#5](https://github.com/ConorOkus/lark-app/issues/5)

Advisory residual risks (report-only, no tickets): `centsToSats` overflow with extreme fiat rates (unreachable at the fixed demo rate); `FakeLarkCore.send` read-modify-write not serialized (UI cannot issue concurrent sends); state machine does not collect core StateFlows emitted outside its own intents (fine for the fake core; needed for a push-based real core); `LarkTabBar` and six screens take the whole state machine instead of scoped callbacks; hidden backup grid may be exposed via accessibility semantics (needs a pass before real seeds exist).
