package xyz.lark.app.core.gateway

/**
 * Wire fixtures derived from the vendored barkd spec (docs/gateway/barkd-openapi-0.4.0.json).
 *
 * Every JSON key used here (except the schemaless `metadata` subtree) is validated against
 * the vendored spec text by `BarkdFixtureSpecTest` (JVM), so fixture drift fails the build
 * instead of silently green-lighting a wrong DTO (plan R14).
 */
object BarkdFixtures {

    val MOVEMENT = """
        {
          "id": 7,
          "status": "successful",
          "subsystem": {"name": "arkoor", "kind": "send"},
          "intended_balance_sat": -14200,
          "effective_balance_sat": -14250,
          "offchain_fee_sat": 50,
          "sent_to": [
            {"destination": {"type": "ark", "value": "ark1qf7demo"}, "amount_sat": 14200}
          ],
          "received_on": [],
          "input_vtxos": ["1111111111111111111111111111111111111111111111111111111111111111:0"],
          "output_vtxos": ["2222222222222222222222222222222222222222222222222222222222222222:1"],
          "exited_vtxos": [],
          "time": {
            "created_at": "2026-07-28T10:00:00Z",
            "updated_at": "2026-07-28T10:00:05Z",
            "completed_at": "2026-07-28T10:00:05Z"
          },
          "metadata": {"note": "coffee"}
        }
    """.trimIndent()

    val BALANCE = """
        {
          "spendable_sat": 412350,
          "pending_lightning_send_sat": 1200,
          "claimable_lightning_receive_sat": 800,
          "pending_in_round_sat": 5000,
          "pending_board_sat": 20000,
          "pending_exit_sat": 6200
        }
    """.trimIndent()

    const val WALLET_EXISTS = """{"fingerprint": "f00dbabe"}"""

    val VTXOS = """
        [
          {
            "id": "3333333333333333333333333333333333333333333333333333333333333333:0",
            "amount_sat": 103087,
            "policy_type": "pubkey",
            "user_pubkey": "02aa",
            "server_pubkey": "02bb",
            "expiry_height": 918402,
            "exit_delta": 12,
            "chain_anchor": "4444444444444444444444444444444444444444444444444444444444444444:1",
            "state": {"type": "spendable"}
          }
        ]
    """.trimIndent()

    val HISTORY = "[$MOVEMENT]"

    val BIP321 = """
        {
          "bip321": "bitcoin:bc1qf7demo?ark=ark1qf7demo",
          "ark": "ark1qf7demo",
          "bolt11": "lnbc210n1demo",
          "onchain": "bc1qf7demo"
        }
    """.trimIndent()

    const val SEND = """{"message": "Payment sent"}"""

    val PENDING_ROUND = """
        {
          "id": 3,
          "status": {"status": "pending"},
          "participation": {"inputs": [], "outputs": []},
          "unlock_hash": null,
          "funding_txid": null
        }
    """.trimIndent()

    const val CREATE_WALLET = """{"fingerprint": "f00dbabe"}"""

    const val MNEMONIC = """{"mnemonic": "tide margin ocean lens quiet ember ladder forest plum signal harbor wren"}"""

    val WAIT_NOTIFICATIONS = """
        {
          "notifications": [
            {"type": "movement-created", "movement": $MOVEMENT},
            {"type": "movement-updated", "movement": $MOVEMENT},
            {"type": "channel-lagging"}
          ],
          "last_pushed_at": "2026-07-28T10:00:06Z"
        }
    """.trimIndent()

    const val CONNECTED = """{"connected": true}"""

    const val TIP = """{"tip_height": 916214}"""

    val ARK_INFO = """
        {
          "network": "signet",
          "round_interval": "30s",
          "vtxo_expiry_delta": 12960,
          "vtxo_exit_delta": 12
        }
    """.trimIndent()

    /** The wire discriminator values of [WalletNotification], validated against the spec. */
    val NOTIFICATION_TYPES = listOf("movement-created", "movement-updated", "channel-lagging")

    /** Response fixture per endpoint path, for routing MockEngine handlers. */
    val byPath: Map<String, String> = mapOf(
        "/api/v1/wallet" to WALLET_EXISTS,
        "/api/v1/wallet/balance" to BALANCE,
        "/api/v1/wallet/vtxos" to VTXOS,
        "/api/v1/history" to HISTORY,
        "/api/v1/wallet/bip321" to BIP321,
        "/api/v1/wallet/send" to SEND,
        "/api/v1/wallet/refresh/all" to PENDING_ROUND,
        "/api/v1/wallet/create" to CREATE_WALLET,
        "/api/v1/wallet/mnemonic" to MNEMONIC,
        "/api/v1/notifications/wait" to WAIT_NOTIFICATIONS,
        "/api/v1/wallet/connected" to CONNECTED,
        "/api/v1/bitcoin/tip" to TIP,
        "/api/v1/wallet/ark-info" to ARK_INFO,
    )
}
