package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** Plans, Account and Referral screens. */
internal val moneyStrings: List<S> = listOf(
    // Plans
    S("Close", "Закрыть", "Schließen"),
    S("Go Pro.\nNo limits, no ads.", "Переходите на Pro.\nБез ограничений и рекламы.", "Hol dir Pro.\nOhne Limits, ohne Werbung."),
    S("Full speed, no throttling", "Полная скорость без ограничений", "Volle Geschwindigkeit ohne Drosselung"),
    S("Split tunneling and kill switch", "Раздельное туннелирование и Kill switch", "Split-Tunneling und Kill Switch"),
    S(
        "{data} a month, {n} device|{data} a month, {n} devices",
        "{data} в месяц, {n} устройство|{data} в месяц, {n} устройства|{data} в месяц, {n} устройств",
        "{data} pro Monat, {n} Gerät|{data} pro Monat, {n} Geräte",
    ),
    S("Continue with Free", "Продолжить бесплатно", "Kostenlos weiter"),
    S("Terms", "Условия", "AGB"),
    S("Privacy", "Конфиденциальность", "Datenschutz"),
    S("{n} month|{n} months", "{n} месяц|{n} месяца|{n} месяцев", "{n} Monat|{n} Monate"),
    S("{data} a month", "{data} в месяц", "{data} pro Monat"),
    S("Choose a plan", "Выберите тариф", "Wähle einen Tarif"),
    S("Pay", "Оплатить", "Bezahlen"),
    S(
        "Payment opens in your browser. Pro turns on here as soon as it goes through.",
        "Оплата откроется в браузере. Pro включится здесь сразу после оплаты.",
        "Die Zahlung öffnet sich im Browser. Pro wird hier aktiv, sobald sie durch ist.",
    ),
    S("Waiting for payment", "Ждём оплату", "Warte auf Zahlung"),
    S("Open payment page", "Открыть страницу оплаты", "Zahlungsseite öffnen"),
    S("No plans on sale right now.", "Сейчас нет тарифов в продаже.", "Gerade sind keine Tarife im Angebot."),

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
        "Your devices are disconnected and your data is erased.",
        "Ваши устройства будут отключены, а данные удалены.",
        "Deine Geräte werden getrennt und deine Daten gelöscht.",
    ),
    S("Cancel", "Отмена", "Abbrechen"),
    S("Delete", "Удалить", "Löschen"),

    // Referral
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
