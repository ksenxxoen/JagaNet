package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

internal val homeStrings: List<S> = listOf(
    // Header and power button
    S("Get Pro", "Получить Pro", "Pro holen"),
    S("Tap to disconnect", "Нажмите, чтобы отключить", "Tippen zum Trennen"),
    S("Tap to connect", "Нажмите, чтобы подключить", "Tippen zum Verbinden"),
    S("Connecting…", "Подключение…", "Verbinde…"),
    S("Disconnecting…", "Отключение…", "Trenne…"),
    S("Connect", "Подключить", "Verbinden"),
    S("Disconnect", "Отключить", "Trennen"),
    S("Simulated tunnel. No real traffic is routed.", "Туннель симулирован, реальный трафик не передаётся.", "Simulierter Tunnel, es wird kein echter Datenverkehr geleitet."),

    // Status
    S("Protected", "Защищено", "Geschützt"),
    S("Connecting", "Подключение", "Verbinde"),
    S("Couldn’t connect", "Не удалось подключиться", "Verbindung fehlgeschlagen"),
    S("Not protected", "Не защищено", "Nicht geschützt"),

    // Blocked banner
    S("You’ve used this month’s free data.", "Бесплатный трафик на этот месяц закончился.", "Dein kostenloses Datenvolumen für diesen Monat ist aufgebraucht."),
    S("Get unlimited", "Безлимит", "Unbegrenzt"),
    S("Your plan’s device limit is reached.", "Достигнут лимит устройств вашего тарифа.", "Das Gerätelimit deines Tarifs ist erreicht."),
    S("See plans", "Тарифы", "Tarife"),

    // Location and connection details
    S("Server location", "Локация сервера", "Serverstandort"),
    S("Download", "Загрузка", "Download"),
    S("Upload", "Отдача", "Upload"),
    S("Mbps", "Мбит/с", "Mbit/s"),
    S("Protocol", "Протокол", "Protokoll"),
    S("Location", "Локация", "Standort"),
    S("Tunnel address", "Адрес туннеля", "Tunneladresse"),
    S("DNS", "DNS", "DNS"),
    S("This session", "Эта сессия", "Diese Sitzung"),

    // Plan cards
    S("Free plan", "Бесплатный тариф", "Kostenloser Tarif"),
    S("{amount} left this month", "Осталось {amount} в этом месяце", "Noch {amount} diesen Monat"),
    S("Unlimited data, 5 devices", "Безлимитный трафик, 5 устройств", "Unbegrenztes Datenvolumen, 5 Geräte"),
    S("Upgrade", "Улучшить", "Upgrade"),
    S("Pro, unlimited data", "Pro, безлимитный трафик", "Pro, unbegrenztes Datenvolumen"),
    S("Renews {date}", "Продление {date}", "Verlängert sich am {date}"),
    S("Until {date}", "До {date}", "Bis {date}"),

    // Protocols
    S("WireGuard speed, disguised so networks that block VPNs can’t spot it.", "Скорость WireGuard и маскировка от сетей, которые блокируют VPN.", "WireGuard-Tempo, getarnt vor Netzen, die VPNs blockieren."),
    S("Plain WireGuard. Slightly lighter, but easy for censors to detect.", "Обычный WireGuard. Чуть легче, но цензорам легко его распознать.", "Normales WireGuard. Etwas schlanker, aber für Zensoren leicht zu erkennen."),
    S("Example of a custom protocol plugged into JagaNet.", "Пример своего протокола, подключённого к JagaNet.", "Beispiel für ein eigenes Protokoll in JagaNet."),
    S("Custom protocol", "Свой протокол", "Eigenes Protokoll"),

    // Connect errors
    S("VPN permission was not granted", "Разрешение на VPN не получено", "VPN-Berechtigung wurde nicht erteilt"),
    S("No server available", "Нет доступных серверов", "Kein Server verfügbar"),
    S("No common protocol with {server}", "Нет общего протокола с сервером {server}", "Kein gemeinsames Protokoll mit {server}"),

    // Tunnel log (Logs screen)
    S("Starting {protocol} tunnel to {location}", "Запуск туннеля {protocol}, локация {location}", "Starte {protocol}-Tunnel nach {location}"),
    S("Kill switch armed", "Kill switch включён", "Kill Switch aktiv"),
    S("Obfuscation on: {packets}, padded handshake, custom headers", "Обфускация включена: {packets}, дополненное рукопожатие, свои заголовки", "Verschleierung an: {packets}, aufgefüllter Handshake, eigene Header"),
    S("{n} junk packet|{n} junk packets", "{n} мусорный пакет|{n} мусорных пакета|{n} мусорных пакетов", "{n} Füllpaket|{n} Füllpakete"),
    S("Handshake completed with {endpoint}", "Рукопожатие с {endpoint} выполнено", "Handshake mit {endpoint} abgeschlossen"),
    S("Tunnel up, address {address}", "Туннель поднят, адрес {address}", "Tunnel aktiv, Adresse {address}"),
    S("DNS set to {dns}", "DNS установлен на {dns}", "DNS gesetzt auf {dns}"),
    S("Tunnel down", "Туннель остановлен", "Tunnel beendet"),
)
