package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** The app's referral program screens (Invite friends, owner dashboard referral card). */
internal val referralStrings: List<S> = listOf(
    // Periods
    S("7 days", "7 дней", "7 Tage"),
    S("30 days", "30 дней", "30 Tage"),
    S("90 days", "90 дней", "90 Tage"),
    S("All time", "Всё время", "Gesamt"),
    S("Last 30 days", "Последние 30 дней", "Letzte 30 Tage"),

    // Funnel
    S("Clicks", "Переходы", "Klicks"),
    S("Unique visitors", "Уникальные посетители", "Eindeutige Besucher"),
    S("Visitors", "Посетители", "Besucher"),
    S("Sign-ups", "Регистрации", "Anmeldungen"),
    S("Paid", "Оплатили", "Bezahlt"),
    S("Purchases", "Покупки", "Käufe"),
    S("Visitors to sign-ups", "Из посетителей в регистрации", "Besucher zu Anmeldungen"),
    S("Sign-ups to paid", "Из регистраций в оплату", "Anmeldungen zu Zahlungen"),
    S("Clicks per day", "Переходы по дням", "Klicks pro Tag"),
    S("No clicks in this period yet.", "За этот период переходов пока нет.", "In diesem Zeitraum noch keine Klicks."),

    // Links
    S("Referral program", "Реферальная программа", "Empfehlungsprogramm"),
    S("Your links", "Ваши ссылки", "Deine Links"),
    S("Main link", "Основная ссылка", "Hauptlink"),
    S("Copy link", "Копировать ссылку", "Link kopieren"),
    S("Copy Telegram link", "Копировать ссылку для Telegram", "Telegram-Link kopieren"),
    S("Rename", "Переименовать", "Umbenennen"),
    S("Save", "Сохранить", "Speichern"),
    S("Archive", "В архив", "Archivieren"),
    S("Archive this link?", "Архивировать ссылку?", "Link archivieren?"),
    S("The link stops working and leaves this list.", "Ссылка перестанет работать и исчезнет из списка.", "Der Link funktioniert dann nicht mehr und verschwindet aus der Liste."),
    S("New link", "Новая ссылка", "Neuer Link"),
    S(
        "Make a link for each place you share it, to see which works best.",
        "Создайте отдельную ссылку для каждой площадки и сравните, какая работает лучше.",
        "Erstelle für jeden Ort einen eigenen Link und sieh, welcher am besten wirkt.",
    ),
    S("Link name", "Название ссылки", "Name des Links"),
    S("Custom code, optional", "Свой код, необязательно", "Eigener Code, optional"),
    S("3 to 32 Latin letters, digits or hyphens.", "От 3 до 32 латинских букв, цифр или дефисов.", "3 bis 32 lateinische Buchstaben, Ziffern oder Bindestriche."),
    S("Create link", "Создать ссылку", "Link erstellen"),

    // Sources, channels, people
    S("Top sources", "Главные источники", "Top-Quellen"),
    S("Direct", "Прямые переходы", "Direkt"),
    S("Sign-up channels", "Где регистрируются", "Anmeldekanäle"),
    S("No data for this period.", "За этот период данных нет.", "Keine Daten für diesen Zeitraum."),
    S("Recent referrals", "Недавние приглашённые", "Neueste Empfehlungen"),
    S("No one has signed up with your links yet.", "По вашим ссылкам пока никто не зарегистрировался.", "Über deine Links hat sich noch niemand angemeldet."),
    S("Signed up", "Зарегистрирован", "Angemeldet"),
    S("Pro active", "Pro активен", "Pro aktiv"),
    S("Pro ended", "Pro закончился", "Pro beendet"),
    S("{n} purchase|{n} purchases", "{n} покупка|{n} покупки|{n} покупок", "{n} Kauf|{n} Käufe"),
    S("Show all", "Показать все", "Alle anzeigen"),

    // Owner dashboard
    S("Top referrers", "Лучшие участники", "Top-Empfehler"),
    S("No referrals yet.", "Приглашений пока нет.", "Noch keine Empfehlungen."),
    S("Sign-ups {signups}, paid {paid}", "Регистрации {signups}, оплатили {paid}", "Anmeldungen {signups}, bezahlt {paid}"),
)
