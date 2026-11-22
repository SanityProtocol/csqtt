package com.csqtt.client

object CsqttConstants {
    object General {
        const val APP_NAME = "CSQTT"
        const val PACKAGE_VK = "com.vkontakte.android"
        const val PACKAGE_VK_CALLS = "com.vk.calls"
        const val CSQTT_EVENT_PREFIX = "__CSQTT_EVENT__|"
        const val ACTION_RESTART_WHEN_VK_REACHABLE = "com.csqtt.client.RESTART_WHEN_VK_REACHABLE"
        const val ACTION_VERIFY_VK_REACHABILITY = "com.csqtt.client.VERIFY_VK_REACHABILITY"
        const val ACTION_RECOVER_VK_CALL = "com.csqtt.client.RECOVER_VK_CALL"
        // a profile picked while the tunnel is on: the run reconnects onto it (the operator, 2026-11-21)
        const val ACTION_SWITCH_PROFILE = "com.csqtt.client.SWITCH_PROFILE"
        // the profile a START was built for: the run's own from then on (run_profile)
        const val EXTRA_RUN_PROFILE = "run_profile"
        const val EXTRA_UNAVAILABLE_VK_HASH = "unavailable_vk_hash"
    }

    object Profiles {
        const val MIN_INDEX = 0
        const val MAX_INDEX = 31
    }

    object Network {
        const val LOCAL_LISTEN_HOST = "127.0.0.1"
        const val DEFAULT_LOCAL_PORT = 0
        const val DEFAULT_SERVER_PEER_PORT = 46000
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_SERVER_WEB_PORT = 46002
        // «Рядом с 2.1.9»: the second server's own ports (deploy.sh CSQTT_INSTANCE=side)
        const val DEFAULT_SIDE_SERVER_PEER_PORT = 46100
        const val DEFAULT_SIDE_SERVER_WEB_PORT = 46102
    }

    object Tunnel {
        const val BINARY_NAME = "libclient.so"
        const val PROCESS_ENV_EVENTS = "CSQTT_EVENTS"
        // the VK hashes reach the core in its environment, never in argv (M-172)
        const val VK_HASHES_ENV = "CSQTT_VK_HASHES"

        const val DEFAULT_FINGERPRINT = "firefox"
        const val DEFAULT_CLIENT_IDS = "8202606,6287487"
        const val DEFAULT_OBFS_MODE = "unified"
        const val DEFAULT_TURN_TRANSPORT = "udp"
        const val TURN_TRANSPORT_TCP_TLS = "tcp_tls"

        const val WORKERS_PER_GROUP = 10
        const val GROUPS_PER_VK_HASH = 1
        const val MAX_VK_HASHES = 7
        const val DEFAULT_MAX_WORKERS = 70
        // a new profile's workers: one group, what the old stored default of 18 ran as (M-84)
        const val DEFAULT_WORKERS = 10
        const val MAX_WORKERS = 100
    }

    object VkAuth {
        const val MODE_CALLS = "vkcalls"
        const val MODE_CAPTCHA = "legacy"
        const val MODE_AUTO_JS = "auto_js"
    }

    object SrtpMode {
        const val WRAP = "wrap-srtp"
        const val DTLS = "dtls-srtp"
        const val AUTO = "auto"
        const val DEFAULT = AUTO
    }

    object Timeouts {
        const val CAPTCHA_AUTO_TIMEOUT_MS = 10_000L
        const val CAPTCHA_MANUAL_TIMEOUT_MS = 60_000L
        const val CAPTCHA_WV_CREATE_TIMEOUT_MS = 3_000L

        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val LOG_READER_RESET_INTERVAL_MS = 60_000L

        const val VPN_PERMISSION_COOLDOWN_MS = 300L

        const val DEPLOY_CMD_TIMEOUT_MS = 900_000L
        const val DEPLOY_STALL_TIMEOUT_MS = 30 * 60 * 1000L

        const val UPDATE_RATE_LIMIT_COOLDOWN_MS = 30L * 60L * 1000L
    }

    object Thresholds {
        const val FLOOD_CONTROL_THRESHOLD = 5
        const val IP_MISMATCH_THRESHOLD = 5
        const val CONNECTION_REFUSED_THRESHOLD = 400
        const val HASH_ERROR_THRESHOLD = 10
    }

    object Update {
        const val DEFAULT_CHECK_INTERVAL_HOURS = 1   // the operator, 2026-11-19: hourly
        const val CHECK_NEVER = -1
        const val ACTION_POSTPONED = "postponed"
        const val ACTION_UPDATE = "update"

        const val GITHUB_RELEASES_URL =
            "https://api.github.com/repos/SanityProtocol/csqtt/releases?per_page=30"
        // The update channel is our own releases (SanityProtocol/csqtt): the only APKs with this app ID and our key.
        // The automatic check runs every DEFAULT_CHECK_INTERVAL_HOURS, counted from the last check (updateCheckDelayMs),
        // so a UI rebuilt on every return to the foreground does not check each time.
        val CHECK_DISABLED = false   // not const: the check's code stays reachable (its null checks smart-cast)
    }

    object Notifications {
        const val TUNNEL_CHANNEL_ID = "csqtt_tunnel_v4"
        const val TUNNEL_NOTIFICATION_ID = 1

        const val VPN_CHANNEL_ID = "csqtt_vpn_v1"

        const val CAPTCHA_CHANNEL_ID = "captcha_channel"
        const val CAPTCHA_NOTIFICATION_ID = 9001

        // B5h1: a stop the user did not ask for, announced while the app is not in front
        const val STOP_CHANNEL_ID = "csqtt_stops_v1"
        const val STOP_NOTIFICATION_ID = 2

        // B5h2: a background operation the user relies on that failed (a subscription refresh, the D6 hand-over)
        const val BACKGROUND_CHANNEL_ID = "csqtt_background_v1"
        const val SUBSCRIPTION_NOTIFICATION_ID = 3
        const val HANDOVER_NOTIFICATION_ID = 4

        // B5f2: «Запомнить «<сеть>»?», quietly, while the app is not in front
        const val WIFI_CHANNEL_ID = "csqtt_wifi_networks_v1"
        const val WIFI_OFFER_NOTIFICATION_ID = 5
    }

    object Widget {
        const val ACTION_TOGGLE = "com.csqtt.client.ACTION_WIDGET_TOGGLE"
    }

    object Captcha {
        const val ERROR_SLIDER_DETECTED = "slider_detected"
        val AUTO_VIEWPORT_WIDTHS = intArrayOf(356, 358, 360, 362, 364, 366, 368)
        val AUTO_VIEWPORT_HEIGHTS = intArrayOf(376, 378, 380, 382, 384, 386, 388)
    }

    object SecureStore {
        const val KEY_ALIAS = "csqtt.settings.secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val VERSION_PREFIX = "v1:"
    }

    object VkAutoHash {
        const val MODE_MANUAL = "manual"
        const val MODE_AUTO_API = "auto_api"
        const val MODE_AUTO_JS = "auto_js"
        const val MAX_OAUTH_HOPS = 15

        const val VK_APP_ID = 7793118
        const val VK_OAUTH_SCOPE = 1073737727
        const val VK_OAUTH_REDIRECT_URI = "https://oauth.vk.ru/blank.html"
        const val VK_OAUTH_AUTH_URL =
            "https://oauth.vk.ru/authorize" +
                "?client_id=7793118" +
                "&scope=1073737727" +
                "&redirect_uri=https%3A%2F%2Foauth.vk.ru%2Fblank.html" +
                "&display=page" +
                "&response_type=token" +
                "&revoke=1" +
                "&v=5.199"
        const val VK_LOGIN_URL = "https://m.vk.ru/login"
        const val API_METHOD_BASE_URL = "https://api.vk.ru/method/"
        const val API_VERSION = "5.199"

        const val AUTH_TIMEOUT_MS = 300_000L
        const val AUTH_MONITOR_INTERVAL_MS = 200L
        const val AUTH_SILENT_RETRY_DELAY_MS = 1_500L
        // a main-frame error fails the login only if no other page took over within this time (L-59)
        const val AUTH_ERROR_GRACE_MS = 3_000L

        const val AUTO_CALL_SMALL_DELAY_MS = 380L
        const val AUTO_CALL_LARGE_DELAY_MS = 380L
        const val AUTO_CALL_MAX_ATTEMPTS = 3
        const val FINISH_CALL_STEP_MS = 150L
        const val FINISH_CALLS_TIMEOUT_MS = 8_000L
    }

    object Vpn {
        const val DEFAULT_MTU = 1500
        // P1 Bt part 2: the core sends an inner packet of up to 1280 bytes whole and splits a longer one in two
        const val MTU_NO_SPLIT = 1280
        // a custom MTU, the operator 2026-11-22: anything IPv4 allows up to the server's TUN (below 1228 QUIC cannot run)
        const val MIN_MTU = 576
        const val MAX_MTU = 1500
        const val EMPTY_WHITELIST_MESSAGE = "В режиме БС выберите хотя бы одно приложение"
    }

    object Theme {
        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        const val PALETTE_INDIGO = "indigo"
        const val PALETTE_ESPRESSO = "espresso"
        const val PALETTE_FOREST = "forest"
    }

    object Links {
        const val RELEASES = "https://github.com/SanityProtocol/csqtt/releases"
        const val ISSUES = "https://github.com/SanityProtocol/csqtt/issues"
        const val DEVELOPER_PROFILE = "https://github.com/amurcanov"
        const val REPOSITORY = "https://github.com/SanityProtocol/csqtt"
        const val TELEGRAM = "https://t.me/amurcanov_telegram"
        const val TELEGRAM_HANDLE = "t.me/amurcanov_telegram"
    }
}
