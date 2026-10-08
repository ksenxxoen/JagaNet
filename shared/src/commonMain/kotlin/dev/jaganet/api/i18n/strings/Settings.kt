package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

internal val settingsStrings: List<S> = listOf(
    // Settings
    S("Invite friends, get free Pro time", "Приглашайте друзей и получайте Pro бесплатно", "Lade Freunde ein und erhalte Pro gratis"),
    S("Connection", "Подключение", "Verbindung"),
    S("Kill switch", "Kill switch", "Kill Switch"),
    S("Route all traffic through the VPN while it’s on", "Весь трафик идет через VPN, пока он включен", "Der ganze Datenverkehr läuft über das VPN, solange es an ist"),
    S("Block internet if the VPN drops", "Блокировать интернет, если VPN отключится", "Internet sperren, wenn das VPN abbricht"),
    S("Auto-connect", "Автоподключение", "Automatisch verbinden"),
    S("On Wi-Fi networks you don’t trust", "В ненадежных сетях Wi-Fi", "In WLANs, denen du nicht vertraust"),
    S("Split tunneling", "Раздельное туннелирование", "Split-Tunneling"),
    S(
        "Not available on iPhone: iOS doesn’t allow per-app VPN outside managed devices",
        "Недоступно на iPhone: iOS не разрешает VPN для отдельных приложений на неуправляемых устройствах",
        "Auf dem iPhone nicht verfügbar: iOS erlaubt App-VPN nur auf verwalteten Geräten",
    ),
    S("Off", "Выкл.", "Aus"),
    S("{n} app|{n} apps", "{n} приложение|{n} приложения|{n} приложений", "{n} App|{n} Apps"),
    S("App", "Приложение", "App"),
    S("Notifications", "Уведомления", "Benachrichtigungen"),
    S("Disconnects and billing reminders", "Обрывы связи и напоминания об оплате", "Verbindungsabbrüche und Zahlungserinnerungen"),
    S("Start on boot", "Запуск при включении", "Beim Gerätestart starten"),
    S("Reconnect after the phone restarts", "Подключаться снова после перезагрузки телефона", "Nach dem Neustart des Handys neu verbinden"),
    S("Owner", "Владелец", "Inhaber"),
    S("Business dashboard", "Панель владельца", "Inhaber-Dashboard"),
    S("Revenue, users and server health", "Выручка, пользователи и состояние серверов", "Umsatz, Nutzer und Serverzustand"),
    S("Help", "Помощь", "Hilfe"),
    S("Contact support", "Написать в поддержку", "Support kontaktieren"),
    S("Usually replies in a day", "Обычно отвечаем за день", "Antwort meist innerhalb eines Tages"),
    S("Connection log", "Журнал подключения", "Verbindungsprotokoll"),
    S("Privacy policy", "Политика конфиденциальности", "Datenschutzerklärung"),
    S("Terms of service", "Условия использования", "Nutzungsbedingungen"),

    // Protocol
    S(
        "How this device talks to the server. Automatic picks the best one both support.",
        "Как устройство связывается с сервером. Автоматический режим выберет лучший протокол, который поддерживают оба.",
        "Wie dieses Gerät mit dem Server spricht. Automatisch wählt das beste Protokoll, das beide können.",
    ),
    S("Recommended", "Рекомендуется", "Empfohlen"),
    S("Not supported on this device yet", "Пока не поддерживается на этом устройстве", "Auf diesem Gerät noch nicht unterstützt"),
    S("Changes apply the next time you connect.", "Изменения вступят в силу при следующем подключении.", "Änderungen gelten ab der nächsten Verbindung."),
    // Split tunneling
    S(
        "Choose which apps use the tunnel. Leave out apps that don’t need protection, like local banking or streaming.",
        "Выберите, какие приложения идут через туннель. Исключите те, которым защита не нужна, например местный банк или стриминг.",
        "Wähle, welche Apps den Tunnel nutzen. Lass Apps weg, die keinen Schutz brauchen, etwa lokales Banking oder Streaming.",
    ),
    S("All apps use the VPN", "Все приложения через VPN", "Alle Apps nutzen das VPN"),
    S("Only selected apps", "Только выбранные приложения", "Nur ausgewählte Apps"),
    S("All except selected apps", "Все, кроме выбранных", "Alle außer ausgewählten Apps"),
    S("Every app goes through your server.", "Все приложения идут через ваш сервер.", "Jede App läuft über deinen Server."),
    S("Apps that use the VPN", "Приложения через VPN", "Apps mit VPN"),
    S("Apps that skip the VPN", "Приложения в обход VPN", "Apps ohne VPN"),

    // Connection log
    S("Kept only on this device.", "Хранится только на этом устройстве.", "Wird nur auf diesem Gerät gespeichert."),
    S("All", "Все", "Alle"),
    S("Warnings", "Предупреждения", "Warnungen"),
    S("Errors", "Ошибки", "Fehler"),
    S("Nothing logged yet. Connect to see tunnel events.", "Пока пусто. Подключитесь, чтобы увидеть события туннеля.", "Noch keine Einträge. Verbinde dich, um Tunnel-Ereignisse zu sehen."),
    S("Share", "Поделиться", "Teilen"),
)
