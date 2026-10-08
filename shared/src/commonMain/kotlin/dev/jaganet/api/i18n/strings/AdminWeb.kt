package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** The website's admin panel (owners), sign-in links and "service unavailable" states. */
internal val adminWebStrings: List<S> = listOf(
    // Buying and signing in while a service is off
    S("Test payments, no real money", "Тестовая оплата, без реальных денег", "Testzahlungen, kein echtes Geld"),
    S("Sign in with Telegram", "Войти через Telegram", "Mit Telegram anmelden"),
    S(
        "Send /login to the bot and open the link it gives you.",
        "Отправьте боту /login и откройте ссылку, которую он пришлёт.",
        "Schick dem Bot /login und öffne den Link, den er dir gibt.",
    ),
    S("Please try again later.", "Пожалуйста, попробуйте позже.", "Bitte versuch es später noch einmal."),
    S("Test mode, sign-in codes are shown on screen", "Тестовый режим, коды входа показываются на экране", "Testmodus, Anmeldecodes werden auf dem Bildschirm angezeigt"),
    S("or", "или", "oder"),
    S("Signing you in…", "Выполняем вход…", "Du wirst angemeldet…"),
    S(
        "This sign-in link is incomplete. Ask for a new one.",
        "Ссылка для входа неполная. Запросите новую.",
        "Dieser Anmeldelink ist unvollständig. Fordere einen neuen an.",
    ),
    S("Back to sign in", "Вернуться ко входу", "Zurück zur Anmeldung"),

    // Server status page
    S("E-mail sending is not set up yet.", "Отправка e-mail ещё не настроена.", "Der E-Mail-Versand ist noch nicht eingerichtet."),
    S("Set up e-mail", "Настроить почту", "E-Mail einrichten"),
    S("Change recipients", "Изменить получателей", "Empfänger ändern"),

    // Admin panel
    S("Admin panel", "Админ-панель", "Adminbereich"),
    S("Money", "Деньги", "Geld"),
    S("Plans and prices", "Тарифы и цены", "Tarife und Preise"),
    S("E-mail", "Почта", "E-Mail"),
    S("Test modes", "Тестовые режимы", "Testmodi"),
    S("Saved", "Сохранено", "Gespeichert"),

    // Money
    S("Test payments", "Тестовая оплата", "Testzahlungen"),
    S("Revenue by day", "Выручка по дням", "Umsatz pro Tag"),
    S("New subscriptions", "Новые подписки", "Neue Abos"),
    S("New subscription", "Новая подписка", "Neues Abo"),
    S("Renewals", "Продления", "Verlängerungen"),
    S("Renewal", "Продление", "Verlängerung"),
    S("Paid orders", "Оплаченные заказы", "Bezahlte Bestellungen"),
    S("Active subscribers", "Активные подписчики", "Aktive Abonnenten"),
    S("Paid Pro right now", "С оплаченным Pro сейчас", "Mit bezahltem Pro gerade jetzt"),
    S("Website and Telegram subscriptions", "Подписки на сайте и в Telegram", "Abos über Website und Telegram"),
    S("Unpaid orders", "Неоплаченные заказы", "Unbezahlte Bestellungen"),
    S("Checkouts started but not paid", "Начали оплату, но не завершили", "Bezahlung begonnen, aber nicht abgeschlossen"),
    S("New subscriptions and renewals by day", "Новые подписки и продления по дням", "Neue Abos und Verlängerungen pro Tag"),
    S("Subscriptions by channel", "Подписки по каналам", "Abos nach Kanal"),
    S("Subscriptions by plan", "Подписки по тарифам", "Abos nach Tarif"),
    S("Recent payments", "Последние платежи", "Letzte Zahlungen"),
    S("Date", "Дата", "Datum"),
    S("Plan", "Тариф", "Tarif"),
    S("Amount", "Сумма", "Betrag"),
    S("Channel", "Канал", "Kanal"),
    S("Type", "Тип", "Art"),
    S("Via the store", "Через магазин", "Über den Store"),
    S("No payments in this period.", "За этот период платежей нет.", "Keine Zahlungen in diesem Zeitraum."),

    // Plans and prices
    S("Prices", "Цены", "Preise"),
    S(
        "Russian visitors see prices in rubles, German and English visitors in euros.",
        "На русском цены показываются в рублях, на немецком и английском в евро.",
        "Auf Russisch werden Preise in Rubel angezeigt, auf Deutsch und Englisch in Euro.",
    ),
    S("Month, rubles", "Месяц, рубли", "Monat, Rubel"),
    S("Year, rubles", "Год, рубли", "Jahr, Rubel"),
    S("Month, euros", "Месяц, евро", "Monat, Euro"),
    S("Year, euros", "Год, евро", "Jahr, Euro"),
    S("Limits", "Лимиты", "Limits"),
    S("Free data per month, GB", "Бесплатный трафик в месяц, ГБ", "Kostenloses Datenvolumen pro Monat, GB"),
    S("Devices on Free", "Устройств на бесплатном тарифе", "Geräte im kostenlosen Tarif"),
    S("Devices on Pro", "Устройств на Pro", "Geräte mit Pro"),
    S("Pro days for an invite", "Дней Pro за приглашение", "Pro-Tage pro Einladung"),
    S(
        "New prices apply to new payments. Paid subscriptions keep their end date.",
        "Новые цены действуют для новых оплат. У оплаченных подписок дата окончания не меняется.",
        "Neue Preise gelten für neue Zahlungen. Bezahlte Abos behalten ihr Enddatum.",
    ),
    S(
        "Enter prices as numbers above zero, for example 299 or 4,99",
        "Укажите цены числами больше нуля, например 299 или 4,99",
        "Gib die Preise als Zahlen über null ein, zum Beispiel 299 oder 4,99",
    ),

    // E-mail
    S("E-mail is set up, it goes out through {host}.", "Почта настроена, письма уходят через {host}.", "E-Mail ist eingerichtet, sie wird über {host} verschickt."),
    S(
        "Sign-in codes and alerts can't be sent by e-mail yet.",
        "Коды входа и оповещения пока нельзя отправлять по e-mail.",
        "Anmeldecodes und Warnmeldungen können noch nicht per E-Mail verschickt werden.",
    ),
    S("Mail server (SMTP)", "Почтовый сервер (SMTP)", "Mailserver (SMTP)"),
    S("Port", "Порт", "Port"),
    S("Encryption", "Шифрование", "Verschlüsselung"),
    S("None", "Нет", "Keine"),
    S("User name", "Логин", "Benutzername"),
    S("Password", "Пароль", "Passwort"),
    S("saved", "сохранён", "gespeichert"),
    S("Sender address", "Адрес отправителя", "Absenderadresse"),
    S("Leave the password empty to keep the saved one.", "Оставьте пароль пустым, чтобы сохранить прежний.", "Lass das Passwort leer, um das gespeicherte zu behalten."),
    S("Turn e-mail off", "Отключить почту", "E-Mail ausschalten"),
    S(
        "Any service with SMTP works, for example Brevo, Mailgun, Postmark, Amazon SES, Gmail or Zoho. Take the server, port, login and password from its SMTP settings.",
        "Подойдёт любой сервис с SMTP, например Brevo, Mailgun, Postmark, Amazon SES, Gmail или Zoho. Сервер, порт, логин и пароль возьмите из его настроек SMTP.",
        "Jeder Dienst mit SMTP funktioniert, zum Beispiel Brevo, Mailgun, Postmark, Amazon SES, Gmail oder Zoho. Server, Port, Benutzername und Passwort findest du in seinen SMTP-Einstellungen.",
    ),
    S("Send test e-mail", "Отправить тестовое письмо", "Test-E-Mail senden"),
    S(
        "Turn e-mail off? Sign-in codes and alerts will no longer be sent by e-mail.",
        "Отключить почту? Коды входа и оповещения больше не будут приходить по e-mail.",
        "E-Mail ausschalten? Anmeldecodes und Warnmeldungen werden dann nicht mehr per E-Mail verschickt.",
    ),
    S("Sent. Check the inbox and the spam folder.", "Отправлено. Проверьте входящие и папку «Спам».", "Gesendet. Schau in den Posteingang und in den Spam-Ordner."),

    // Alerts
    S("Who gets server alerts", "Кто получает оповещения о сервере", "Wer Serverwarnungen bekommt"),
    S(
        "You get a message when something breaks on the server and when it works again.",
        "Сообщение приходит, когда на сервере что-то ломается и когда всё снова работает.",
        "Du bekommst eine Nachricht, wenn auf dem Server etwas kaputtgeht und wenn es wieder funktioniert.",
    ),
    S(
        "E-mail addresses, one per line or separated by commas",
        "Адреса e-mail, по одному в строке или через запятую",
        "E-Mail-Adressen, eine pro Zeile oder durch Kommas getrennt",
    ),
    S("Telegram chat ids, separated by commas", "Номера чатов Telegram через запятую", "Telegram-Chat-IDs, durch Kommas getrennt"),
    S(
        "Send /myid to the bot in a chat to see that chat's id.",
        "Отправьте боту /myid в нужном чате, чтобы узнать номер этого чата.",
        "Schick dem Bot /myid in einem Chat, um die ID dieses Chats zu sehen.",
    ),
    S(
        "The Telegram bot is not set up, so alerts can't go to Telegram.",
        "Telegram-бот не настроен, поэтому оповещения в Telegram не отправляются.",
        "Der Telegram-Bot ist nicht eingerichtet, deshalb können keine Warnmeldungen an Telegram gehen.",
    ),
    S(
        "A Telegram chat id is a number, like 123456789 or -1001234567890",
        "Номер чата Telegram это число, например 123456789 или -1001234567890",
        "Eine Telegram-Chat-ID ist eine Zahl, etwa 123456789 oder -1001234567890",
    ),

    // Test modes
    S(
        "A test mode is on. Turn it off before real people use the service.",
        "Включён тестовый режим. Отключите его, прежде чем сервисом начнут пользоваться реальные люди.",
        "Ein Testmodus ist an. Schalte ihn aus, bevor echte Menschen den Dienst nutzen.",
    ),
    S("Only for trying the service out yourself.", "Только чтобы самому опробовать сервис.", "Nur um den Dienst selbst auszuprobieren."),
    S("Show sign-in codes on screen", "Показывать коды входа на экране", "Anmeldecodes auf dem Bildschirm zeigen"),
    S(
        "Anyone can then sign in to any account, yours too, just by typing its e-mail address.",
        "Тогда кто угодно сможет войти в любой аккаунт, в том числе в ваш, просто введя его e-mail.",
        "Dann kann sich jeder in jedes Konto einloggen, auch in deins, einfach indem er dessen E-Mail-Adresse eingibt.",
    ),
    S(
        "Anyone can then get Pro without paying. No money is taken.",
        "Тогда кто угодно сможет получить Pro без оплаты. Деньги не списываются.",
        "Dann kann jeder Pro bekommen, ohne zu bezahlen. Es wird kein Geld abgebucht.",
    ),
    S(
        "A real payment service is connected, so test payments can't be turned on.",
        "Подключена настоящая оплата, поэтому тестовую оплату включить нельзя.",
        "Ein echter Zahlungsdienst ist angebunden, deshalb lassen sich Testzahlungen nicht einschalten.",
    ),
)
