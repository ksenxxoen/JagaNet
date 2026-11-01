; Boop VPN for Windows: the setup program (Inno Setup 6.3+).
;
; It puts Boop.exe in %ProgramFiles%\Boop (the path winsvc.InstallDir uses) and
; lets Boop.exe itself register the service (`Boop.exe --install-service`), so
; the service, the directory rights and the old-task cleanup are the same code
; as when the window installs the service. See installer/README.md.
;
; ISCC /DAppVersion=1.4.1 /DSourceExe=..\dist\Boop.exe [/DWebView2Setup=...] [/DSignCmd=1] boop.iss

#ifndef AppVersion
  #define AppVersion "1.4.1"
#endif
#ifndef SourceExe
  #define SourceExe "..\dist\Boop.exe"
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

[Files]
; wintun.dll is embedded in Boop.exe and written next to it by --install-service.
Source: "{#SourceExe}"; DestDir: "{app}"; DestName: "Boop.exe"; Flags: ignoreversion
#ifdef WebView2Setup
Source: "{#WebView2Setup}"; DestDir: "{tmp}"; DestName: "MicrosoftEdgeWebview2Setup.exe"; Flags: deleteafterinstall; Check: not WebView2Installed
#endif

[Icons]
Name: "{autoprograms}\Boop VPN"; Filename: "{app}\Boop.exe"
Name: "{autodesktop}\Boop VPN"; Filename: "{app}\Boop.exe"; Tasks: desktopicon

[Run]
; The service itself is installed from [Code] (ssPostInstall), before these.
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Установка Microsoft Edge WebView2..."; Flags: skipifdoesntexist waituntilterminated; Check: not WebView2Installed
; The user's own Run key: these two run as the user who started the setup, not
; as the elevated process (whose HKCU may be somebody else's).
Filename: "{app}\Boop.exe"; Parameters: "--autostart-on"; Flags: runasoriginaluser runhidden waituntilterminated; Tasks: autostart
Filename: "{app}\Boop.exe"; Parameters: "--autostart-off"; Flags: runasoriginaluser runhidden waituntilterminated; Tasks: not autostart
Filename: "{app}\Boop.exe"; Description: "Запустить Boop VPN"; Flags: nowait postinstall skipifsilent runasoriginaluser

[Code]
const
  ServiceName = 'BoopVPN';
  WebView2Key = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';

procedure ExitProcess(ExitCode: Cardinal); external 'ExitProcess@kernel32.dll stdcall';

var
  PurgeData: Boolean;

function HasParam(const Name: String): Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), Name) = 0 then
      Result := True;
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

{ UTF-8 bytes (what Boop.exe writes to the result file) to a string. }
function MultiByteToWideChar(CodePage: Cardinal; Flags: Cardinal; const MultiByte: AnsiString;
  MultiByteLen: Integer; WideChar: String; WideCharLen: Integer): Integer;
  external 'MultiByteToWideChar@kernel32.dll stdcall';

function Utf8ToText(const B: AnsiString): String;
var
  N: Integer;
begin
  Result := '';
  if B = '' then
    Exit;
  N := MultiByteToWideChar(65001, 0, B, Length(B), Result, 0);
  if N <= 0 then
    Exit;
  SetLength(Result, N);
  MultiByteToWideChar(65001, 0, B, Length(B), Result, N);
end;

{ Stop the service so Boop.exe can be replaced (net stop waits for it). The
  config it remembers brings the tunnel back when it starts again. Windows that
  are open are closed by CloseApplications. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Result := '';
  Exec(ExpandConstant('{sys}\net.exe'), 'stop ' + ServiceName, '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

procedure InstallService();
var
  Code: Integer;
  ResultFile, Raw, Why, Msg: String;
  RawA: AnsiString;
begin
  ResultFile := ExpandConstant('{tmp}\boop-install-result.txt');
  DeleteFile(ResultFile);
  if not Exec(ExpandConstant('{app}\Boop.exe'), '--install-service --result "' + ResultFile + '"',
              '', SW_HIDE, ewWaitUntilTerminated, Code) then
    Code := -1;
  if Code = 0 then
  begin
    Log('service installed');
    Exit;
  end;
  Why := 'код выхода ' + IntToStr(Code);
  if LoadStringFromFile(ResultFile, RawA) and (RawA <> '') then
    Why := Utf8ToText(RawA);
  Log('service install failed: ' + Why);
  Msg := 'Файлы Boop VPN установлены, но службу установить не удалось.' + #13#10#13#10 + Why + #13#10#13#10 +
         'Журнал: ' + ExpandConstant('{commonappdata}\Boop\install.log') + #13#10 +
         'Boop при запуске попробует установить службу ещё раз (или запустится в резервном режиме).';
  SuppressibleMsgBox(Msg, mbError, MB_OK, IDOK);
  { An unattended install must fail loudly. }
  if WizardSilent then
    ExitProcess(10);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    InstallService();
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
      with its process), then any open window; the files are Inno's to delete. }
    if not Exec(ExpandConstant('{app}\Boop.exe'), '--remove-service', '', SW_HIDE, ewWaitUntilTerminated, Code)
       or (Code <> 0) then
      Log('remove-service failed, code ' + IntToStr(Code));
    Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /T /IM Boop.exe', '', SW_HIDE, ewWaitUntilTerminated, Code);
  end;
  if (CurUninstallStep = usPostUninstall) and PurgeData then
  begin
    DelTree(ExpandConstant('{localappdata}\Boop'), True, True, True);
    DelTree(ExpandConstant('{commonappdata}\Boop'), True, True, True);
  end;
end;

[UninstallDelete]
; What the client or the service leaves next to the program.
Type: files; Name: "{app}\wintun.dll"
Type: files; Name: "{app}\Boop.exe.old"
Type: files; Name: "{app}\Boop.exe.new"
Type: dirifempty; Name: "{app}"
