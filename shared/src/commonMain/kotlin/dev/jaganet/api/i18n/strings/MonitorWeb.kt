package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** The website's server status page (owners). */
internal val monitorWebStrings: List<S> = listOf(
    S("Server status", "Состояние сервера", "Serverstatus"),
    S("1 hour", "1 час", "1 Stunde"),
    S("24 hours", "24 часа", "24 Stunden"),
    S("Everything works", "Всё работает", "Alles funktioniert"),
    S("Something needs attention", "Кое-что требует внимания", "Etwas braucht deine Aufmerksamkeit"),
    S("There is a problem", "Есть проблема", "Es gibt ein Problem"),
    S("State unknown", "Состояние неизвестно", "Status unbekannt"),
    S("Checked at {time}", "Проверено {time}", "Geprüft am {time}"),
    S("Not checked yet", "Ещё не проверялось", "Noch nicht geprüft"),

    // Check levels
    S("OK", "Норма", "OK"),
    S("Problem", "Проблема", "Problem"),
    S("Unknown", "Неизвестно", "Unbekannt"),

    // Check names
    S("VPN", "VPN", "VPN"),
    S("Database", "База данных", "Datenbank"),
    S("Website (HTTPS)", "Сайт (HTTPS)", "Website (HTTPS)"),
    S("Certificate", "Сертификат", "Zertifikat"),
    S("Telegram bot", "Telegram-бот", "Telegram-Bot"),
    S("Processor", "Процессор", "Prozessor"),
    S("Disk", "Диск", "Festplatte"),
    S("Channel load", "Загрузка канала", "Kanalauslastung"),
    S("Ping and packet loss", "Пинг и потери пакетов", "Ping und Paketverlust"),

    // Network
    S("Network", "Сеть", "Netzwerk"),
    S("Download speed", "Скорость загрузки", "Download-Geschwindigkeit"),
    S("Upload speed", "Скорость отдачи", "Upload-Geschwindigkeit"),
    S("Capacity {speed}, reported by the network card", "Канал {speed}, по данным сетевой карты", "Kanal {speed}, laut Netzwerkkarte"),
    S("Capacity unknown", "Ширина канала неизвестна", "Kanalkapazität unbekannt"),
    S("{used} of {limit} allowed by the hosting plan", "{used} из {limit} по тарифу хостинга", "{used} von {limit} laut Hosting-Tarif"),
    S("Interface {name}", "Интерфейс {name}", "Schnittstelle {name}"),
    S("Throughput", "Скорость канала", "Durchsatz"),
    S("Load", "Загрузка", "Auslastung"),
    S("Channel load, %", "Загрузка канала, %", "Kanalauslastung, %"),
    S("Ping", "Пинг", "Ping"),
    S("Ping, ms", "Пинг, мс", "Ping, ms"),
    S("{n} ms", "{n} мс", "{n} ms"),
    S("Packet loss", "Потери пакетов", "Paketverlust"),
    S("Packet loss, %", "Потери пакетов, %", "Paketverlust, %"),
    S("Drops", "Отброшено", "Verworfen"),
    S("Errors and drops", "Ошибки и отброшенные пакеты", "Fehler und verworfene Pakete"),

    // Resources and VPN
    S("Resources", "Ресурсы", "Ressourcen"),
    S("Disk free", "Свободно на диске", "Frei auf der Festplatte"),
    S("{used} of {total}", "{used} из {total}", "{used} von {total}"),
    S("Processor and memory, %", "Процессор и память, %", "Prozessor und Arbeitsspeicher, %"),
    S("Keys on the server", "Ключей на сервере", "Schlüssel auf dem Server"),
    S("Devices online", "Устройства в сети", "Geräte online"),

    // Alerts
    S("Alerts", "Оповещения", "Warnmeldungen"),
    S("Level", "Уровень", "Stufe"),
    S("Message", "Сообщение", "Meldung"),
    S("Started", "Началось", "Begonnen"),
    S("Ended", "Закончилось", "Beendet"),
    S("Duration", "Длительность", "Dauer"),
    S("No open alerts.", "Активных оповещений нет.", "Keine offenen Warnmeldungen."),
    S("Resolved alerts", "Завершённые оповещения", "Erledigte Warnmeldungen"),
    S("{n} day|{n} days", "{n} день|{n} дня|{n} дней", "{n} Tag|{n} Tage"),

    // Notifications
    S("Where alerts are sent", "Куда приходят оповещения", "Wohin Warnmeldungen gehen"),
    S("No email addresses", "Адресов e-mail нет", "Keine E-Mail-Adressen"),
    S(
        "E-mail sending is not set up yet. Set SMTP_* in /etc/jaganet/env.",
        "Отправка e-mail ещё не настроена. Укажите SMTP_* в /etc/jaganet/env.",
        "Der E-Mail-Versand ist noch nicht eingerichtet. Setze SMTP_* in /etc/jaganet/env.",
    ),
    S("{n} Telegram chat|{n} Telegram chats", "{n} чат Telegram|{n} чата Telegram|{n} чатов Telegram", "{n} Telegram-Chat|{n} Telegram-Chats"),
    S("Send test notification", "Отправить тестовое уведомление", "Testbenachrichtigung senden"),
    S("Sent", "Отправлено", "Gesendet"),
)
