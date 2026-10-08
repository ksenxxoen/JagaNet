package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

internal val authStrings: List<S> = listOf(
    // Sign in
    S("Private internet,\nno setup.", "Приватный интернет\nбез настройки.", "Privates Internet,\nohne Einrichtung."),
    S("Sign in with your email. We’ll send a 6-digit code, so there’s no password to remember.", "Войдите по e-mail. Мы пришлём 6-значный код, пароль запоминать не нужно.", "Melde dich mit deiner E-Mail an. Wir schicken dir einen 6-stelligen Code, ganz ohne Passwort."),
    S("Email", "E-mail", "E-Mail"),
    S("Invite code", "Код приглашения", "Einladungscode"),
    S("Email me a code", "Получить код", "Code per E-Mail senden"),
    S("I have an invite code", "У меня есть код приглашения", "Ich habe einen Einladungscode"),
    S("Already use {app} on another device?", "Уже пользуетесь {app} на другом устройстве?", "Nutzt du {app} schon auf einem anderen Gerät?"),
    S("Open Devices there, tap Add device and enter the code here.", "Откройте там «Устройства», нажмите «Добавить устройство» и введите код здесь.", "Öffne dort Geräte, tippe auf Gerät hinzufügen und gib den Code hier ein."),
    S("Sign in with a device code", "Войти по коду с устройства", "Mit Gerätecode anmelden"),
    S("Can’t reach the server. Check your connection.", "Не удаётся связаться с сервером. Проверьте подключение к интернету.", "Server nicht erreichbar. Prüfe deine Verbindung."),
    S("Can’t reach the server.", "Не удаётся связаться с сервером.", "Server nicht erreichbar."),

    // Verify
    S("Check your email", "Проверьте почту", "Schau in deine E-Mails"),
    S("We sent a 6-digit code to {email}. It expires in 10 minutes.", "Мы отправили 6-значный код на {email}. Он действует 10 минут.", "Wir haben einen 6-stelligen Code an {email} geschickt. Er gilt 10 Minuten."),
    S("Test mode, your code {code} is filled in for you.", "Тестовый режим, код {code} уже подставлен.", "Testmodus, dein Code {code} ist schon eingetragen."),
    S("Code", "Код", "Code"),
    S("Sign in", "Войти", "Anmelden"),
    S("Use a different email", "Другой e-mail", "Andere E-Mail verwenden"),

    // Pair
    S("Enter device code", "Код с устройства", "Gerätecode eingeben"),
    S("On a device that’s already signed in, open Devices and tap Add device. Enter the 6 digits shown there.", "На устройстве, где вы уже вошли, откройте «Устройства» и нажмите «Добавить устройство». Введите 6 цифр, которые там появятся.", "Öffne auf einem angemeldeten Gerät Geräte und tippe auf Gerät hinzufügen. Gib die 6 Ziffern ein, die dort stehen."),
    S("Device code", "Код устройства", "Gerätecode"),

    // Tab bar
    S("Connect", "Подключить", "Verbinden"),
    S("Stats", "Статистика", "Statistik"),
    S("Settings", "Настройки", "Einstellungen"),

    // Shared UI kit
    S("Back", "Назад", "Zurück"),
    S("Loading…", "Загрузка…", "Lädt…"),
    S("Something went wrong", "Что-то пошло не так", "Etwas ist schiefgelaufen"),

    // Desktop window
    S("{app} simulator", "Симулятор {app}", "{app} Simulator"),
)
