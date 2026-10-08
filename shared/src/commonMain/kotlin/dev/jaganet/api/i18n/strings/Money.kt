package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** Plans, Account and Referral screens. */
internal val moneyStrings: List<S> = listOf(
    // Plans
    S("Close", "Закрыть", "Schließen"),
    S("Go Pro.\nNo limits, no ads.", "Переходите на Pro.\nБез ограничений и рекламы.", "Hol dir Pro.\nOhne Limits, ohne Werbung."),
    S("Up to 5 devices at once", "До 5 устройств одновременно", "Bis zu 5 Geräte gleichzeitig"),
    S("Full speed, no throttling", "Полная скорость без ограничений", "Volle Geschwindigkeit ohne Drosselung"),
    S("Split tunneling and kill switch", "Раздельное туннелирование и Kill switch", "Split-Tunneling und Kill Switch"),
    S("Best value, billed yearly", "Выгоднее всего, оплата за год", "Bester Preis, jährliche Zahlung"),
    S("Billed every month", "Оплата каждый месяц", "Monatliche Zahlung"),
    S(
        "{data} a month, {n} device|{data} a month, {n} devices",
        "{data} в месяц, {n} устройство|{data} в месяц, {n} устройства|{data} в месяц, {n} устройств",
        "{data} pro Monat, {n} Gerät|{data} pro Monat, {n} Geräte",
    ),
    S("Continue with Free", "Продолжить бесплатно", "Kostenlos weiter"),
    S("Subscribe", "Оформить подписку", "Abonnieren"),
    S(
        "Renews automatically. Cancel anytime in your {store} account.",
        "Продлевается автоматически. Отменить можно в любой момент в аккаунте {store}.",
        "Verlängert sich automatisch. Jederzeit in deinem {store}-Konto kündbar.",
    ),
    S("Restore purchase", "Восстановить покупку", "Kauf wiederherstellen"),
    S("Terms", "Условия", "AGB"),
    S("Privacy", "Конфиденциальность", "Datenschutz"),
    S("BEST VALUE", "ВЫГОДНО", "TOP-PREIS"),

    // Account
    S("Account", "Аккаунт", "Konto"),
    S("Current plan", "Текущий тариф", "Aktueller Tarif"),
    S("Active", "Активен", "Aktiv"),
    S("Devices", "Устройства", "Geräte"),
    S("Renews on", "Продление", "Verlängert am"),
    S("Pro until", "Pro до", "Pro bis"),
    S("Data", "Трафик", "Datenvolumen"),
    S("{data} used", "Использовано {data}", "{data} genutzt"),
    S("This month", "В этом месяце", "Diesen Monat"),
    S("Billed via", "Способ оплаты", "Bezahlt über"),
    S("Change plan", "Сменить тариф", "Tarif ändern"),
    S("Manage in store", "Управлять в магазине", "Im Store verwalten"),
    S("Payment history", "История платежей", "Zahlungsverlauf"),
    S("No payments yet.", "Платежей пока нет.", "Noch keine Zahlungen."),
    S("Expired", "Истекла", "Abgelaufen"),
    S("Cancelled", "Отменена", "Gekündigt"),
    S("Refunded", "Возвращена", "Erstattet"),
    S("No charge", "Без оплаты", "Gratis"),
    S("Invite friends", "Пригласить друзей", "Freunde einladen"),
    S("{n} joined", "Присоединились: {n}", "{n} beigetreten"),
    S(
        "{n} day of Pro earned|{n} days of Pro earned",
        "получен {n} день Pro|получено {n} дня Pro|получено {n} дней Pro",
        "{n} Tag Pro verdient|{n} Tage Pro verdient",
    ),
    S("Free Pro time for both of you", "Бесплатный Pro для вас обоих", "Gratis Pro für euch beide"),
    S("Sign out", "Выйти", "Abmelden"),
    S("Delete account", "Удалить аккаунт", "Konto löschen"),
    S("Delete your account?", "Удалить аккаунт?", "Konto löschen?"),
    S(
        "Your devices are disconnected and your data is erased. Store subscriptions must be cancelled in the store.",
        "Ваши устройства будут отключены, а данные удалены. Подписки из магазина нужно отменить в самом магазине.",
        "Deine Geräte werden getrennt und deine Daten gelöscht. Store-Abos musst du im Store kündigen.",
    ),
    S("Cancel", "Отмена", "Abbrechen"),
    S("Delete", "Удалить", "Löschen"),

    // Referral
    S("Give Pro, get Pro", "Дарите Pro, получайте Pro", "Pro schenken, Pro bekommen"),
    S(
        "When a friend subscribes with your code, you both get {n} day of Pro for free.|When a friend subscribes with your code, you both get {n} days of Pro for free.",
        "Когда друг оформит подписку по вашему коду, вы оба бесплатно получите {n} день Pro.|Когда друг оформит подписку по вашему коду, вы оба бесплатно получите {n} дня Pro.|Когда друг оформит подписку по вашему коду, вы оба бесплатно получите {n} дней Pro.",
        "Wenn ein Freund mit deinem Code ein Abo abschließt, bekommt ihr beide {n} Tag Pro gratis.|Wenn ein Freund mit deinem Code ein Abo abschließt, bekommt ihr beide {n} Tage Pro gratis.",
    ),
    S("Your code", "Ваш код", "Dein Code"),
    S("Copy", "Копировать", "Kopieren"),
    S("Copied", "Скопировано", "Kopiert"),
    S("Invited", "Приглашено", "Eingeladen"),
    S("Subscribed", "Подписались", "Abonniert"),
    S("Earned", "Получено", "Verdient"),
    S("{n} d", "{n} дн.", "{n} T."),
    S("Share invite link", "Поделиться приглашением", "Einladungslink teilen"),
    S("Join me on JagaNet: {url}", "Присоединяйтесь ко мне в JagaNet: {url}", "Komm zu mir auf JagaNet: {url}"),
)
