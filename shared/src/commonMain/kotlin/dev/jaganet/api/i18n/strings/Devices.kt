package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

internal val devicesStrings: List<S> = listOf(
    S("Devices", "Устройства", "Geräte"),
    S(
        "{used} of {n} device on your plan|{used} of {n} devices on your plan",
        "{used} из {n} устройства по тарифу|{used} из {n} устройств по тарифу|{used} из {n} устройств по тарифу",
        "{used} von {n} Gerät in deinem Tarif|{used} von {n} Geräten in deinem Tarif",
    ),
    S("Remove", "Удалить", "Entfernen"),
    S("Removing signs the device out and frees a slot.", "Устройство выйдет из аккаунта, а место освободится.", "Das Gerät wird abgemeldet und der Platz wird frei."),
    S("Add device", "Добавить устройство", "Gerät hinzufügen"),
    S("Sign-in code", "Код для входа", "Anmeldecode"),
    S("New device", "Новое устройство", "Neues Gerät"),
    S(
        "Install {app} on the other device, choose “{button}” and enter this code. It expires in 10 minutes.",
        "Установите {app} на другое устройство, выберите «{button}» и введите этот код. Он действует 10 минут.",
        "Installiere {app} auf dem anderen Gerät, wähle „{button}“ und gib diesen Code ein. Er gilt 10 Minuten.",
    ),
    S("Need more devices?", "Нужно больше устройств?", "Mehr Geräte nötig?"),
    S("no tunnel yet", "туннеля пока нет", "noch kein Tunnel"),
    S("seen {ago}", "активность {ago}", "zuletzt {ago}"),
    S("Protocol: {name}", "Протокол: {name}", "Protokoll: {name}"),
    S("This device", "Это устройство", "Dieses Gerät"),
    S("Online", "В сети", "Online"),
    S("Offline", "Не в сети", "Offline"),
    S("More options for {name}", "Действия с устройством {name}", "Mehr Optionen für {name}"),
)
