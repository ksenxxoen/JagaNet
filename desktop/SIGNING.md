# Подпись Boop.exe

`Boop.exe` пока не подписан, поэтому SmartScreen и антивирусы предупреждают при
скачивании и при автообновлении. Сертификат не куплен; сборка подготовлена так, что
подпись включается одной переменной окружения `BOOP_SIGN`. Без неё `./build.sh`
работает как раньше и выдаёт неподписанный `dist/Boop.exe`.

Сертификаты, ключи, пароли и токены **никогда не кладём в репозиторий**: только в
переменные окружения / секреты CI. `*.pfx` и `*.pvk` занесены в `.gitignore`.

## Как устроен шаг подписи

После `go build` скрипт, если задан `BOOP_SIGN`:

1. подписывает копию `dist/Boop.exe` (Authenticode, SHA-256) с меткой времени
   RFC 3161;
2. проверяет подпись `osslsigncode verify` (см. `BOOP_SIGN_VERIFY`);
3. только после успешной проверки кладёт подписанный файл на место `dist/Boop.exe`;
4. пишет `dist/Boop.exe.sha256` — хэш **подписанного** файла для манифеста обновления.

Любая ошибка (нет инструмента, неверный пароль, недоступен сервер меток времени,
проверка не прошла) останавливает сборку с ненулевым кодом. Пароль передаётся
`osslsigncode` через временный файл с правами 600 (`-readpass`), а не в командной
строке, и удаляется по выходе.

## Варианты

### 1. Обычный сертификат (OV) через osslsigncode

`BOOP_SIGN=osslsigncode`. Нужен [osslsigncode](https://github.com/mtrojnara/osslsigncode)
2.x (`apt-get install osslsigncode`, `brew install osslsigncode`). Работает на Linux и
macOS, то есть сборка и подпись идут на одной машине без Windows.

| Переменная | Обязательна | Что это |
|---|---|---|
| `BOOP_SIGN_PFX` | да, или следующая | путь к PFX/P12 с ключом и цепочкой |
| `BOOP_SIGN_PFX_BASE64` | вместо `BOOP_SIGN_PFX` | тот же PFX в base64 (удобно для секрета CI); раскладывается во временный файл |
| `BOOP_SIGN_PASSWORD` | если PFX с паролем | пароль от PFX |
| `BOOP_SIGN_TSA` | нет | сервер меток времени RFC 3161, по умолчанию `https://timestamp.acs.microsoft.com`; `none` — без метки (только для тестов) |

Важно: с июня 2023 года удостоверяющие центры выдают OV-сертификаты для подписи кода
только с ключом в аппаратном модуле (USB-токен или облачный HSM: DigiCert KeyLocker,
SSL.com eSigner, Certum SimplySign и т. п.), а не PFX-файлом. Путь с PFX годится для
тестового сертификата и для старых/внутренних сертификатов. С токеном или облачным
HSM нужен PKCS#11 (`osslsigncode -pkcs11module …`) или инструмент поставщика —
в `build.sh` это пока не встроено (см. «Что дальше»). Если сертификат ещё не выбран,
проще взять Azure Trusted Signing.

### 2. Azure Trusted Signing

`BOOP_SIGN=azure`. Облачная подпись Microsoft (в новых документах может называться
Artifact Signing): ключ живёт в Azure, сертификаты короткоживущие и выпускаются
сервисом сами, платится подписка (на момент написания — порядка 10 $ в месяц за
базовый тариф). Есть требования к заявителю (проверка организации или, для отдельных
стран, физлица) — актуальные условия смотреть у Microsoft перед регистрацией.

Подписывает [jsign](https://ebourg.github.io/jsign/) 6.0+ (Java, работает и на Linux).

| Переменная | Обязательна | Что это |
|---|---|---|
| `BOOP_AZURE_ENDPOINT` | да | регион аккаунта, например `weu.codesigning.azure.net` |
| `BOOP_AZURE_ACCOUNT` | да | имя аккаунта Trusted Signing |
| `BOOP_AZURE_PROFILE` | да | имя профиля сертификатов |
| `BOOP_AZURE_TOKEN` | нет | токен доступа; если не задан, берётся `az account get-access-token --resource https://codesigning.azure.net` (нужен вход `az login` или сервисный принципал с ролью «Trusted Signing Certificate Profile Signer») |
| `BOOP_SIGN_JSIGN` | нет | команда jsign, по умолчанию `jsign` |
| `BOOP_SIGN_TSA` | нет | по умолчанию `http://timestamp.acs.microsoft.com` |

Токен передаётся jsign как `env:BOOP_AZURE_TOKEN`, в командную строку не попадает.
Сертификаты Trusted Signing выпускаются на несколько дней, поэтому метка времени
здесь обязательна — без неё подпись быстро станет недействительной.

На Windows то же самое делает `signtool sign /dlib Azure.CodeSigning.Dlib.dll
/dmdf metadata.json /fd SHA256 /tr http://timestamp.acs.microsoft.com /td SHA256
dist\Boop.exe`; `build.sh` этот путь не использует.

### Общие переменные

| Переменная | По умолчанию | Что это |
|---|---|---|
| `BOOP_SIGN` | пусто — не подписывать | `osslsigncode` или `azure` |
| `BOOP_SIGN_NAME` | `Boop` | описание программы в подписи (видно в окне UAC) |
| `BOOP_SIGN_URL` | адрес панели (`api`, второй аргумент) | ссылка в подписи |
| `BOOP_SIGN_VERIFY` | `strict` | `strict` — сборка падает, если `osslsigncode verify` не прошёл; `warn` — только предупреждение; `off` — не проверять |
| `BOOP_SIGN_CAFILE` | системные корни OpenSSL | свой корневой сертификат для проверки (тестовый самоподписанный, корень Microsoft для Trusted Signing) |

`osslsigncode verify` проверяет цепочку по корням OpenSSL (`/etc/ssl/certs`), а не по
хранилищу Windows. Корни обычных УЦ там есть; корня Microsoft Identity Verification,
которым заверены сертификаты Trusted Signing, может не быть — тогда либо указать его в
`BOOP_SIGN_CAFILE`, либо `BOOP_SIGN_VERIFY=warn` и проверить на Windows `signtool`.

## Как проверить подпись

На Linux/macOS:

```sh
osslsigncode verify -in dist/Boop.exe
```

Ждём `Message digest` совпадающий (Current = Calculated), `Timestamp Server Signature
verification: ok` и `Succeeded`.

На Windows:

```bat
signtool verify /pa /v dist\Boop.exe
```

или в PowerShell `Get-AuthenticodeSignature .\Boop.exe` (статус `Valid`), или
«Свойства → Цифровые подписи» в проводнике.

### Проверка с тестовым сертификатом

Самоподписанный сертификат делается в любом временном каталоге **вне репозитория**:

```sh
t=$(mktemp -d)
openssl req -x509 -newkey rsa:3072 -sha256 -days 30 -nodes \
  -subj "/CN=Boop Test Signing" -addext "extendedKeyUsage=codeSigning" \
  -keyout "$t/key.pem" -out "$t/cert.pem"
openssl pkcs12 -export -inkey "$t/key.pem" -in "$t/cert.pem" -out "$t/test.pfx" -passout pass:test

BOOP_SIGN=osslsigncode BOOP_SIGN_PFX="$t/test.pfx" BOOP_SIGN_PASSWORD=test \
  BOOP_SIGN_CAFILE="$t/cert.pem" ./build.sh 1.2.0-test
osslsigncode verify -in dist/Boop.exe     # ожидаемо: Error: self-signed certificate
osslsigncode verify -CAfile "$t/cert.pem" -in dist/Boop.exe   # Succeeded
rm -rf "$t"
```

Без `-CAfile` проверка заканчивается `Failed` только из-за недоверенного корня
(`self-signed certificate`); дайджест и метка времени при этом проверяются успешно.
Без `BOOP_SIGN_CAFILE` (или с `BOOP_SIGN_VERIFY=warn`) `build.sh` с таким сертификатом
в режиме `strict` остановится — так и задумано.

## SmartScreen

* Неподписанный файл: «Windows защитила ваш компьютер» и издатель «Неизвестный»
  у каждого нового файла, пока он сам не наберёт репутацию.
* Подписанный: в окне UAC и в предупреждениях виден издатель. Репутация SmartScreen
  копится на **сертификат** (издателя), а не на каждую сборку, поэтому после
  накопления новые версии перестают вызывать предупреждение. Сразу после покупки
  предупреждение всё ещё может появляться — нужны скачивания.
* EV-сертификаты с 2024 года больше не дают мгновенной репутации; переплачивать
  за EV ради SmartScreen смысла нет.
* Антивирусы реже дают ложные срабатывания на подписанный файл, особенно при
  автообновлении (программа сама скачивает и запускает exe).
* Без метки времени подпись перестаёт быть действительной, когда истекает
  сертификат, — поэтому `BOOP_SIGN_TSA=none` только для тестов.

## Автообновление и порядок релиза

Клиент (`core/update.go`) скачивает exe по манифесту `GET /v1/app/desktop` и сверяет
SHA-256 всего файла с полем `sha256`, а если в клиент вшит открытый ключ —
ещё и ed25519-подпись (`signature`) над теми же байтами. Authenticode меняет файл
(контрольная сумма PE, таблица сертификатов, сама подпись в конце), поэтому:

1. `./build.sh <версия>` с `BOOP_SIGN=…` — сборка и подпись (повторный прогон даст
   **другой** хэш: в подписи новая метка времени).
2. Проверить подпись (`osslsigncode verify` / `signtool verify`), при желании
   запустить на Windows.
3. Хэш взять из `dist/Boop.exe.sha256` — он посчитан от подписанного файла.
4. Если используется ed25519 — подписывать этим ключом тоже уже подписанный файл.
5. Загрузить именно этот `dist/Boop.exe` по адресу из манифеста.
6. Последним шагом опубликовать манифест (`version`, `url`, `sha256`, `signature`).

Ничего не переподписывать и не пересобирать после того, как хэш попал в манифест:
клиент такой файл отвергнет (`sha256 mismatch`) и обновление не установится, хотя
старая версия продолжит работать. Сама проверка в клиенте от подписи не зависит —
это хэш байтов файла; тест `TestManifestAfterAuthenticode` в `core/update_test.go`
это закрепляет, а с `BOOP_TEST_SIGNED_EXE=/полный/путь/dist/Boop.exe go test ./core/`
проверяет настоящий подписанный файл против его `.sha256`.

## Что дальше

* Выбрать путь (Azure Trusted Signing или OV у УЦ) и купить / оформить.
* Если OV с токеном или облачным HSM — добавить в `build.sh` вариант PKCS#11
  (`osslsigncode -pkcs11module`) или утилиту поставщика.
* В CI — секреты (`BOOP_SIGN_PFX_BASE64` + `BOOP_SIGN_PASSWORD` или данные сервисного
  принципала Azure) и шаг публикации манифеста по порядку выше.
* Ed25519-подпись обновлений пока выключена: `core.UpdatePublicKey` пустой, клиент поле
  `signature` не требует, и в `desktop.json` его можно не писать (так выпускаются 1.3.0 и 1.3.1,
  см. `docs/RELEASE-1.3.1.md`). Чтобы включить: вшить открытый ключ в клиент и выпустить эту
  версию; начиная со следующей подписывать `signature` над байтами уже подписанного
  Authenticode `Boop.exe` (шаг 4 выше) и только потом публиковать манифест.
