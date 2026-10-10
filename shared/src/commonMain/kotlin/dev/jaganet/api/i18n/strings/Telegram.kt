package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** The Telegram bot. */
internal val telegramStrings: List<S> = listOf(
    // Command menu
    S("Plans and menu", "Тарифы и меню", "Tarife und Menü"),
    S("My VPN key", "Мой VPN-ключ", "Mein VPN-Schlüssel"),
    S("Get the app", "Скачать приложение", "App holen"),
    S("My subscription", "Моя подписка", "Mein Abo"),

    S(
        "JagaNet VPN is fast and secure. It encrypts all your traffic.",
        "JagaNet VPN быстрый и безопасный. Он шифрует весь ваш трафик.",
        "JagaNet VPN ist schnell und sicher. Es verschlüsselt deinen gesamten Datenverkehr.",
    ),
    S(
        "It works in the JagaNet app and in other VPN apps.",
        "Работает в приложении JagaNet и в других VPN-приложениях.",
        "Es funktioniert in der JagaNet-App und in anderen VPN-Apps.",
    ),
    S("✅ Your Pro is active until {date}.", "✅ Ваш Pro действует до {date}.", "✅ Dein Pro ist aktiv bis {date}."),
    S("🔑 My VPN key", "🔑 Мой VPN-ключ", "🔑 Mein VPN-Schlüssel"),
    S("📱 Get the app", "📱 Скачать приложение", "📱 App holen"),
    S("Choose a language", "Выберите язык", "Wähle eine Sprache"),
    S("Sorry, buying isn't available right now.", "Извините, покупка сейчас недоступна.", "Kaufen ist gerade leider nicht möglich."),
    S("{product} for {price}", "{product} за {price}", "{product} für {price}"),
    S(
        "Tap the button to pay. Your VPN key arrives here right after.",
        "Нажмите кнопку, чтобы оплатить. VPN-ключ придёт сюда сразу после оплаты.",
        "Tippe auf den Button, um zu bezahlen. Dein VPN-Schlüssel kommt direkt danach hierher.",
    ),
    S("Pay {price}", "Оплатить {price}", "{price} bezahlen"),
    S(
        "You don't have an active subscription yet. Choose a plan.",
        "У вас пока нет активной подписки. Выберите тариф.",
        "Du hast noch kein aktives Abo. Wähle einen Tarif.",
    ),
    S("Couldn't make a key.", "Не удалось создать ключ.", "Der Schlüssel konnte nicht erstellt werden."),
    S("🔑 Your VPN key", "🔑 Ваш VPN-ключ", "🔑 Dein VPN-Schlüssel"),
    S(
        "📱 Install the JagaNet app, tap Sign in with a device code and enter this code",
        "📱 Установите приложение JagaNet, нажмите «Войти по коду устройства» и введите этот код",
        "📱 Installiere die JagaNet-App, tippe auf „Mit Gerätecode anmelden“ und gib diesen Code ein",
    ),
    S(
        "The code works for 10 minutes. The app uses this same subscription.",
        "Код действует 10 минут. Приложение будет использовать эту же подписку.",
        "Der Code gilt 10 Minuten. Die App nutzt dasselbe Abo.",
    ),
    S(
        "Prefer another app? AmneziaVPN works with your key from /key.",
        "Хотите другое приложение? AmneziaVPN работает с вашим ключом из /key.",
        "Lieber eine andere App? AmneziaVPN funktioniert mit deinem Schlüssel aus /key.",
    ),
    S("No active subscription.", "Нет активной подписки.", "Kein aktives Abo."),
    S("✅ Pro until {date}.", "✅ Pro до {date}.", "✅ Pro bis {date}."),
    S("{n} VPN key|{n} VPN keys", "{n} VPN-ключ|{n} VPN-ключа|{n} VPN-ключей", "{n} VPN-Schlüssel|{n} VPN-Schlüssel"),
    S("Extend", "Продлить", "Verlängern"),
    S("✅ Payment received. Pro is active until {date}.", "✅ Оплата получена. Pro действует до {date}.", "✅ Zahlung erhalten. Pro ist aktiv bis {date}."),
    S("Tap /key to get your VPN key.", "Нажмите /key, чтобы получить VPN-ключ.", "Tippe auf /key, um deinen VPN-Schlüssel zu bekommen."),
    S("That key was deleted. Tap /key for a new one.", "Этот ключ удалён. Нажмите /key, чтобы получить новый.", "Dieser Schlüssel wurde gelöscht. Tippe auf /key für einen neuen."),
    S(
        "1. Install AmneziaVPN (button below) or our JagaNet app.",
        "1. Установите AmneziaVPN (кнопка ниже) или наше приложение JagaNet.",
        "1. Installiere AmneziaVPN (Button unten) oder unsere JagaNet-App.",
    ),
    S(
        "2. Scan this QR code in the app from another screen or import the {file} file below.",
        "2. Отсканируйте этот QR-код в приложении с другого экрана или импортируйте файл {file} ниже.",
        "2. Scanne diesen QR-Code in der App von einem anderen Bildschirm oder importiere die Datei {file} unten.",
    ),
    S("3. Connect.", "3. Подключитесь.", "3. Verbinde dich."),
    S(
        "Keep the key private. Anyone who has it uses your subscription.",
        "Никому не передавайте ключ. Любой, у кого он есть, пользуется вашей подпиской.",
        "Halte den Schlüssel privat. Wer ihn hat, nutzt dein Abo.",
    ),
    S("Open key page", "Открыть страницу ключа", "Schlüsselseite öffnen"),

    // Referral program
    S("Invite friends", "Пригласить друзей", "Freunde einladen"),
    S("🎁 Invite friends", "🎁 Пригласить друзей", "🎁 Freunde einladen"),
    S("Your link", "Ваша ссылка", "Dein Link"),
    S("Link to this bot", "Ссылка на этого бота", "Link zu diesem Bot"),
    S("Clicks {n}", "Переходы {n}", "Klicks {n}"),
    S("Sign-ups {n}", "Регистрации {n}", "Anmeldungen {n}"),
    S("Paid {n}", "Оплатили {n}", "Bezahlt {n}"),
    S(
        "Detailed statistics and extra links for each campaign are in your account on the website.",
        "Подробная статистика и отдельные ссылки для каждой кампании есть в личном кабинете на сайте.",
        "Detaillierte Statistiken und eigene Links für jede Kampagne findest du in deinem Konto auf der Website.",
    ),
    S("Open statistics", "Открыть статистику", "Statistik öffnen"),

    // Sign-in link
    S("Sign in on the website", "Войти на сайте", "Auf der Website anmelden"),
    S("Open this link to sign in on the website. It works once, within 15 minutes.", "Откройте ссылку, чтобы войти на сайте. Она сработает один раз в течение 15 минут.", "Öffne diesen Link, um dich auf der Website anzumelden. Er funktioniert einmal, innerhalb von 15 Minuten."),
)
