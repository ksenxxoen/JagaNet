package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** The Telegram mini app (issue #87): its screens, its sign-in and the bot's "Open Boop" buttons. */
internal val miniAppStrings: List<S> = listOf(
    // Tabs and screens
    S("Community", "Сообщество", "Community"),
    S("News and chat with other users", "Новости и общение с другими пользователями", "Neuigkeiten und Austausch mit anderen Nutzern"),
    S("Home", "Главная", "Start"),
    S("Profile", "Профиль", "Profil"),
    S("Hi, {name}", "Привет, {name}", "Hallo, {name}"),
    S("{used} of {limit}", "{used} из {limit}", "{used} von {limit}"),
    S("Choose a plan to keep using the VPN", "Выберите тариф, чтобы продолжить пользоваться VPN", "Wähle einen Tarif, um das VPN weiter zu nutzen"),
    S("The free plan works in the Boop app", "Бесплатный тариф работает в приложении Boop", "Der kostenlose Tarif funktioniert in der Boop-App"),
    S("Subscription key, not counted in the limit", "Ключ подписки не входит в лимит", "Der Abo-Schlüssel zählt nicht zum Limit"),
    S("A new payment adds to the current one", "Новая оплата добавится к текущей", "Eine neue Zahlung kommt zur aktuellen hinzu"),
    S("Happ or v2RayTun, Hiddify works too", "Happ или v2RayTun, подойдёт и Hiddify", "Happ oder v2RayTun, Hiddify geht auch"),
    S("Tap Connect and add the subscription in one tap", "Нажмите «Подключить» и добавьте подписку в одно касание", "Tippe auf Verbinden und füge das Abo mit einem Tippen hinzu"),
    S("Turn it on in the app", "Включите его в приложении", "Schalte es in der App ein"),
    S("We answer in the chat with the bot", "Отвечаем в чате с ботом", "Wir antworten im Chat mit dem Bot"),
    // Connection
    S("The Boop app", "Приложение Boop", "Die Boop-App"),
    S("Our own app, connects in one tap", "Наше приложение, подключается в одно касание", "Unsere eigene App, verbindet mit einem Tippen"),
    S("Scan it in the app from another phone", "Отсканируйте в приложении на другом телефоне", "Scanne ihn in der App auf einem anderen Handy"),
    S("Add in one tap", "Добавить в одно касание", "Mit einem Tippen hinzufügen"),
    S("Add to {app}", "Добавить в {app}", "Zu {app} hinzufügen"),
    S("Opens the app with your subscription", "Откроет приложение с вашей подпиской", "Öffnet die App mit deinem Abo"),
    S("No app yet?", "Ещё нет приложения?", "Noch keine App?"),
    S("Links, QR codes and steps", "Ссылки, QR-коды и инструкция", "Links, QR-Codes und Anleitung"),
    S("No key yet", "Ключа пока нет", "Noch kein Schlüssel"),
    S("Keys come with a subscription", "Ключ появится вместе с подпиской", "Schlüssel gibt es mit dem Abo"),
    S("Couldn't copy, select the link and copy it", "Не получилось скопировать, выделите ссылку и скопируйте её", "Kopieren hat nicht geklappt, markiere den Link und kopiere ihn"),
    // Devices
    S("Connected devices", "Подключённые устройства", "Verbundene Geräte"),
    S("Key for other apps","Ключ для других приложений", "Schlüssel für andere Apps"),
    S("Devices in the plan", "Устройства по тарифу", "Geräte im Tarif"),
    S("Delete a device to free a place.", "Удалите устройство, чтобы освободить место.", "Lösche ein Gerät, um einen Platz frei zu machen."),
    S("Subscription key", "Ключ подписки", "Abo-Schlüssel"),
    S("It comes with your access and can't be deleted.", "Он входит в ваш доступ, удалить его нельзя.", "Er gehört zu deinem Zugang und kann nicht gelöscht werden."),
    // Payment
    S("{price} a month", "{price} в месяц", "{price} im Monat"),
    S("Pro works on all your devices, with every location", "Pro работает на всех ваших устройствах и во всех локациях", "Pro funktioniert auf all deinen Geräten und an jedem Standort"),
    S("Payment opens in the browser. Come back here after paying.", "Оплата откроется в браузере. После оплаты вернитесь сюда.", "Die Zahlung öffnet sich im Browser. Komm danach hierher zurück."),
    S("Pro is active until {date}", "Pro действует до {date}", "Pro ist aktiv bis {date}"),
    S("Waiting for the payment", "Ждём оплату", "Wir warten auf die Zahlung"),
    S("Open the payment page again", "Открыть оплату ещё раз", "Zahlungsseite erneut öffnen"),
    S("Check the payment", "Проверить оплату", "Zahlung prüfen"),
    S("Choose another plan", "Выбрать другой тариф", "Anderen Tarif wählen"),
    // Support
    S("Write to us, we answer every message", "Напишите нам, мы отвечаем на каждое сообщение", "Schreib uns, wir antworten auf jede Nachricht"),
    S("Write in the bot", "Написать в бот", "Im Bot schreiben"),
    S(
        "Describe the problem in one message, you can attach a screenshot. We answer in the chat.",
        "Опишите проблему одним сообщением, можно приложить скриншот. Ответим в чате.",
        "Beschreib das Problem in einer Nachricht, gern mit Screenshot. Wir antworten im Chat.",
    ),
    S("Or by e-mail", "Или по почте", "Oder per E-Mail"),
    // Profile
    S("Your invite link", "Ваша ссылка-приглашение", "Dein Einladungslink"),
    S("Reminders before the subscription ends and payment receipts", "Напоминания о конце подписки и чеки об оплате", "Erinnerungen vor dem Aboende und Zahlungsbestätigungen"),
    S("A one-time link, works for 10 minutes", "Одноразовая ссылка, действует 10 минут", "Ein Einmal-Link, 10 Minuten gültig"),
    S("Notifications are on", "Уведомления включены", "Benachrichtigungen sind an"),
    S("Notifications are off", "Уведомления выключены", "Benachrichtigungen sind aus"),
    // Sign-in
    S("To find out why, write to support.", "Чтобы узнать причину, напишите в поддержку.", "Um den Grund zu erfahren, schreib dem Support."),
    S("Boop is unavailable right now", "Boop сейчас недоступен", "Boop ist gerade nicht verfügbar"),
    S("Try again in a few minutes.", "Попробуйте через несколько минут.", "Versuch es in ein paar Minuten noch einmal."),
    S("Couldn't sign in", "Не удалось войти", "Anmeldung fehlgeschlagen"),
    S("Close the mini app and open it again from the bot.", "Закройте мини-приложение и откройте его снова из бота.", "Schließ die Mini-App und öffne sie erneut über den Bot."),
    S("The Telegram mini app is off", "Мини-приложение Telegram выключено", "Die Telegram-Mini-App ist ausgeschaltet"),
    S("Bad request", "Неверный запрос", "Ungültige Anfrage"),
    S("Unknown language", "Неизвестный язык", "Unbekannte Sprache"),
    // Outside Telegram, import links
    S("Open Boop in Telegram", "Откройте Boop в Telegram", "Öffne Boop in Telegram"),
    S("This page works inside our Telegram bot. Open the bot and tap Open.", "Эта страница работает внутри нашего Telegram-бота. Откройте бота и нажмите «Открыть».", "Diese Seite funktioniert in unserem Telegram-Bot. Öffne den Bot und tippe auf Öffnen."),
    S("Go to the website", "Перейти на сайт", "Zur Website"),
    S("Opening the app", "Открываем приложение", "Die App wird geöffnet"),
    S("If the app didn't open, tap the button. No app yet? Install it first.", "Если приложение не открылось, нажмите кнопку. Нет приложения? Сначала установите его.", "Falls die App nicht aufgeht, tippe auf den Button. Noch keine App? Installiere sie zuerst."),
    S("Open the app", "Открыть приложение", "App öffnen"),
    S("This link doesn't work. Go back to Telegram and try again.", "Эта ссылка не работает. Вернитесь в Telegram и попробуйте ещё раз.", "Dieser Link funktioniert nicht. Geh zurück zu Telegram und versuch es noch einmal."),
    // The new mini app's preview (/tg/app-next) on a server built without web/
    S("The new mini app is not built into this server", "Новое мини-приложение не встроено в этот сервер", "Die neue Mini-App ist nicht in diesen Server eingebaut"),
    // The bot
    S("Open Boop", "Открыть Boop", "Boop öffnen"),
    S("A new device was connected to your subscription, {name}.", "К вашей подписке подключено новое устройство, {name}.", "Ein neues Gerät wurde mit deinem Abo verbunden, {name}."),
    S("If it wasn't you, delete it and write to support.", "Если это были не вы, удалите его и напишите в поддержку.", "Falls du das nicht warst, lösche es und schreib dem Support."),
)
