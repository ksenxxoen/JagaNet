# Запасной адрес API для ПК-клиента (P2-4, issue #37)

Если провайдер блокирует или замедляет `boopvpn.app`, Boop для Windows и Linux ходит в панель через
запасной адрес `https://api.boopvpn.app` (Cloudflare). Пользователь адресов не видит; в отчёте
«Сообщить о проблеме» есть строка `API: основной` или `API: запасной`.

## Как работает клиент

* **Список адресов — в одном месте:** `apiBase` в `desktop/ui.go`, по порядку, через запятую:
  `https://boopvpn.app,https://api.boopvpn.app`. Третий нейтральный домен — ещё один элемент списка.
  Аргумент `[api]` у `build.sh` / `build-linux.sh` и `BOOP_API` (dev-сборка и Linux) заменяют
  **весь** список. Без аргумента сборка берёт список из `ui.go` — так и нужно собирать выпуски
  (сборка с одним адресом выводит предупреждение «no spare address»). На Windows переменной окружения
  для адреса нет, как и раньше.
* **Когда переходит на следующий адрес:** сетевая ошибка (DNS, соединение, TLS, таймаут) или ответ
  явно не от панели: HTML-страница (nginx, Cloudflare, страница блокировки), 52x Cloudflare,
  404/502/503/504 без JSON. Ответы панели с JSON (401, 402, 403, 404, 409, 422, 500…) — **не**
  повод: они возвращаются как раньше.
* **Неидемпотентные запросы** (POST, PATCH, DELETE) уходят на следующий адрес, только если для них
  ни разу не было установлено соединение (значит, до сервера они дойти не могли). Если соединение
  было или пришёл любой ответ — запрос не повторяется, ошибка возвращается как раньше. GET
  повторяется на следующем адресе всегда.
* **Таймаут:** пока есть следующий адрес, на установку соединения (DNS + TCP + TLS) у попытки 3 с
  (`APIConnectBudget`); последний адрес ждёт со старыми таймаутами (8 с соединение, 8 с TLS, 15 с
  весь запрос). Так при мёртвом основном адресе ответ приходит максимум на ~3 с позже, чем при
  рабочем, а после переключения — без задержки. `SocketControl` (физический интерфейс) и DoH
  (`Resolve`) работают для всех адресов: они в общем dialer'е.
* **Запоминание:** адрес, который ответил, становится рабочим; запасной сохраняется в `state.json`
  (`apiBase`) и используется первым при следующем запуске. Через 30 мин (`PrimaryRetry`) после
  переключения один запрос снова идёт сначала на основной адрес; ответил — клиент возвращается на
  него (и `apiBase` из `state.json` убирается), нет — запрос тут же уходит на запасной, следующая
  попытка ещё через 30 мин.
* **Журнал:** каждое переключение — одна строка в `boop.log`, текст ошибки через `Redact`:
  `api: switched to spare address 1 (api.boopvpn.app); boopvpn.app did not answer: …`,
  `api: back to the main address (boopvpn.app)`.
* **Если не ответил ни один адрес** (например, основной заблокирован, а на запасном ещё нет
  фрагмента nginx ниже и он отдаёт 404-страницу), ошибка — «нет связи с панелью» (`ErrOffline`), а
  не 404: приложение не решит, что ключ удалён, и подключится по сохранённому конфигу (P0-7).
* **Через этот механизм идут:** все запросы `/v1/…`, манифест обновления `/v1/app/desktop`,
  скачивание файла обновления, если его URL относительный или на одном из адресов панели (сейчас
  панель отдаёт `https://boopvpn.app/download/windows`), и `/v1/health` проверки сети (опрашиваются
  все адреса сразу). SHA-256 и подпись файла проверяются как раньше, с какого бы адреса он ни
  пришёл. Абсолютный URL на другом хосте скачивается как есть.
* **Не трогаются:** ссылки, которые открываются в браузере (кабинет, продление, поддержка, ссылка на
  .deb в Linux) — они по-прежнему на `https://boopvpn.app`.

## Что добавить в nginx (`boopvpn-api`, сервер 92.53) — применяет Дмитрий

Сейчас `api.boopvpn.app` проксирует только пути агентов нод, а на `/v1/me` и прочее отвечает 404 —
клиент считает его нерабочим и остаётся на основном адресе (всё работает, как до этой задачи).
Чтобы запасной адрес заработал, в `server { … }` для `api.boopvpn.app` в файле
`/etc/nginx/sites-available/boopvpn-api` добавить:

```nginx
    # --- Boop: запасной адрес API для ПК-клиента (issue #37) ---
    # Реальный IP клиента. Запрос приходит от Cloudflare; адрес клиента — в CF-Connecting-IP.
    # Доверять ему только от адресов Cloudflare (список: https://www.cloudflare.com/ips/ —
    # сверить перед применением). Если эти строки уже есть в http { } или в этом server { },
    # второй раз не добавлять.
    set_real_ip_from 173.245.48.0/20;
    set_real_ip_from 103.21.244.0/22;
    set_real_ip_from 103.22.200.0/22;
    set_real_ip_from 103.31.4.0/22;
    set_real_ip_from 141.101.64.0/18;
    set_real_ip_from 108.162.192.0/18;
    set_real_ip_from 190.93.240.0/20;
    set_real_ip_from 188.114.96.0/20;
    set_real_ip_from 197.234.240.0/22;
    set_real_ip_from 198.41.128.0/17;
    set_real_ip_from 162.158.0.0/15;
    set_real_ip_from 104.16.0.0/13;
    set_real_ip_from 104.24.0.0/14;
    set_real_ip_from 172.64.0.0/13;
    set_real_ip_from 131.0.72.0/22;
    set_real_ip_from 2400:cb00::/32;
    set_real_ip_from 2606:4700::/32;
    set_real_ip_from 2803:f800::/32;
    set_real_ip_from 2405:b500::/32;
    set_real_ip_from 2405:8100::/32;
    set_real_ip_from 2a06:98c0::/29;
    set_real_ip_from 2c0f:f248::/32;
    real_ip_header CF-Connecting-IP;

    # API приложения: всё под /v1/, как на boopvpn.app. Уже существующие location для путей
    # агентов нод (например, location /v1/node/) длиннее и продолжают срабатывать сами.
    location /v1/ {
        proxy_pass http://127.0.0.1:4000;   # тот же upstream (PORT панели), что у boopvpn.app
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        # Заменить, а не дописать ($proxy_add_x_forwarded_for): панель берёт первый адрес из
        # X-Forwarded-For, и присланный клиентом заголовок не должен его подменять.
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        client_max_body_size 1m;            # отчёт о проблеме — до ~256 КБ
        proxy_read_timeout 60s;
    }

    # Файл обновления для Windows (URL из манифеста: /download/windows).
    location = /download/windows {
        proxy_pass http://127.0.0.1:4000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 120s;
    }
```

Перед применением: `proxy_pass` должен смотреть туда же, куда в конфиге `boopvpn.app` (если там
`upstream` или другой порт — взять его). Пути `/internal/…` и сайт через этот адрес не открываются —
так и задумано. Применить: `sudo nginx -t && sudo systemctl reload nginx`.

Проверка после применения (с любой машины):

```sh
curl -sS https://api.boopvpn.app/v1/health        # {"ok":true,...} — JSON панели, не HTML
curl -sS -o /dev/null -w '%{http_code}\n' https://api.boopvpn.app/v1/me   # 401 (JSON), не 404
curl -sSI https://api.boopvpn.app/download/windows | head -3   # 200 или 404 панели, если exe не выложен
```

В журнале панели у запросов через `api.boopvpn.app` должен быть реальный IP клиента, а не адрес
Cloudflare.

## Как проверить клиент вручную

**Dev-сборка (Linux/macOS, без туннеля)** — основной адрес заменить на несуществующий через `BOOP_API`:

```sh
cd desktop
node scripts/fakepanel.mjs &                     # печатает свой адрес, например http://127.0.0.1:5200
BOOP_API=https://boop-nonexistent.invalid,http://127.0.0.1:5200 go run .
# открыть http://127.0.0.1:5199, войти: вход и список серверов работают,
# в выводе: "api: switched to spare address 1 (127.0.0.1:5200); boop-nonexistent.invalid did not answer: …"
# «Сообщить о проблеме» → в тексте строка "API: запасной"
```

Первый запрос ждёт основной адрес до ~3 с (dev-сборка подставляет несуществующим именам выдуманный
адрес `192.0.2.x`, он не отвечает), дальше всё идёт сразу на запасной. После перезапуска с тем же
`BOOP_DATA` в выводе `api: starting with spare address …`. Вместо несуществующего имени можно взять
адрес, который не отвечает (`https://10.255.255.1`), или отказывающий порт (`http://127.0.0.1:9`).

**Linux-пакет** — так же через `BOOP_API` (адреса через запятую), например
`BOOP_API=https://boop-nonexistent.invalid,https://boopvpn.app boop` — вход идёт через
`boopvpn.app` как через запасной.

**Windows** — переменной окружения нет; собрать тестовый exe со списком, где основной адрес
не существует, а запасной — настоящая панель:

```sh
./build.sh 1.2.0-test "https://boop-nonexistent.invalid,https://boopvpn.app"
```

Запустить, войти, открыть `%LOCALAPPDATA%\Boop\boop.log` — строка `api: switched to spare address 1`,
в `state.json` — `"apiBase": "https://boopvpn.app"`; «Сообщить о проблеме» → `API: запасной`.
После применения nginx-фрагмента то же самое с настоящим запасным:
`./build.sh 1.2.0-test "https://boop-nonexistent.invalid,https://api.boopvpn.app"`.
