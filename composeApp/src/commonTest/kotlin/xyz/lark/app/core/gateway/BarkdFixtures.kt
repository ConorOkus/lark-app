package xyz.lark.app.core.gateway

/**
 * Wire fixtures derived from the vendored barkd specs: stock 0.4.0
 * (docs/gateway/barkd-openapi-0.4.0.json, [byPath]) and the fork 0.1.0-beta.6
 * (docs/gateway/barkd-fork-openapi-0.1.0-beta.6.json, [forkByPath]).
 *
 * Every fixture is validated by `BarkdFixtureSpecTest` (JVM) against its own endpoint's
 * response schema in its own vendored spec — keys must belong to that schema (the schemaless
 * `metadata` subtree aside) and schema-required properties must be present — so fixture
 * drift fails the build instead of silently green-lighting a wrong DTO (plan R14).
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

    // Carries every ArkInfo property the spec marks required, so the fixture stays a
    // spec-valid response even though the app only decodes the network and the deltas.
    val ARK_INFO = """
        {
          "network": "signet",
          "server_pubkey": "02cc",
          "mailbox_pubkey": "02dd",
          "round_interval": "30s",
          "nb_round_nonces": 64,
          "vtxo_exit_delta": 12,
          "vtxo_expiry_delta": 12960,
          "htlc_send_expiry_delta": 144,
          "htlc_expiry_delta": 40,
          "max_vtxo_amount": 100000000,
          "required_board_confirmations": 3,
          "max_user_invoice_cltv_delta": 1000,
          "min_board_amount_sat": 1000,
          "offboard_feerate_sat_per_kvb": 1250,
          "ln_receive_anti_dos_required": false,
          "fees": {
            "board": {"min_fee_sat": 0, "base_fee_sat": 0, "ppm": 0},
            "offboard": {"base_fee_sat": 0, "fixed_additional_vb": 0, "ppm_expiry_table": []},
            "refresh": {"base_fee_sat": 0, "ppm_expiry_table": []},
            "lightning_receive": {"base_fee_sat": 0, "ppm": 0},
            "lightning_send": {"min_fee_sat": 0, "base_fee_sat": 0, "ppm_expiry_table": []}
          },
          "max_vtxo_exit_depth": 12,
          "max_offboard_inputs": 100
        }
    """.trimIndent()

    // --- Fork surface (barkd fork 0.1.0-beta.6) ---

    // Live-observed channel shape: `expiry_height` may be absent entirely, so this fixture
    // deliberately omits it while keeping `force_close_spend_delay` present.
    val FORK_CHANNEL = """
        {
          "channel_id": "741e8bd3",
          "counterparty": "024fb4d3",
          "capacity_sat": 1000000,
          "local_balance_msat": 500000000,
          "is_usable": false,
          "is_channel_ready": false,
          "force_close_spend_delay": 144
        }
    """.trimIndent()

    val FORK_CHANNELS = "[$FORK_CHANNEL]"

    const val FORK_CHANNELS_BALANCE = """{"balance_sat": 500000}"""

    // The address value stays within the bech32 charset: the app's fork receive path
    // validates it before embedding, and a healthy fixture must survive that check.
    const val FORK_NEXT_ADDRESS = """{"address": "ark1qf2knext"}"""

    // The fork sends an explicit `"pending_exit_sat": null` when the exit subsystem is down.
    val FORK_BALANCE = """
        {
          "spendable_sat": 412350,
          "pending_lightning_send_sat": 1200,
          "claimable_lightning_receive_sat": 800,
          "pending_in_round_sat": 5000,
          "pending_board_sat": 20000,
          "pending_exit_sat": null
        }
    """.trimIndent()

    // Carries every fork-spec-required ArkInfo property; the fork has `supports_channels`
    // and `offboard_fixed_fee_vb` but NO `fees` object or `max_offboard_inputs`.
    val FORK_ARK_INFO = """
        {
          "network": "mutinynet",
          "server_pubkey": "02cc",
          "mailbox_pubkey": "02dd",
          "round_interval": "30s",
          "nb_round_nonces": 64,
          "vtxo_exit_delta": 12,
          "vtxo_expiry_delta": 12960,
          "htlc_send_expiry_delta": 144,
          "htlc_expiry_delta": 40,
          "max_vtxo_amount": 100000000,
          "max_vtxo_exit_depth": 12,
          "required_board_confirmations": 3,
          "max_user_invoice_cltv_delta": 1000,
          "min_board_amount_sat": 1000,
          "offboard_feerate_sat_per_kvb": 1250,
          "offboard_fixed_fee_vb": 200,
          "ln_receive_anti_dos_required": false,
          "supports_channels": true
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

    /**
     * Response fixture per endpoint path of the FORK_BETA6 surface (only the endpoints whose
     * path or shape differs from stock; [MOVEMENT] and [CREATE_WALLET] are shared because the
     * fork's response schemas for them match).
     */
    val forkByPath: Map<String, String> = mapOf(
        "/api/v1/wallet/balance" to FORK_BALANCE,
        "/api/v1/wallet/history" to HISTORY,
        "/api/v1/wallet/create" to CREATE_WALLET,
        "/api/v1/wallet/ark-info" to FORK_ARK_INFO,
        "/api/v1/wallet/addresses/next" to FORK_NEXT_ADDRESS,
        "/api/v1/lightning/channels" to FORK_CHANNELS,
        "/api/v1/lightning/channels/balance" to FORK_CHANNELS_BALANCE,
    )
}
