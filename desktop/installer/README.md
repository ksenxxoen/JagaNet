# Установщик Boop-Setup (Inno Setup)

`Boop-Setup-<версия>.exe` ставит клиент для Windows так, как это делают обычные программы:
`%ProgramFiles%\Boop`, ярлыки, запись в «Установленных приложениях», удаление. Версия та же, что у
`Boop.exe` (окно) и `BoopService.exe` (служба) (по умолчанию из `build.sh`).

## Что делает установщик

Один UAC на всю установку (`PrivilegesRequired=admin`). Путь фиксирован: это тот же
`%ProgramFiles%\Boop`, что использует `winsvc.InstallDir`, его нельзя менять.

1. **Остановка.** `net stop BoopVPN`; открытые окна Boop закрывает Inno (`CloseApplications`).
2. **Файлы и права.** `Boop.exe`, `BoopService.exe` и `wintun.dll` (файл Wintun с исходной подписью, из
   `build/wintun.dll`) в `%ProgramFiles%\Boop`. Права каталога — `Permissions:` в `[Dirs]`
   (SYSTEM и администраторы — полный, пользователи — чтение и запуск), владелец не меняется.
   `%ProgramData%\Boop` закрывается `icacls /inheritance:r` (SYSTEM и администраторы).
3. **Служба.** `sc.exe create` (или `config`, если служба уже есть: так 1.4.x перенацеливается на
   `BoopService.exe`) — LocalSystem, автозапуск; `sc failure` (перезапуск), `sc start`. Это делает только
   установщик (`[Code]`); ни `Boop.exe`, ни `BoopService.exe` службу не ставят. Если шаг не прошёл,
   показывается сообщение, а тихая установка (`/VERYSILENT`) завершается кодом 10. Старая задача планировщика
   `Boop VPN` (1.3.x/1.4.x) удаляется (`schtasks /Delete`, только внутри установщика).
4. **WebView2.** Окно клиента построено на WebView2 (`go-webview2`). Если Evergreen Runtime не найден
   (реестр `EdgeUpdate\Clients\{F3017226-…}`), запускается упакованный в установщик bootstrapper
   Microsoft, тихо (`/silent /install`, нужна сеть). Не вышло — не страшно, клиент сам откроет страницу загрузки.
5. **Ярлыки и автозапуск.** Меню «Пуск»; рабочий стол — по галочке. «Запускать при входе в Windows» —
   значение `BoopVPN` в `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` со значением
   `"…\Boop.exe" --tray` (`[Registry]`, по галочке; снятая галочка удаляет значение). Выбран HKCU, а не HKLM:
   настройка личная, и окно переключает её без прав администратора (напрямую через API реестра, только в
   установленной копии, без `reg.exe`/`schtasks`/`powershell`). Если установщик запустил другой
   администратор, HKCU — его: выключатель в окне это исправляет. Поверх существующей установки галочка
   стоит так, как было у человека (значение Run или старая задача); `/TASKS=` важнее.
6. **Запуск.** Последний шаг мастера запускает окно от обычного пользователя (без UAC).

## Обновление поверх

Тот же `AppId` — установщик находит прежнюю установку.

* Поверх 1.4.x: служба останавливается, файлы заменяются, `sc config` перенацеливает службу на
  `BoopService.exe`, старая задача планировщика удаляется, состояние автозапуска сохраняется; токен и настройки (`%LOCALAPPDATA%\Boop`, `%ProgramData%\Boop`) не трогаются, туннель
  поднимается из запомненной конфигурации.
* Поверх «голой» 1.3.x с задачей планировщика: задача удаляется; автозапуск переносится в Run-значение,
  если он был включён.
* Автоапдейтер клиента (`desktop.json`, подмена `Boop.exe` в `%ProgramFiles%\Boop`) работает для окна; службу `BoopService.exe` с 1.5.1 заменяет только установщик (манифест называет один файл; `winsvc.ErrUpdateByInstaller`, этап C-5 эпика #76);
  манифест и его формат не менялись. **1.5.1 нельзя публиковать в полях `desktop.json`, которые читает 1.4.x** — только через установщик (служба 1.4.x подменила бы `Boop.exe` и перестала стартовать, см. `docs/CHANGELOG-1.5.1.md`; этапы C-5/C-6). `Boop.exe --service` из старой записи SCM выходит с кодом 11 и пишет `%ProgramData%\Boop\service-legacy.log`. Файлы, которые он подменил, деинсталлятор тоже удалит.

## Удаление

«Установленные приложения» → Boop VPN → Удалить (или `unins000.exe`):

* остановка и `sc delete BoopVPN` (адаптер и правила WFP уходят вместе с процессом службы), удаление задачи
  `Boop VPN` старых версий и Run-значения; файлы удаляет сам деинсталлятор;
* закрываются оставшиеся окна, удаляются файлы и ярлыки;
* данные (`%LOCALAPPDATA%\Boop`, токен, и `%ProgramData%\Boop`) по умолчанию остаются. Вопрос в обычном
  режиме, `/PURGEDATA` в тихом. Данные чистятся для профиля пользователя, под которым идёт удаление.

## Портативная копия

`Boop.exe` вне `%ProgramFiles%\Boop` ничего не регистрирует и не распаковывает; без службы окно предлагает
скачать установщик (страница `/download/windows` панели).

## Сборка

Нужны Windows и [Inno Setup](https://jrsoftware.org/isinfo.php) 6.3+ (`choco install innosetup`).

```
./build.sh                       # на Linux/macOS/WSL: dist/Boop.exe
pwsh installer/build.ps1         # dist/Boop-Setup-<версия>.exe и .sha256
pwsh installer/build.ps1 -Version 1.5.1 -Exe dist/Boop.exe -Service dist/BoopService.exe -OutDir dist
```

`build.ps1` скачивает bootstrapper WebView2 в `build/` (проверяет подпись Microsoft) и упаковывает его.
Без Windows установщик не собрать (Inno через wine не проверялся).

В CI (`.github/workflows/desktop.yml`, джоб `windows-installer`) установщик собирается из того же
`Boop.exe`, что собрал джоб `build`, и выкладывается артефактом `Boop-Setup-<sha>` (внутри
`Boop-Setup-1.5.1.exe`). Там же e2e: `/VERYSILENT` → служба Running → повторная установка → тихое
удаление → служба и файлы удалены → то же с `/PURGEDATA`.

## Подпись

Если задан `BOOP_SIGN` (см. [../SIGNING.md](../SIGNING.md)), `build.ps1` подписывает установщик и
деинсталлятор через `SignTool` Inno (`sign-file.ps1`: те же переменные, что в `build.sh`, поддержаны
`osslsigncode` и `azure`/jsign). `Boop.exe` внутри должен быть подписан заранее (`./build.sh` с тем же
`BOOP_SIGN`). `.sha256` пишется после подписи. Без `BOOP_SIGN` всё собирается неподписанным.

## Тихая установка

`Boop-Setup-<версия>.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART [/TASKS="autostart,desktopicon"]`
`unins000.exe /VERYSILENT [/PURGEDATA]`

## С 1.5.3: что внутри

`Boop.exe` — Tauri-окно (`cargo build --release -p boop-shell` в `tauri/`), `BoopHost.exe` — Go-хост (`build.sh`), `BoopService.exe` и
`wintun.dll`. `pwsh installer/build.ps1 -Exe tauri/target/release/Boop.exe -HostExe dist/BoopHost.exe -Service dist/BoopService.exe`
(это значения по умолчанию). При `BOOP_SIGN` Tauri-`Boop.exe` подписывается в `build.ps1`, Go-файлы — в `build.sh`.

Заметка (переходный период): в CI `dist/Boop.exe` и `dist/BoopHost.exe` — одна и та же Go-сборка (одинаковый SHA-256), это задумано:
`BoopHost.exe` — копия Go-программы под именем хоста. Установщик берёт `Boop.exe` из `tauri/target/release/` (`-Exe`), а не из `dist/`;
из `dist/` в него попадают только `BoopHost.exe`, `BoopService.exe` и `wintun.dll`.

## Started by the 1.4.x bridge (`/BOOPBRIDGE=1`, since 2.0.0)

`Boop-bridge.exe` (cmd/boop-bridge, see `docs/RELEASE-2.0.0.md`) runs the setup as SYSTEM with
`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /BOOPBRIDGE=1`. With that switch every per-user entry is skipped
(`Check: not FromBridge`: HKCU Run, toast keys, `--toast-register`, which would all land in SYSTEM's profile) and
`/BOOPRESTART=1` does nothing — the window must not start as SYSTEM. The new `BoopService.exe` starts it in the user's session.
`[InstallDelete]` removes `Boop.exe.old`/`.new` left by the 1.4.x swap.
