package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** Shared by every product: units, time, plan and product names. */
internal val commonStrings: List<S> = listOf(
    S("{n} h", "{n} ч", "{n} Std."),
    S("{n} min", "{n} мин", "{n} Min."),
    S("never", "никогда", "nie"),
    S("{n} s ago", "{n} с назад", "vor {n} s"),
    S("{n} min ago", "{n} мин назад", "vor {n} Min."),
    S("{n} h ago", "{n} ч назад", "vor {n} Std."),
    S("{n} day ago|{n} days ago", "{n} день назад|{n} дня назад|{n} дней назад", "vor {n} Tag|vor {n} Tagen"),
    S("{n} device|{n} devices", "{n} устройство|{n} устройства|{n} устройств", "{n} Gerät|{n} Geräte"),
    S("{n} GB a month", "{n} ГБ в месяц", "{n} GB pro Monat"),

    // Plans and products
    S("Free", "Бесплатный", "Kostenlos"),
    S("Pro", "Pro", "Pro"),
    S("Pro monthly", "Pro на месяц", "Pro monatlich"),
    S("Pro yearly", "Pro на год", "Pro jährlich"),
    S("Pro for 1 month", "Pro на 1 месяц", "Pro für 1 Monat"),
    S("Pro for 1 year", "Pro на 1 год", "Pro für 1 Jahr"),
    S("Invite reward", "Награда за приглашение", "Einladungsbonus"),
    S("{price} a month", "{price} в месяц", "{price} pro Monat"),
    S("{price} a year", "{price} в год", "{price} pro Jahr"),
    S("{from} to {to}", "с {from} по {to}", "{from} bis {to}"),
    S("Unlimited data", "Безлимитный трафик", "Unbegrenztes Datenvolumen"),

    // Billing sources
    S("App Store", "App Store", "App Store"),
    S("Google Play", "Google Play", "Google Play"),
    S("Website", "Сайт", "Website"),
    S("Telegram", "Telegram", "Telegram"),
    S("Simulation", "Симуляция", "Simulation"),

    // Protocols
    S("Automatic", "Автоматически", "Automatisch"),
    S("Custom (demo)", "Свой протокол (демо)", "Eigenes Protokoll (Demo)"),

    // Language picker
    S("Language", "Язык", "Sprache"),
)
