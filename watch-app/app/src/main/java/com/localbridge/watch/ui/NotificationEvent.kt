package com.localbridge.watch.ui

data class NotificationEvent(
    val title: String,
    val body: String,
    val source: String
) {
    // ── Source display mapping ──────────────────────────────────
    private fun rawBundleId(): String {
        var id = source.ifBlank { "iPhone" }
        if (id.startsWith("ANCS: ", ignoreCase = true)) id = id.substring(6)
        return id.trim()
    }

    fun getCleanedSource(): String {
        var clean = rawBundleId()
        if (clean.startsWith("com.", ignoreCase = true)) clean = clean.substring(4)
        return clean
    }

    /**
     * Friendly display name with emoji.
     * Falls back to the last segment of the bundle ID, capitalized.
     */
    fun getDisplaySource(): String {
        val bundleId = rawBundleId()
        SOURCE_MAP[bundleId]?.let { return it }

        // Fallback: extract last segment, replace dots, capitalize
        val segments = bundleId.split(".")
        val last = segments.lastOrNull()?.takeIf { it.length > 1 } ?: segments.reversed().firstOrNull { it.length > 1 } ?: segments.last()
        return last.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    fun cleanDisplayText(): String {
        val messageText = listOf(title, body)
            .filter { it.isNotBlank() && it != source }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return messageText.ifBlank { "New notification" }
    }

    companion object {
        private val SOURCE_MAP = mapOf(
            // ── Messaging ──
            "com.facebook.Messenger" to "💬 Messenger",
            "com.linecorp.LINE" to "💬 LINE",
            "net.whatsapp.WhatsApp" to "💬 WhatsApp",
            "com.burbn.instagram" to "💬 Instagram",
            "jp.naver.line" to "💬 LINE",
            "com.toyopagroup.picaboo" to "💬 Snapchat",
            "com.tencent.xin" to "💬 WeChat",
            "com.viber" to "💬 Viber",
            "com.skype.skype" to "💬 Skype",
            "com.discord" to "💬 Discord",
            "com.hammerandchisel.discord" to "💬 Discord",

            // ── Calls / Phone ──
            "com.apple.InCallService" to "📞 โทรเข้า",
            "com.apple.mobilephone" to "📞 โทร",
            "com.apple.TelephonyUtilities" to "📞 โทร",

            // ── SMS ──
            "com.apple.MobileSMS" to "💬 SMS",
            "com.apple.messages" to "💬 iMessage",

            // ── Email ──
            "com.apple.mobilemail" to "📧 Mail",
            "com.apple.Mail" to "📧 Mail",
            "com.google.Gmail" to "📧 Gmail",
            "com.microsoft.Outlook" to "📧 Outlook",
            "com.readdle.smartemail" to "📧 Spark",

            // ── Calendar / Reminders ──
            "com.apple.mobilecal" to "📅 ปฏิทิน",
            "com.apple.reminders" to "📌 Reminders",
            "com.google.calendar" to "📅 Google Calendar",

            // ── Banking (Thai) ──
            "com.kasikornbank.kplus" to "💰 K PLUS",
            "com.scb.planet" to "💰 SCB EASY",
            "com.bbl.mobilebanking" to "💰 BBL",
            "com.ktb.netbank" to "💰 KTB",
            "com.krungsri.kma" to "💰 KMA",
            "com.easybuy.easypass" to "💰 TrueMoney",

            // ── Social ──
            "com.facebook.Facebook" to "📘 Facebook",
            "com.totter.it" to "🐦 X",
            "com.atebits.Tweetie2" to "🐦 X",
            "com.linkedin.LinkedIn" to "🔗 LinkedIn",
            "com.zhiliaoapp.musically" to "🎵 TikTok",

            // ── Utilities / System ──
            "com.apple.springboard" to "🔔 แจ้งเตือน",
            "com.apple.Preferences" to "⚙️ Settings",
            "com.apple.weather" to "🌤️ Weather",
            "com.apple.Maps" to "🗺️ Maps",
            "com.apple.Health" to "❤️ Health",
            "com.apple.shortcuts" to "⚡ Shortcuts",
            "com.apple.AppStore" to "🛒 App Store",
            "com.apple.watch" to "⌚ Watch",

            // ── Games ──
            "com.supercell.clashofclans" to "🎮 Clash of Clans",
            "com.supercell.clashroyale" to "🎮 Clash Royale",
            "com.nianticlabs.pokemongo" to "🎮 Pokémon GO",
            "com.tencent.pubgm" to "🎮 PUBG Mobile",
            "com.mobile.legends" to "🎮 Mobile Legends",
            "com.garena.rov" to "🎮 ROV",

            // ── Local Bridge ──
            "Galaxy Bridge" to "🌉 Galaxy Bridge",
            "iPhone" to "📱 iPhone"
        )
    }
}