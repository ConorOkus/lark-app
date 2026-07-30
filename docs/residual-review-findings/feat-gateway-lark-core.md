# Residual Review Findings — feat/gateway-lark-core

Source: ce-code-review run `20260728-235715-0d04ad74` (multi-agent review: correctness, testing, reliability, security, maintainability; the independent cross-model adversarial pass timed out on this diff's size — adversarial lens degraded for this run). Verdict: Ready with fixes. Three findings were applied on-branch in `fix(review)` (refresh-cancellation P0, long-poll socket timeout, schema-scoped fixture validation — which itself caught 14 missing spec-required fields in the ArkInfo fixture); the four below were validator-confirmed but fell below the autonomous-apply bar (single-reviewer confidence 75) and are tracked as issues.

## Residual Review Findings

- P1 · `composeApp/src/commonMain/kotlin/xyz/lark/app/core/gateway/GatewayLarkCore.kt:219` · Mnemonic fetch and refresh bypass the R16 network-verification gate — [#9](https://github.com/ConorOkus/lark-app/issues/9)
- P1 · `composeApp/src/commonMain/kotlin/xyz/lark/app/core/gateway/GatewayLarkCore.kt:338` · ark-info 404 leaves health READY with a zero balance forever — [#10](https://github.com/ConorOkus/lark-app/issues/10)
- P2 · `composeApp/src/commonMain/kotlin/xyz/lark/app/core/gateway/BarkdApi.kt:139,161` · Error bodies: unbounded retention + mnemonic-leaking decode excerpts — [#11](https://github.com/ConorOkus/lark-app/issues/11)
- P2 · `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/settings/BackupScreen.kt:88` · Words-unavailable notice shown while the mnemonic fetch is pending — [#12](https://github.com/ConorOkus/lark-app/issues/12)

One finding was validator-rejected (base-URL https enforcement in code: OS cleartext policies + the plan's platform-config carve-out already govern it). Advisory residual risks (report-only): notification long-poll retries at a fixed cadence during outages (documented follow-up); HttpClient never closed (harmless while the core is an app-lifetime singleton); `send()`'s pre-verification blocking relies on the balance==0 invariant rather than an explicit guard; deeply nested `Movement.metadata` decode depth unconfirmed for kotlinx 1.9.0; `HealthyFixtures`' literal `.replace` fixtures silently no-op if base fixtures change; mnemonic words retained in memory for the session.
