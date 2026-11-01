# Boop для компьютера

Один клиент (`desktop/`) для Windows и Linux: окно `ui/` (HTML/CSS/JS) рисует, Go (`core/`) держит
состояние и туннель (Windows: AmneziaWG и VLESS; Linux: пока только AmneziaWG, VLESS-серверы там не показываются). Устройство — в [ARCHITECTURE.md](ARCHITECTURE.md).

| | Windows | Ubuntu 22.04 / 24.04 |
| --- | --- | --- |
| Сборка | `./build.sh [версия] [api]` → `dist/Boop.exe` | `./build-linux.sh [версия] [api]` → `dist/boop_<версия>~ubuntu22.04_amd64.deb`, `…~ubuntu24.04_amd64.deb` |
| Окно | WebView2 | WebKitGTK (webview_go) |
| Туннель | в самом приложении (администратор): AmneziaWG и VLESS | служба `boop-helper` (root), окно — от пользователя; только AmneziaWG |
| Обновления | скачивает и ставит сам | только сообщает о версии и открывает ссылку на .deb |
| Подпись | [SIGNING.md](SIGNING.md) | нет (пакет ставится локально через apt) |

Разработка без Windows и без туннеля: `go run .` — окно в браузере на `127.0.0.1:5199` с поддельным
туннелем (`main_other.go`); проверки UI — `node scripts/ui-checks.mjs`, `node scripts/e2e.mjs`.

Адреса панели: `https://boopvpn.app` (основной), затем `https://api.boopvpn.app` (запасной) — список
в одном месте, `apiBase` в `ui.go`. Аргумент `[api]` сборки и `BOOP_API` (dev-сборка, Linux) заменяют
весь список (адреса через запятую). Как работает переключение, фрагмент nginx для `api.boopvpn.app`
и ручная проверка — [docs/api-fallback.md](docs/api-fallback.md).

## Linux: сборка

Нужен Docker (сборка идёт в контейнерах `ubuntu:22.04` и `ubuntu:24.04`, поэтому glibc, GTK и
WebKitGTK в пакете — ровно те, что в этом выпуске Ubuntu) и Go 1.27 на машине сборки (Go и модули
скачиваются на хосте и проверяются по хэшу; контейнеру нужен только архив Ubuntu).

```sh
cd desktop
./build-linux.sh 1.3.1          # адреса панели — список из ui.go (основной + запасной)
# dist/boop_1.3.1~ubuntu22.04_amd64.deb
# dist/boop_1.3.1~ubuntu24.04_amd64.deb   (+ .sha256 рядом)
```

* Каждый пакет проверяется `lintian` внутри своего выпуска; ошибка lintian — ошибка сборки.
* `BOOP_INSTALL_TEST=1 ./build-linux.sh …` — после сборки ставит каждый пакет в чистый контейнер
  своего выпуска (`apt install ./…deb`), запускает `boop --version` и `boop-helper --version`,
  удаляет и вычищает пакет.
* `BOOP_NETTEST=1 ./build-linux.sh …` — интеграционный тест туннеля (`nettest/`): сервер
  amneziawg-go в отдельном network namespace, трафик через туннель, после отключения не остаётся ни
  правил, ни маршрутов, ни интерфейса. Нужен контейнер с `NET_ADMIN`, `SYS_ADMIN` и `/dev/net/tun`.
* `BOOP_LINUX_RELEASES=24.04` — только один выпуск; `BOOP_LINUX_BASE_22_04=<образ>` — другой
  базовый образ (зеркало); `BOOP_LINUX_DOCKER=0` — собрать без Docker только для Ubuntu, на которой
  запущено (нужны пакеты из `linux/Dockerfile`).

Почему два пакета, а не один: на 22.04 есть только `libwebkit2gtk-4.0-37`, на 24.04 — только
`libwebkit2gtk-4.1-0` (4.0 из 24.04 убрали). Это разные библиотеки с одинаковым API; бинарник,
слинкованный с одной, на другой не запустится. Подгружать WebKitGTK во время выполнения (dlopen)
ненадёжно и сложно отлаживать, а два пакета собираются одной командой и ставятся штатно через apt
с правильными `Depends:`. Пакет для 24.04 собирается тем же `webview_go` через переходник
`linux/pkgconfig-webkit41` (webview.h сам поддерживает 4.1).

## Linux: установка

```sh
sudo apt install ./boop_1.3.1~ubuntu24.04_amd64.deb     # на 22.04 — …~ubuntu22.04…
```

Пакет ставит `/usr/bin/boop`, `/usr/lib/boop/boop-helper`, службу `boop-helper.service` (включается и
запускается сразу), ярлык «Boop» в меню приложений с иконкой. Пользователь, который ставил пакет
(`$SUDO_USER`, иначе пользователь с uid 1000), попадает в группу `boop` — только её члены (и root)
могут управлять туннелем. Перелогиниваться не нужно. Другого пользователя добавить:
`sudo usermod -aG boop <имя>`.

Удаление: `sudo apt remove boop` (служба останавливается, всё, что оставил туннель, убирается),
`sudo apt purge boop` — ещё и группа `boop`. Настройки и вход пользователя лежат в `~/.config/boop`
и пакетом не трогаются.

Если что-то не так:

* «Служба Boop не запущена» — `sudo systemctl enable --now boop-helper`, журнал:
  `journalctl -u boop-helper`.
* «Нет доступа к службе Boop» — пользователь не в группе: `sudo usermod -aG boop $USER`.
* Журнал приложения: `~/.config/boop/boop.log`. «Сообщить о проблеме» в настройках работает как на Windows.
* Нет окна (нет WebKitGTK/дисплея) — `boop --browser` открывает Boop в браузере
  (127.0.0.1, случайный порт, одноразовая ссылка); то же происходит само, если окно создать нельзя.
* Пустое окно или странное поведение страницы — `BOOP_DEBUG=1 boop` из терминала: консоль страницы
  и вызовы моста попадают в вывод, в окне доступен инспектор WebKit.
* Проверить сборку против тестовой панели: `BOOP_API=<адрес> boop` (как `go run .` в разработке).

## Linux: что делает служба

`boop-helper` (root) держит интерфейс `boop0` (userspace amneziawg-go, все параметры обфускации —
тем же общим кодом конфига, что и на Windows). Весь трафик идёт в туннель как в wg-quick: fwmark
`0x426f` на сокете туннеля, своя таблица `17007`, правила `not fwmark 0x426f table 17007` и
`table main suppress_prefixlength 0` для IPv4 и IPv6. Если в конфиге нет IPv6-адреса, IPv6 в этой
таблице `unreachable` — мимо туннеля он не уходит (как на Windows). DNS из конфига отдаётся
systemd-resolved: `resolvectl dns boop0 …`, `resolvectl domain boop0 ~.`. При отключении, выходе
или падении окна, остановке службы и при её старте всё это убирается (`boop-helper --cleanup`).

Окно говорит со службой по `/run/boop/helper.sock` (JSON по строке: `hello`, `up`, `down`,
`status`, `ping`; ничего больше). Право проверяется по uid собеседника (`SO_PEERCRED`) и группе
`boop`. Туннель принадлежит соединению окна: закрылось окно — служба опускает туннель.

## Linux: публикация обновления

Клиент на Linux спрашивает `GET /v1/app/desktop?platform=linux&distro=ubuntu24.04` и, если версия
новее, показывает «Доступно обновление» и кнопку «Скачать версию …», которая открывает
`/download/linux/<distro>` в браузере. Ставит пакет человек сам. Чтобы опубликовать, положить в
`DOWNLOADS_DIR` сервера:

* `boop-ubuntu22.04.deb` и `boop-ubuntu24.04.deb` (переименованные пакеты из `dist/`);
* `desktop-linux.json`: `{"version":"1.3.1","notes":"…","mandatory":false}`.

Пока их нет, Linux-клиент получает 404 и ничего не предлагает. Windows-клиенты спрашивают без
параметров и получают то же, что и раньше (`desktop.json` + `Boop.exe`).
