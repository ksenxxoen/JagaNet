package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** Several VLESS locations in one key and the balancer (issue #41): subscriptions, admin panel, errors. */
internal val locationStrings: List<S> = listOf(
    // Names in VLESS subscriptions
    S("{name} (backup)", "{name} (запасной)", "{name} (Reserve)"),
    S("{name} (backup {n})", "{name} (запасной {n})", "{name} (Reserve {n})"),
    S("Auto", "Авто", "Auto"),
    S("Auto (best)", "Авто (лучший)", "Auto (beste)"),
    // Admin panel, nodes
    S("Location name", "Название локации", "Name des Standorts"),
    S("Order in the list", "Порядок в списке", "Reihenfolge in der Liste"),
    S("In subscriptions", "В подписке", "In Abos"),
    S("No", "Нет", "Nein"),
    S("Cascade", "Каскад", "Kaskade"),
    S("Entry", "Вход", "Eingang"),
    S("Exit", "Выход", "Ausgang"),
    S("Balancer", "Балансировщик", "Lastverteiler"),
    S(
        "Xray settings for this entry: the exits, a leastPing balancer and the observatory. Add them to the entry's Xray config by hand.",
        "Настройки Xray для этого входа: выходы, балансировщик leastPing и observatory. Добавьте их в конфиг Xray входа вручную.",
        "Xray-Einstellungen für diesen Eingang: die Ausgänge, ein leastPing-Lastverteiler und das Observatory. Füge sie von Hand in die Xray-Konfiguration des Eingangs ein.",
    ),
    S("Show the Xray settings", "Показать настройки Xray", "Xray-Einstellungen zeigen"),
    // Errors
    S("The balancer is off on this server (VLESS_BALANCER)", "Балансировщик на этом сервере выключен (VLESS_BALANCER)", "Der Lastverteiler ist auf diesem Server aus (VLESS_BALANCER)"),
    S("Mark this node as a cascade entry first", "Сначала отметьте эту ноду как вход каскада", "Markiere diesen Knoten zuerst als Eingang der Kaskade"),
    S(
        "No active exit nodes: mark at least one VLESS node as an exit",
        "Нет активных выходных нод: отметьте хотя бы одну VLESS-ноду как выход",
        "Keine aktiven Ausgangsknoten: Markiere mindestens einen VLESS-Knoten als Ausgang",
    ),
)
