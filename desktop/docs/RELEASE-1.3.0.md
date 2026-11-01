# Выпуск Boop 1.3.0: как опубликовать

Файлы собраны без подписи (сертификата нет): `Boop.exe`, два `.deb`, `.sha256` к каждому и
манифесты `desktop.json` / `desktop-linux.json`. Ничего из этого не лежит в репозитории.

## 0. (По желанию) подписать Boop.exe — до всего остального

Порядок из [SIGNING.md](../SIGNING.md): **сначала подпись exe, потом хэш**.

```sh
cd desktop
BOOP_SIGN=azure ./build.sh 1.3.0        # или BOOP_SIGN=osslsigncode, см. SIGNING.md
osslsigncode verify -in dist/Boop.exe    # Succeeded
cat dist/Boop.exe.sha256                 # хэш ПОДПИСАННОГО файла
```

Подписанный exe — другие байты: в `desktop.json` поставить его `sha256` (или убрать поле —
панель посчитает хэш сама по лежащему рядом `Boop.exe`). Без подписи — взять файлы как есть.
Ed25519-подпись манифеста (`signature`) в 1.3.0 **не используется**: в клиент не вшит
открытый ключ (`core.UpdatePublicKey` пустой), поле можно не заполнять. Если его когда-нибудь
включат — подписывать ключом уже подписанный Authenticode файл, тоже до публикации манифеста.

## 1. Положить файлы в `DOWNLOADS_DIR` панели

`DOWNLOADS_DIR` — переменная окружения сервера панели (каталог, который читает `Site.kt`).
Имена файлов — ровно такие:

| файл из сборки | имя в `DOWNLOADS_DIR` | что отдаёт панель |
| --- | --- | --- |
| `Boop.exe` | `Boop.exe` | `/download/windows` |
| `boop_1.3.0~ubuntu22.04_amd64.deb` | `boop-ubuntu22.04.deb` | `/download/linux/ubuntu22.04` |
| `boop_1.3.0~ubuntu24.04_amd64.deb` | `boop-ubuntu24.04.deb` | `/download/linux/ubuntu24.04` |
| `desktop.json` | `desktop.json` | `GET /v1/app/desktop` (Windows) |
| `desktop-linux.json` | `desktop-linux.json` | `GET /v1/app/desktop?platform=linux&distro=…` |

Порядок: сначала `Boop.exe` и оба `.deb` (копировать во временное имя и `mv` — чтобы клиент не
скачал половину файла), **последними** — `desktop.json` и `desktop-linux.json`. Пока манифеста нет,
установленные клиенты обновление не видят; сайт и прямые ссылки уже отдают новые файлы.

```sh
D=/path/to/DOWNLOADS_DIR
install -m 0644 Boop.exe "$D/.Boop.exe.new" && mv "$D/.Boop.exe.new" "$D/Boop.exe"
install -m 0644 boop_1.3.0~ubuntu22.04_amd64.deb "$D/.u22.new" && mv "$D/.u22.new" "$D/boop-ubuntu22.04.deb"
install -m 0644 boop_1.3.0~ubuntu24.04_amd64.deb "$D/.u24.new" && mv "$D/.u24.new" "$D/boop-ubuntu24.04.deb"
install -m 0644 desktop.json desktop-linux.json "$D/"
```

Манифесты (поля читает `DesktopReleaseFile` в `Site.kt`; `url` панель подставляет сама):

```json
desktop.json        {"version":"1.3.0","mandatory":false,"notes":"…","sha256":"<sha256 Boop.exe>"}
desktop-linux.json  {"version":"1.3.0","mandatory":false,"notes":"…"}
```

`sha256` в `desktop.json` необязателен (без него панель хэширует `Boop.exe`), но должен
совпадать с файлом, иначе клиенты откажутся ставить обновление (`sha256 mismatch`). Для Linux хэш
панель всегда считает по `.deb`. `notes` показывается в баннере обновления — без названий
протоколов. Перезапуск панели не нужен: файлы читаются при каждом запросе.

## 2. Запасной адрес `api.boopvpn.app`

Если nginx для `api.boopvpn.app` ещё не дополнен по [api-fallback.md](api-fallback.md)
(`location /v1/` и `location = /download/windows` к панели) — сделать это до или вместе с
выпуском. Без этого всё работает через основной адрес, просто нет запасного.
Проверка: `curl -s https://api.boopvpn.app/v1/app/desktop | jq .version` → `"1.3.0"`.

## 3. Проверить

```sh
curl -s https://boopvpn.app/v1/app/desktop | jq                       # version 1.3.0, url …/download/windows, sha256
curl -s 'https://boopvpn.app/v1/app/desktop?platform=linux&distro=ubuntu24.04' | jq
curl -sL https://boopvpn.app/download/windows | sha256sum              # = sha256 из манифеста
curl -sL https://boopvpn.app/download/linux/ubuntu22.04 | sha256sum   # = boop_1.3.0~ubuntu22.04_amd64.deb.sha256
curl -sL https://boopvpn.app/download/linux/ubuntu24.04 | sha256sum
```

**Автообновление на Windows:** на машине с Boop 1.2.0 открыть окно (проверка при запуске и раз
в 6 часов — перезапустить Boop) → баннер «Доступно обновление · 1.3.0 — <notes>» →
нажать → «Скачиваем…», «Устанавливаем и перезапускаем…» → в настройках внизу «Boop 1.3.0».
В `%LOCALAPPDATA%\Boop\boop.log`: `update: staged 1.3.0`. Ошибка хэша — «Не удалось обновить.
Повторить», старая версия продолжает работать.

**Linux:** на 1.2.0 баннер «Доступно обновление», «Скачать версию 1.3.0» открывает
`/download/linux/ubuntu<ver>`; поставить `sudo apt install ./boop-ubuntu24.04.deb`, `boop --version`
→ `1.3.0`.

Дальше — ручной чек-лист [QA-1.3.0.md](QA-1.3.0.md).

## Откат

Вернуть прежние `Boop.exe` / `.deb` и манифесты с прежней версией. Клиенты 1.3.0 на 1.2.0 сами не
откатываются (обновляются только на более новую версию).
