package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** Server monitoring: check names and messages, alert e-mails and Telegram notices; sign-in e-mail. */
internal val monitorStrings: List<S> = listOf(
    // Check names
    S("VPN", "VPN", "VPN"),
    S("Database", "База данных", "Datenbank"),
    S("Website (HTTPS)", "Сайт (HTTPS)", "Website (HTTPS)"),
    S("Certificate", "Сертификат", "Zertifikat"),
    S("Telegram bot", "Telegram-бот", "Telegram-Bot"),
    S("Processor", "Процессор", "Prozessor"),
    S("Memory", "Память", "Arbeitsspeicher"),
    S("Disk", "Диск", "Festplatte"),
    S("Channel load", "Загрузка канала", "Kanalauslastung"),
    S("Network errors", "Ошибки сети", "Netzwerkfehler"),
    S("Ping and packet loss", "Пинг и потери пакетов", "Ping und Paketverlust"),
    S("DNS", "DNS", "DNS"),
    S("Monthly traffic", "Трафик за месяц", "Datenvolumen im Monat"),

    // Check messages
    S("Running, {peers} devices, {online} online now", "Работает, устройств {peers}, сейчас в сети {online}", "Läuft, {peers} Geräte, {online} jetzt online"),
    S("The VPN interface doesn't answer", "VPN-интерфейс не отвечает", "Die VPN-Schnittstelle antwortet nicht"),
    S("Working", "Работает", "Funktioniert"),
    S("The database doesn't answer", "База данных не отвечает", "Die Datenbank antwortet nicht"),
    S("Opens", "Открывается", "Erreichbar"),
    S("The website doesn't open", "Сайт не открывается", "Die Website ist nicht erreichbar"),
    S("Can't read the certificate", "Не удалось прочитать сертификат", "Das Zertifikat lässt sich nicht lesen"),
    S("Valid for {n} more days", "Осталось дней {n}", "Noch {n} Tage gültig"),
    S("The bot can't reach Telegram", "Бот не может связаться с Telegram", "Der Bot erreicht Telegram nicht"),
    S("Load {p}%", "Нагрузка {p}%", "Auslastung {p}%"),
    S("{used} of {total} used", "Занято {used} из {total}", "{used} von {total} belegt"),
    S("{free} free of {total}", "Свободно {free} из {total}", "{free} von {total} frei"),
    S("Can't read the network interface", "Не удалось прочитать сетевой интерфейс", "Die Netzwerkschnittstelle lässt sich nicht lesen"),
    S("Down {rx}, up {tx}, capacity unknown", "Входящий {rx}, исходящий {tx}, ёмкость канала неизвестна", "Eingehend {rx}, ausgehend {tx}, Kapazität unbekannt"),
    S("Load {p}% of {cap}, down {rx}, up {tx}", "Загрузка {p}% от {cap}, входящий {rx}, исходящий {tx}", "Auslastung {p}% von {cap}, eingehend {rx}, ausgehend {tx}"),
    S("{errors} errors and {drops} dropped packets in a minute", "За минуту ошибок {errors}, потерянных пакетов {drops}", "{errors} Fehler und {drops} verworfene Pakete pro Minute"),
    S("No errors", "Ошибок нет", "Keine Fehler"),
    S("No answer from {hosts}", "Нет ответа от {hosts}", "Keine Antwort von {hosts}"),
    S("Packet loss {loss}% to {host}", "Потери пакетов {loss}% до {host}", "Paketverlust {loss}% zu {host}"),
    S("Slow responses, {ms} ms", "Медленные ответы, {ms} мс", "Langsame Antworten, {ms} ms"),
    S("{ms} ms, no loss", "{ms} мс, без потерь", "{ms} ms, kein Verlust"),
    S("Names don't resolve", "Имена не разрешаются", "Namen werden nicht aufgelöst"),
    S("Slow, {ms} ms", "Медленно, {ms} мс", "Langsam, {ms} ms"),
    S("{ms} ms", "{ms} мс", "{ms} ms"),
    S("{used} of {limit} this month", "{used} из {limit} в этом месяце", "{used} von {limit} in diesem Monat"),
    S("Mbps", "Мбит/с", "Mbit/s"),

    // Notifications
    S("Problem", "Проблема", "Problem"),
    S("Warning", "Предупреждение", "Warnung"),
    S("Still a problem", "Проблема не решена", "Problem besteht weiter"),
    S("Fixed", "Исправлено", "Behoben"),
    S("Since {time}", "С {time}", "Seit {time}"),
    S("The problem lasted {n} minute.|The problem lasted {n} minutes.", "Проблема длилась {n} минуту.|Проблема длилась {n} минуты.|Проблема длилась {n} минут.", "Das Problem dauerte {n} Minute.|Das Problem dauerte {n} Minuten."),
    S("Server {host}, {time} UTC", "Сервер {host}, {time} UTC", "Server {host}, {time} UTC"),
    S("Server status", "Состояние сервера", "Serverstatus"),
    S("Test notification", "Тестовое уведомление", "Testbenachrichtigung"),
    S("Alerts from your server reach you here.", "Уведомления о сервере будут приходить сюда.", "Benachrichtigungen über deinen Server kommen hier an."),
    S(
        "This chat's id is {id}. Put it in ALERT_TELEGRAM_CHAT_IDS to get server alerts here.",
        "Номер этого чата {id}. Добавьте его в ALERT_TELEGRAM_CHAT_IDS, чтобы получать сюда уведомления о сервере.",
        "Die ID dieses Chats ist {id}. Trag sie in ALERT_TELEGRAM_CHAT_IDS ein, um Serverwarnungen hier zu bekommen.",
    ),

    // Sign-in e-mail
    S("Your JagaNet sign-in code {code}", "Код входа в JagaNet {code}", "Dein JagaNet-Anmeldecode {code}"),
    S("Your sign-in code is {code}. It works for 10 minutes.", "Ваш код входа {code}. Он действует 10 минут.", "Dein Anmeldecode ist {code}. Er gilt 10 Minuten."),
    S("If you didn't ask for it, just ignore this e-mail.", "Если вы его не запрашивали, просто проигнорируйте это письмо.", "Wenn du ihn nicht angefordert hast, ignoriere diese E-Mail einfach."),
    S("All nodes respond", "Все ноды на связи", "Alle Knoten antworten"),
    S("No contact with {names}", "Нет связи с {names}", "Keine Verbindung zu {names}"),
    S("VPN nodes", "VPN-ноды", "VPN-Knoten"),
)
