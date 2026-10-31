package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** The full Telegram bot and Telegram sign-in on the website (issue #38). */
internal val telegramBotStrings: List<S> = listOf(
    // Menu and help
    S("My keys and connection", "Мои ключи и подключение", "Meine Schlüssel und Verbindung"),
    S("Buy a subscription", "Купить подписку", "Abo kaufen"),
    S("Buy or extend the subscription", "Купить или продлить подписку", "Abo kaufen oder verlängern"),
    S("Support", "Поддержка", "Support"),
    S("Write to support", "Написать в поддержку", "An den Support schreiben"),
    S("Commands", "Команды", "Befehle"),
    S("Subscription active until {date}.", "Подписка активна до {date}.", "Abo aktiv bis {date}."),
    S(
        "Choose what to do in the menu. To write to support, tap Support.",
        "Выберите действие в меню. Чтобы написать в поддержку, нажмите «Поддержка».",
        "Wähle im Menü, was du tun möchtest. Um dem Support zu schreiben, tippe auf Support.",
    ),
    S(
        "How to connect is in My keys. Questions? Write to support, we answer here.",
        "Как подключиться, написано в разделе «Мои ключи». Есть вопросы? Напишите в поддержку, ответим здесь.",
        "Wie du dich verbindest, steht unter Meine Schlüssel. Fragen? Schreib dem Support, wir antworten hier.",
    ),
    // Buying
    S(
        "Your subscription is active until {date}. A new payment adds to it.",
        "Ваша подписка активна до {date}. Новая оплата продлит её.",
        "Dein Abo ist aktiv bis {date}. Eine neue Zahlung verlängert es.",
    ),
    // Keys
    S("Your key is off until the subscription is renewed.", "Ключ выключен до продления подписки.", "Dein Schlüssel ist aus, bis das Abo verlängert wird."),
    S("QR code of your subscription. Scan it in the app.", "QR-код вашей подписки. Отсканируйте его в приложении.", "QR-Code deines Abos. Scanne ihn in der App."),
    S("Your subscription link", "Ваша ссылка-подписка", "Dein Abo-Link"),
    S("Key page with QR code and copy buttons", "Страница ключа с QR-кодом и кнопками копирования", "Schlüsselseite mit QR-Code und Kopierknöpfen"),
    S(
        "1. Install Happ or v2RayTun (buttons below). Hiddify works too.",
        "1. Установите Happ или v2RayTun (кнопки ниже). Подойдёт и Hiddify.",
        "1. Installiere Happ oder v2RayTun (Knöpfe unten). Hiddify geht auch.",
    ),
    S(
        "2. Copy the subscription link and add it in the app with + and Import from clipboard, or scan the QR code.",
        "2. Скопируйте ссылку-подписку и добавьте её в приложении через «+» и «Импорт из буфера» или отсканируйте QR-код.",
        "2. Kopiere den Abo-Link und füge ihn in der App über + und Aus Zwischenablage importieren hinzu, oder scanne den QR-Code.",
    ),
    S(
        "Keep the link private. Anyone who has it uses your subscription.",
        "Не передавайте ссылку другим. Любой, у кого она есть, пользуется вашей подпиской.",
        "Gib den Link nicht weiter. Wer ihn hat, nutzt dein Abo.",
    ),
    // Devices
    S("Devices {used} of {limit}", "Устройства {used} из {limit}", "Geräte {used} von {limit}"),
    S("No devices connected yet.", "Подключённых устройств пока нет.", "Noch keine Geräte verbunden."),
    S("last seen {date}", "был в сети {date}", "zuletzt online {date}"),
    S("not connected yet", "ещё не подключалось", "noch nicht verbunden"),
    S(
        "The subscription link from My keys is not counted. Delete a device to free a place.",
        "Ссылка-подписка из «Мои ключи» не входит в лимит. Удалите устройство, чтобы освободить место.",
        "Der Abo-Link aus Meine Schlüssel zählt nicht mit. Lösche ein Gerät, um einen Platz freizumachen.",
    ),
    S("Delete {name}? It stops connecting.", "Удалить «{name}»? Оно перестанет подключаться.", "{name} löschen? Es verbindet sich dann nicht mehr."),
    S("Device deleted.", "Устройство удалено.", "Gerät gelöscht."),
    // Notifications
    S(
        "Notifications are on. We remind you 3 days and 1 day before the subscription ends, when it ends, and confirm payments.",
        "Уведомления включены. Напомним за 3 дня и за 1 день до конца подписки, сообщим, когда она закончится, и подтвердим оплату.",
        "Benachrichtigungen sind an. Wir erinnern dich 3 Tage und 1 Tag vor Ablauf des Abos, beim Ablauf und bestätigen Zahlungen.",
    ),
    S(
        "Notifications are off. You won't get reminders before the subscription ends.",
        "Уведомления выключены. Напоминаний перед концом подписки не будет.",
        "Benachrichtigungen sind aus. Du bekommst keine Erinnerungen vor Ablauf des Abos.",
    ),
    S("Turn off", "Выключить", "Ausschalten"),
    S("Turn on", "Включить", "Einschalten"),
    S("Turn off notifications", "Отключить уведомления", "Benachrichtigungen ausschalten"),
    S(
        "Your subscription has ended. Renew it to keep using the VPN.",
        "Подписка закончилась. Продлите её, чтобы VPN продолжал работать.",
        "Dein Abo ist abgelaufen. Verlängere es, um das VPN weiter zu nutzen.",
    ),
    S("Your subscription ends on {date}.", "Подписка закончится {date}.", "Dein Abo endet am {date}."),
    S("{n} day left|{n} days left", "Остался {n} день|Осталось {n} дня|Осталось {n} дней", "Noch {n} Tag|Noch {n} Tage"),
    // Support
    S("Support chat isn't set up yet. Please try later.", "Чат поддержки пока не настроен. Попробуйте позже.", "Der Support-Chat ist noch nicht eingerichtet. Bitte versuche es später."),
    S(
        "Write your question in one message. You can attach a screenshot. We answer here.",
        "Напишите вопрос одним сообщением. Можно приложить скриншот. Ответим здесь.",
        "Schreib deine Frage in einer Nachricht. Du kannst einen Screenshot anhängen. Wir antworten hier.",
    ),
    S("Support request", "Обращение в поддержку", "Support-Anfrage"),
    S("Reply to this message to answer the user.", "Ответьте на это сообщение (reply), чтобы ответить пользователю.", "Antworte auf diese Nachricht, um dem Nutzer zu antworten."),
    S("Couldn't send the message to support. Please try again later.", "Не удалось отправить сообщение в поддержку. Попробуйте позже.", "Die Nachricht konnte nicht an den Support gesendet werden. Bitte versuche es später."),
    S("Sent to support. The answer comes here.", "Отправлено в поддержку. Ответ придёт сюда.", "An den Support gesendet. Die Antwort kommt hier."),
    S("Reply", "Ответить", "Antworten"),
    S("Support answer", "Ответ поддержки", "Antwort vom Support"),
    S("Sent to the user.", "Отправлено пользователю.", "An den Nutzer gesendet."),
    S("Couldn't deliver the answer. The user may have blocked the bot.", "Не удалось доставить ответ. Возможно, пользователь заблокировал бота.", "Die Antwort konnte nicht zugestellt werden. Der Nutzer hat den Bot vielleicht blockiert."),
    // Sign-in and linking
    S(
        "Open this link to sign in on the website. It works once, within 10 minutes.",
        "Откройте ссылку, чтобы войти на сайт. Она работает один раз в течение 10 минут.",
        "Öffne diesen Link, um dich auf der Website anzumelden. Er funktioniert einmal, innerhalb von 10 Minuten.",
    ),
    S("Telegram is linked to {email}. Sign in with either of them.", "Telegram привязан к {email}. Входить можно любым из них.", "Telegram ist mit {email} verknüpft. Du kannst dich mit beidem anmelden."),
    S("Telegram is already linked to {email}.", "Telegram уже привязан к {email}.", "Telegram ist bereits mit {email} verknüpft."),
    S(
        "This link is used or has expired. Get a new one in your account on the website.",
        "Ссылка уже использована или устарела. Получите новую в личном кабинете на сайте.",
        "Dieser Link wurde schon benutzt oder ist abgelaufen. Hol dir einen neuen in deinem Konto auf der Website.",
    ),
    S(
        "Another Telegram is linked to {email}. Unlink it in your account on the website first.",
        "К {email} уже привязан другой Telegram. Сначала отвяжите его в личном кабинете на сайте.",
        "Mit {email} ist schon ein anderes Telegram verknüpft. Trenne es zuerst in deinem Konto auf der Website.",
    ),
    S("This Telegram is linked to another account. Unlink it there first.", "Этот Telegram привязан к другому аккаунту. Сначала отвяжите его там.", "Dieses Telegram ist mit einem anderen Konto verknüpft. Trenne es zuerst dort."),
    S(
        "This Telegram has its own account with a subscription. Write to support and we will move it.",
        "У этого Telegram есть свой аккаунт с подпиской. Напишите в поддержку, и мы перенесём её.",
        "Dieses Telegram hat ein eigenes Konto mit Abo. Schreib dem Support, wir übertragen es.",
    ),
    S("Telegram sign-in is off", "Вход через Telegram выключен", "Die Anmeldung mit Telegram ist aus"),
    S("Telegram sign-in failed, try again", "Не удалось войти через Telegram, попробуйте ещё раз", "Anmeldung mit Telegram fehlgeschlagen, versuche es erneut"),
    S("This account signs in only with Telegram", "В этот аккаунт можно войти только через Telegram", "Dieses Konto meldet sich nur mit Telegram an"),
    // Website account page
    S("Link Telegram", "Привязать Telegram", "Telegram verknüpfen"),
    S(
        "Link Telegram to sign in with it and to get reminders, keys and support in the bot.",
        "Привяжите Telegram, чтобы входить через него и получать напоминания, ключи и поддержку в боте.",
        "Verknüpfe Telegram, um dich damit anzumelden und Erinnerungen, Schlüssel und Support im Bot zu bekommen.",
    ),
    S("Linked to {name}", "Привязан к {name}", "Verknüpft mit {name}"),
    S("Reminders, keys and support are in the bot.", "Напоминания, ключи и поддержка в боте.", "Erinnerungen, Schlüssel und Support gibt es im Bot."),
    S("Open Telegram", "Открыть Telegram", "Telegram öffnen"),
    S("Tap Start in the bot. The link works once, within 15 minutes.", "Нажмите «Запустить» в боте. Ссылка работает один раз в течение 15 минут.", "Tippe im Bot auf Starten. Der Link funktioniert einmal, innerhalb von 15 Minuten."),
    S("Unlink", "Отвязать", "Trennen"),
    S("Unlink Telegram from this account?", "Отвязать Telegram от этого аккаунта?", "Telegram von diesem Konto trennen?"),
    S("This account is blocked. To find out why, write to support.", "Аккаунт заблокирован. Чтобы узнать причину, напишите в поддержку.", "Dieses Konto ist gesperrt. Schreib dem Support, um den Grund zu erfahren."),
)
