; Boop VPN for Windows: the setup program (Inno Setup 6.3+).
;
; Since 1.5.1 this script does ALL the system work; neither Boop.exe nor
; BoopService.exe installs, registers or removes anything (they have no flags for
; it). Since 1.5.3 Boop.exe is the Tauri window and tray (desktop/tauri), and the
; Go host it starts (BoopHost.exe: core.App, system toasts, autostart value)
; sits next to it; the Go window is not installed. It puts Boop.exe,
; BoopHost.exe, BoopService.exe (the service) and Wintun's
; own signed wintun.dll in %ProgramFiles%\Boop, sets the rights on the directory
; (Permissions in the Dirs section), registers or re-points, configures and starts
; the service BoopVPN (sc.exe, run by the Pascal code inside the setup only),
; writes the user's autostart value and removes what 1.3.x/1.4.x left behind (the
; scheduled task). See installer/README.md.
;
; ISCC /DAppVersion=2.0.2 /DSourceExe=..\tauri\target\release\Boop.exe /DSourceHost=..\dist\BoopHost.exe /DSourceService=..\dist\BoopService.exe [/DWintunDll=...] [/DWebView2Setup=...] [/DSignCmd=1] boop.iss

#ifndef AppVersion
  #define AppVersion "2.0.2"
#endif
#ifndef SourceExe
  #define SourceExe "..\tauri\target\release\Boop.exe"
#endif
#ifndef SourceHost
  #define SourceHost "..\dist\BoopHost.exe"
#endif
#ifndef SourceService
  #define SourceService "..\dist\BoopService.exe"
#endif
#ifndef WintunDll
  #define WintunDll "..\build\wintun.dll"
#endif
#ifndef OutDir
  #define OutDir "..\dist"
#endif

[Setup]
; Never change the AppId: it is how a new setup finds the old install.
AppId={{6D1B6F0E-4C58-4B7B-9A7B-3F0B8C5E2A64}
AppName=Boop VPN
AppVersion={#AppVersion}
AppVerName=Boop VPN {#AppVersion}
AppPublisher=Boop
AppPublisherURL=https://boopvpn.app
AppSupportURL=https://boopvpn.app
VersionInfoVersion={#AppVersion}.0
VersionInfoProductName=Boop VPN
VersionInfoDescription=Boop VPN setup
; One UAC prompt for the whole install. The fixed path is the one the service
; and the updater use, so it cannot be changed.
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DefaultDirName={commonpf64}\Boop
DisableDirPage=yes
DefaultGroupName=Boop VPN
DisableProgramGroupPage=yes
UninstallDisplayName=Boop VPN
UninstallDisplayIcon={app}\Boop.exe
OutputDir={#OutDir}
OutputBaseFilename=Boop-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; The running window (and the service's old copy) are closed before files are
; replaced; the program is not started again by Inno, the last step does it.
CloseApplications=yes
RestartApplications=no
#ifdef SignCmd
SignTool=boop
SignedUninstaller=yes
#endif

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "autostart"; Description: "Запускать Boop при входе в Windows"; GroupDescription: "Дополнительно:"
Name: "desktopicon"; Description: "Ярлык на рабочем столе"; GroupDescription: "Дополнительно:"; Flags: unchecked

[Dirs]
; The program directory: SYSTEM and administrators in full, ordinary users read
; and run only. No owner change; what %ProgramFiles% already gives is kept.
Name: "{app}"; Permissions: system-full admins-full users-readexec

[Files]
Source: "{#SourceExe}"; DestDir: "{app}"; DestName: "Boop.exe"; Flags: ignoreversion
Source: "{#SourceHost}"; DestDir: "{app}"; DestName: "BoopHost.exe"; Flags: ignoreversion
Source: "{#SourceService}"; DestDir: "{app}"; DestName: "BoopService.exe"; Flags: ignoreversion
; Wintun's own file, with its own signature (never unpacked by program code).
Source: "{#WintunDll}"; DestDir: "{app}"; DestName: "wintun.dll"; Flags: ignoreversion
#ifdef WebView2Setup
Source: "{#WebView2Setup}"; DestDir: "{tmp}"; DestName: "MicrosoftEdgeWebview2Setup.exe"; Flags: deleteafterinstall; Check: not WebView2Installed
#endif

[Icons]
; AppUserModelID: the toast notifications (Boop.exe registers the same id, toast/toast.go).
Name: "{autoprograms}\Boop VPN"; Filename: "{app}\Boop.exe"; AppUserModelID: "Boop.VPN"
Name: "{autodesktop}\Boop VPN"; Filename: "{app}\Boop.exe"; Tasks: desktopicon

[Run]
; The service itself is set up by the Pascal code (ssPostInstall), before these.
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Установка Microsoft Edge WebView2..."; Flags: skipifdoesntexist waituntilterminated; Check: not WebView2Installed
; Toasts: the name, icon and boop: handler in the user's HKCU (the host also does it at every start of the installed copy).
Filename: "{app}\BoopHost.exe"; Parameters: "--toast-register"; Flags: runasoriginaluser runhidden waituntilterminated; Check: not FromBridge
Filename: "{app}\Boop.exe"; Description: "Запустить Boop VPN"; Flags: nowait postinstall skipifsilent runasoriginaluser
; Update from the running app (it starts this setup with /SILENT /BOOPRESTART=1): postinstall entries are skipped in silent mode, so start the window again here.
Filename: "{app}\Boop.exe"; Flags: nowait runasoriginaluser; Check: RestartAfterUpdate

[Registry]
; Autostart of the installed copy: HKCU Run, the same value the window writes when
; the person flips the switch (winsvc.RunKeyName), started to the tray. HKCU
; because it is per user and the window needs no rights to change it. (When a
; different administrator elevates the setup, HKCU is theirs: the window's switch fixes it.)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "BoopVPN"; ValueData: """{app}\Boop.exe"" --tray"; Flags: uninsdeletevalue; Tasks: autostart; Check: not FromBridge
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "BoopVPN"; Flags: deletevalue; Tasks: not autostart; Check: not FromBridge
; Toast keys: removed by the uninstaller itself.
Root: HKCU; Subkey: "Software\Classes\AppUserModelId\Boop.VPN"; Flags: uninsdeletekey; Check: not FromBridge
Root: HKCU; Subkey: "Software\Classes\boop"; Flags: uninsdeletekey; Check: not FromBridge

[Code]
const
  ServiceName = 'BoopVPN';
  ServiceKey = 'SYSTEM\CurrentControlSet\Services\BoopVPN';
  RunKey = 'Software\Microsoft\Windows\CurrentVersion\Run';
  OldTaskName = 'Boop VPN';
  WebView2Key = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';

procedure ExitProcess(ExitCode: Cardinal); external 'ExitProcess@kernel32.dll stdcall';

var
  PurgeData: Boolean;
  HadAutostart: Boolean;

function HasParam(const Name: String): Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), Name) = 0 then
      Result := True;
end;

{ /BOOPBRIDGE=1: started by the 1.4.x transition service (cmd/boop-bridge), which
  is SYSTEM. HKCU and "the original user" are SYSTEM's then, so nothing per-user is
  written: the person's Run value is the one 1.4.x wrote and already points at
  Boop.exe --tray; the window is started by the new service in the user's session. }
function FromBridge(): Boolean;
begin
  Result := ExpandConstant('{param:BOOPBRIDGE|0}') = '1';
end;

function RestartAfterUpdate(): Boolean;
begin
  Result := (ExpandConstant('{param:BOOPRESTART|0}') = '1') and not FromBridge();
end;

function IsRealVersion(const V: String): Boolean;
begin
  Result := (V <> '') and (V <> '0.0.0.0');
end;

{ The Evergreen WebView2 Runtime, as Microsoft documents its detection. }
function WebView2Installed(): Boolean;
var
  V: String;
begin
  Result := (RegQueryStringValue(HKLM32, WebView2Key, 'pv', V) and IsRealVersion(V))
         or (RegQueryStringValue(HKLM64, WebView2Key, 'pv', V) and IsRealVersion(V))
         or (RegQueryStringValue(HKCU, WebView2Key, 'pv', V) and IsRealVersion(V));
end;

function ServiceExists(): Boolean;
begin
  Result := RegKeyExists(HKLM, ServiceKey);
end;

{ Runs a system tool hidden and waits; false when it could not start. }
function RunSys(const Exe, Params: String; var Code: Integer): Boolean;
begin
  Result := Exec(ExpandConstant('{sys}\') + Exe, Params, '', SW_HIDE, ewWaitUntilTerminated, Code);
  Log(Exe + ' ' + Params + ' -> ' + IntToStr(Code));
end;

{ The scheduled task that 1.3.x/1.4.x used to start the elevated window. }
function OldTaskExists(): Boolean;
var
  Code: Integer;
begin
  Result := RunSys('schtasks.exe', '/Query /TN "' + OldTaskName + '"', Code) and (Code = 0);
end;

procedure RemoveOldTask();
var
  Code: Integer;
begin
  if OldTaskExists() then
    RunSys('schtasks.exe', '/Delete /TN "' + OldTaskName + '" /F', Code);
end;

{ The person's autostart before this setup: the Run value, or the old task. }
function InitializeSetup(): Boolean;
begin
  Result := True;
  HadAutostart := RegValueExists(HKCU, RunKey, 'BoopVPN') or OldTaskExists();
end;

{ Over an existing install the checkbox starts as the person had it (1.4.x: the
  Run value or the old task); /TASKS on the command line still wins. }
procedure InitializeWizard();
begin
  if (ExpandConstant('{param:TASKS|}') = '') and ServiceExists() then
  begin
    if HadAutostart then
      WizardSelectTasks('autostart')
    else
      WizardSelectTasks('!autostart');
  end;
end;

{ Stop the service so its program can be replaced (net stop waits for it). The
  config it remembers brings the tunnel back when it starts again. Windows that
  are open are closed by CloseApplications. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Result := '';
  if ServiceExists() then
    RunSys('net.exe', 'stop ' + ServiceName, Code);
end;

function ScOk(const Params: String): Boolean;
var
  Code: Integer;
begin
  Result := RunSys('sc.exe', Params, Code) and (Code = 0);
end;

{ Registers the service (or re-points an existing one: 1.4.x had Boop.exe --service)
  as LocalSystem, automatic start, restart after a failure, and starts it. }
function SetUpService(var Why: String): Boolean;
var
  Bin: String;
begin
  Result := False;
  { Inner quotes are escaped: sc.exe parses its command line like any program. }
  Bin := '\"' + ExpandConstant('{app}\BoopService.exe') + '\" --service';
  if ServiceExists() then
  begin
    if not ScOk('config ' + ServiceName + ' binPath= "' + Bin + '" start= auto obj= LocalSystem DisplayName= "Boop VPN"') then
    begin Why := 'sc config'; Exit; end;
  end
  else
  begin
    if not ScOk('create ' + ServiceName + ' binPath= "' + Bin + '" start= auto obj= LocalSystem DisplayName= "Boop VPN"') then
    begin Why := 'sc create'; Exit; end;
  end;
  ScOk('description ' + ServiceName + ' "Holds Boop VPN''s tunnel so the window runs without administrator rights."');
  if not ScOk('failure ' + ServiceName + ' reset= 3600 actions= restart/1000/restart/5000/restart/30000') then
  begin Why := 'sc failure'; Exit; end;
  ScOk('failureflag ' + ServiceName + ' 1');
  if not ScOk('start ' + ServiceName) then
  begin Why := 'sc start'; Exit; end;
  Result := True;
end;

{ %ProgramData%\Boop (the service's log and remembered config): SYSTEM and
  administrators only, no inheritance (ProgramData gives ordinary users create rights). }
procedure SecureDataDir();
var
  Dir: String;
  Code: Integer;
begin
  Dir := ExpandConstant('{commonappdata}\Boop');
  ForceDirectories(Dir);
  RunSys('icacls.exe', '"' + Dir + '" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F', Code);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Why, Msg: String;
begin
  if CurStep = ssPostInstall then
  begin
    SecureDataDir();
    RemoveOldTask();
    if not SetUpService(Why) then
    begin
      Log('service setup failed: ' + Why);
      Msg := 'Файлы Boop VPN установлены, но службу настроить не удалось (' + Why + ').' + #13#10 +
             'Запустите установщик ещё раз от имени администратора. Подробности в журнале установки.';
      SuppressibleMsgBox(Msg, mbError, MB_OK, IDOK);
      { An unattended install must fail loudly. }
      if WizardSilent then
        ExitProcess(10);
    end;
  end;
end;

function InitializeUninstall(): Boolean;
begin
  Result := True;
  PurgeData := HasParam('/PURGEDATA');
  if (not PurgeData) and (not UninstallSilent) then
    PurgeData := SuppressibleMsgBox('Удалить также настройки и вход в аккаунт Boop VPN?' + #13#10 +
      'Если вы собираетесь установить Boop снова, нажмите «Нет».',
      mbConfirmation, MB_YESNO or MB_DEFBUTTON2, IDNO) = IDYES;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Code: Integer;
begin
  if CurUninstallStep = usUninstall then
  begin
    { The service goes first (its tunnel, the adapter and the firewall rules go
      with its process), then any open window, the old task and the autostart
      value; the files are Inno's to delete. }
    if ServiceExists() then
    begin
      RunSys('net.exe', 'stop ' + ServiceName, Code);
      RunSys('sc.exe', 'delete ' + ServiceName, Code);
    end;
    RunSys('taskkill.exe', '/F /T /IM Boop.exe', Code);
    RunSys('taskkill.exe', '/F /T /IM BoopHost.exe', Code);
    RunSys('taskkill.exe', '/F /T /IM BoopService.exe', Code);
    RemoveOldTask();
    RegDeleteValue(HKCU, RunKey, 'BoopVPN');
  end;
  if (CurUninstallStep = usPostUninstall) and PurgeData then
  begin
    DelTree(ExpandConstant('{localappdata}\Boop'), True, True, True);
    DelTree(ExpandConstant('{commonappdata}\Boop'), True, True, True);
  end;
end;

[InstallDelete]
; What the 1.4.x self-update left next to the program (a swapped-out Boop.exe).
Type: files; Name: "{app}\Boop.exe.old"
Type: files; Name: "{app}\Boop.exe.new"

[UninstallDelete]
; What the client or the service leaves next to the program.
Type: files; Name: "{app}\Boop.exe.old"
Type: files; Name: "{app}\Boop.exe.new"
Type: files; Name: "{app}\BoopHost.exe.old"
Type: files; Name: "{app}\BoopHost.exe.new"
Type: files; Name: "{app}\BoopService.exe.old"
Type: files; Name: "{app}\BoopService.exe.new"
Type: dirifempty; Name: "{app}"
