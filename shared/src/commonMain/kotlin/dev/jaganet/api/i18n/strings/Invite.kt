package dev.jaganet.api.i18n.strings

import dev.jaganet.api.i18n.S

/** "Invite a friend, get +14 days" (issue #40): the bot, the website's invite page, notices and the admin view. */
internal val inviteStrings: List<S> = listOf(
    // Bot and website
    S("Invite a friend", "Пригласить друга", "Freund einladen"),
    S("Invite a friend and get {days} of Pro", "Пригласите друга и получите {days} Pro", "Lade einen Freund ein und erhalte {days} Pro"),
    S(
        "Send your link to a friend. When they pay for a subscription for the first time, you get {days} of Pro.",
        "Отправьте другу свою ссылку. Когда он впервые оплатит подписку, вы получите {days} Pro.",
        "Schick deinem Freund deinen Link. Wenn er zum ersten Mal ein Abo bezahlt, bekommst du {days} Pro.",
    ),
    S(
        "Send your link to a friend. When they sign up, you get {days} of Pro.",
        "Отправьте другу свою ссылку. Когда он зарегистрируется, вы получите {days} Pro.",
        "Schick deinem Freund deinen Link. Wenn er sich anmeldet, bekommst du {days} Pro.",
    ),
    S("Your friend gets {days} of Pro too.", "Друг тоже получит {days} Pro.", "Dein Freund bekommt auch {days} Pro."),
    S("Link to the website", "Ссылка на сайт", "Link zur Website"),
    S("Link to the Telegram bot", "Ссылка на Telegram-бота", "Link zum Telegram-Bot"),
    S("Invited {n}", "Приглашено {n}", "Eingeladen {n}"),
    S("Days received {n}", "Получено дней {n}", "Erhaltene Tage {n}"),
    S("Days received", "Получено дней", "Erhaltene Tage"),
    S("Your code {code}", "Ваш код {code}", "Dein Code {code}"),
    S("Rewards", "Начисления", "Gutschriften"),
    S("No rewards yet", "Начислений пока нет", "Noch keine Gutschriften"),
    S("Welcome bonus", "Бонус за приглашение", "Willkommensbonus"),
    S("Added", "Начислено", "Gutgeschrieben"),
    S("Monthly limit reached", "Достигнут месячный лимит", "Monatslimit erreicht"),
    S("Cancelled by admin", "Отменено администратором", "Vom Admin storniert"),

    // Notices (Telegram or e-mail)
    S(
        "A friend you invited paid for a subscription, you get {days} of Pro. Pro is active until {date}.",
        "Приглашённый вами друг оплатил подписку, вам начислено {days} Pro. Pro действует до {date}.",
        "Ein von dir eingeladener Freund hat ein Abo bezahlt, du bekommst {days} Pro. Pro ist aktiv bis {date}.",
    ),
    S(
        "A welcome bonus for joining by invitation, {days} of Pro. Pro is active until {date}.",
        "Бонус за регистрацию по приглашению, {days} Pro. Pro действует до {date}.",
        "Ein Willkommensbonus für die Anmeldung per Einladung, {days} Pro. Pro ist aktiv bis {date}.",
    ),
    S("Boop VPN, a reward for inviting a friend", "Boop VPN, награда за приглашение друга", "Boop VPN, Bonus für die Einladung eines Freundes"),
    S("Reward not found", "Начисление не найдено", "Gutschrift nicht gefunden"),

    // Admin
    S("Invite rewards", "Награды за приглашения", "Einladungsboni"),
    S("Rewards given", "Начислений", "Gutschriften"),
    S("Days given", "Начислено дней", "Gutgeschriebene Tage"),
    S("Over the monthly limit", "Сверх месячного лимита", "Über dem Monatslimit"),
    S("Cancelled rewards", "Отменено", "Storniert"),
    S("Suspicious", "Подозрительные", "Verdächtig"),
    S("Same network as the inviter", "Та же сеть, что у пригласившего", "Gleiches Netz wie der Einladende"),
    S("Same browser as the inviter", "Тот же браузер, что у пригласившего", "Gleicher Browser wie der Einladende"),
    S(
        "Days are added after the end of the current subscription, or from today if it has ended.",
        "Дни добавляются после окончания текущей подписки, а если она закончилась, то с сегодняшнего дня.",
        "Die Tage kommen nach dem Ende des laufenden Abos dazu, oder ab heute, wenn es abgelaufen ist.",
    ),
    S("Days for the inviter", "Дней пригласившему", "Tage für den Einladenden"),
    S("0 rewards off", "0 награды выключены", "0 Boni aus"),
    S("When to reward", "Когда начислять", "Wann gutschreiben"),
    S("First payment of the friend", "Первая оплата друга", "Erste Zahlung des Freundes"),
    S("Sign-up of the friend", "Регистрация друга", "Anmeldung des Freundes"),
    S("Days for the friend", "Дней другу", "Tage für den Freund"),
    S("0 none", "0 нет", "0 keine"),
    S("Rewards per inviter a month", "Начислений одному пригласившему в месяц", "Gutschriften pro Einladendem im Monat"),
    S("Top inviters", "Лучшие пригласившие", "Top-Einladende"),
    S("For", "За что", "Wofür"),
    S("For inviting {who}", "За приглашение {who}", "Für die Einladung von {who}"),
    S("Sign-up", "Регистрация", "Anmeldung"),
    S("First payment", "Первая оплата", "Erste Zahlung"),
    S("No", "Нет", "Nein"),
    S("Waiting", "Ожидает", "Wartet"),
    S("Not sent", "Не отправлено", "Nicht gesendet"),
    S("Cancel reward", "Отменить начисление", "Gutschrift stornieren"),
    S("Cancel this reward? Its days are taken back.", "Отменить начисление? Его дни будут списаны.", "Gutschrift stornieren? Ihre Tage werden abgezogen."),
)
