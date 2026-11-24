package dev.jaganet.server.services

import dev.jaganet.api.Vless
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang

/**
 * How the subscription list is presented in Happ, v2RayTun and in the profiles (sing-box, Clash):
 * "Fastest" and a balancing entry keep their plain names, then two placeholder rows, then the
 * other locations.
 *
 * Placeholders are valid entries the apps can list, but they do not carry a user's UUID or Reality
 * keys, they point at 127.0.0.1:1, and they are left out of urltest / leastPing so they cannot
 * become the "Fastest" pick.
 */
object SubscriptionLayout {
    const val TELEGRAM_TEXT = "Наш ТГ @boop_vpn"
    /** Leading speech-balloon so Happ draws a chat icon instead of a globe; the text itself is unchanged. */
    const val TELEGRAM = "💬 $TELEGRAM_TEXT"
    const val RESERVE = "⬇️ Резервные сервера ⬇️"
    const val DUMMY_HOST = "127.0.0.1"
    const val DUMMY_PORT = 1
    const val DUMMY_UUID = "00000000-0000-0000-0000-000000000000"

    val PLACEHOLDERS = listOf(TELEGRAM, RESERVE)

    /** The urltest / leastPing group: the translated "Fastest", with no leading icon. */
    fun autoName(lang: Lang) = I18n.tr(lang, "Fastest")

    /** A balancing entry's name in the subscription: the owner's label as-is, no added icon. */
    fun entryName(name: String): String = name

    /** One row of the subscription after the auto group, in the order the apps show it. */
    sealed class Item {
        data class Location(val uuid: String, val name: String, val variant: Vless.Variant) : Item()
        data class Placeholder(val name: String) : Item()
    }

    /**
     * Balancing-entry variants first, then the two placeholders, then the other locations.
     * Empty when there are no locations (the key is off).
     */
    fun items(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT): List<Item> {
        if (locations.isEmpty()) return emptyList()
        fun variants(list: List<VlessLocation>) = list.flatMap { l ->
            l.named(lang).map { (v, n) -> Item.Location(l.params.uuid, n, v) }
        }
        return variants(locations.filter { it.entry }) +
            PLACEHOLDERS.map { Item.Placeholder(it) } +
            variants(locations.filter { !it.entry })
    }

    /** A share link for a placeholder: valid enough to list, no user UUID, no Reality keys, no route. */
    fun dummyLink(name: String): String = buildString {
        append("vless://").append(DUMMY_UUID).append('@').append(DUMMY_HOST).append(':').append(DUMMY_PORT)
        append("?encryption=none&security=none&type=tcp")
        append('#').append(Vless.encode(name))
    }

    /** The base64 list's links, including placeholders, in display order. */
    fun links(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT): List<String> =
        items(locations, lang).map {
            when (it) {
                is Item.Location -> Vless.link(it.uuid, it.variant, it.name)
                is Item.Placeholder -> dummyLink(it.name)
            }
        }

    fun isPlaceholderName(name: String) = name in PLACEHOLDERS

    fun isDummyHost(host: String) = host == DUMMY_HOST
}
