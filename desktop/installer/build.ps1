<#
Builds dist\Boop-Setup-<version>.exe with Inno Setup 6.3+ from
  -Exe      the Tauri window (tauri\target\release\Boop.exe, cargo build --release -p boop-shell),
  -HostExe  the Go host (dist\BoopHost.exe) and
  -Service  the service (dist\BoopService.exe).
  pwsh installer\build.ps1 [-Version 2.0.0] [-Exe ...] [-HostExe ...] [-Service ...] [-OutDir dist]

The Go exes come from ./build.sh (they are already signed there when BOOP_SIGN is
set); the Tauri window is signed here (installer\sign-file.ps1) when BOOP_SIGN is set. If BOOP_SIGN is set here too, the setup program and its uninstaller are
signed (installer\sign-file.ps1); the .sha256 is written after signing. Without
it everything is built unsigned. The WebView2 Evergreen bootstrapper is
downloaded once into build\ and packed into the setup program.
#>
param(
  [string]$Version = '',
  [string]$Exe = '',
  [string]$HostExe = '',
  [string]$Service = '',
  [string]$OutDir = ''
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$desktop = Split-Path $here -Parent
if (-not $Version) {
  $m = Select-String -Path (Join-Path $desktop 'build.sh') -Pattern 'VERSION="\$\{1:-([0-9][^}]*)\}"' | Select-Object -First 1
  if (-not $m) { throw 'cannot read the default version from build.sh; pass -Version' }
  $Version = $m.Matches[0].Groups[1].Value
}
if (-not $Exe) { $Exe = Join-Path $desktop 'tauri\target\release\Boop.exe' }
if (-not $HostExe) { $HostExe = Join-Path $desktop 'dist\BoopHost.exe' }
if (-not $Service) { $Service = Join-Path $desktop 'dist\BoopService.exe' }
if (-not $OutDir) { $OutDir = Join-Path $desktop 'dist' }
$Exe = (Resolve-Path $Exe).Path
$HostExe = if (Test-Path $HostExe) { (Resolve-Path $HostExe).Path } else { '' }
# 1.4.x built one Boop.exe that was also the service; its setup has no second file.
$Service = if (Test-Path $Service) { (Resolve-Path $Service).Path } else { '' }
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

$iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source
if (-not $iscc) {
  foreach ($d in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
    $p = Join-Path $d 'Inno Setup 6\ISCC.exe'
    if (Test-Path $p) { $iscc = $p; break }
  }
}
if (-not $iscc) { throw 'ISCC.exe (Inno Setup 6.3+) not found: choco install innosetup' }

# WebView2 Evergreen bootstrapper: small, always fetches the current runtime. Its
# hash changes with Microsoft's releases, so the check is that Microsoft signed it.
$wv = Join-Path $desktop 'build\MicrosoftEdgeWebview2Setup.exe'
if (-not (Test-Path $wv)) {
  New-Item -ItemType Directory -Force (Split-Path $wv) | Out-Null
  Invoke-WebRequest -UseBasicParsing -Uri 'https://go.microsoft.com/fwlink/p/?LinkId=2124703' -OutFile $wv
}
$sig = Get-AuthenticodeSignature $wv
if ($sig.Status -ne 'Valid' -or $sig.SignerCertificate.Subject -notmatch 'Microsoft') {
  Remove-Item $wv -Force
  throw "WebView2 bootstrapper is not signed by Microsoft ($($sig.Status))"
}

# Wintun's own signed wintun.dll (build.sh fetches it into build\): the setup program installs it as it is.
$wintun = Join-Path $desktop 'build\wintun.dll'
if (-not (Test-Path $wintun)) { throw "no ${wintun}: run ./build.sh first" }
if ($env:BOOP_SIGN -and (Get-AuthenticodeSignature $Exe).Status -eq 'NotSigned') {
  # The Tauri window is built by cargo, so it is signed here, before it is packed.
  & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here 'sign-file.ps1') $Exe
  if ($LASTEXITCODE -ne 0) { throw "signing $Exe failed" }
}
if ($env:BOOP_SIGN) {
  # Everything inside the setup program must be signed before it is packed.
  foreach ($f in @($Exe, $HostExe, $Service, $wintun) | Where-Object { $_ }) {
    if ((Get-AuthenticodeSignature $f).Status -eq 'NotSigned') { throw "BOOP_SIGN is set, but $f is not signed" }
  }
}
$defs = @("/DAppVersion=$Version", "/DSourceExe=$Exe", "/DWintunDll=$wintun", "/DWebView2Setup=$wv", "/DOutDir=$OutDir")
if ($HostExe) { $defs += "/DSourceHost=$HostExe" }
if ($Service) { $defs += "/DSourceService=$Service" }
if ($env:BOOP_SIGN) {
  $sign = 'pwsh -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $here 'sign-file.ps1') + '" "$f"'
  $defs += @('/DSignCmd=1', "/Sboop=$sign")
}
& $iscc @defs (Join-Path $here 'boop.iss')
if ($LASTEXITCODE -ne 0) { throw "ISCC failed ($LASTEXITCODE)" }

$out = Join-Path $OutDir "Boop-Setup-$Version.exe"
if (-not (Test-Path $out)) { throw "no $out" }
$hash = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
"$hash  Boop-Setup-$Version.exe" | Set-Content -Encoding ascii "$out.sha256"
Write-Host "built $out"
Write-Host "SHA-256: $hash"

# desktop.json for the panel (not published; the panel's document is replaced by hand).
# The 1.4.x fields (version/url/sha256/signature) name the bridge, Boop-bridge.exe:
# what a 1.4.x service downloads, checks and swaps in for its own Boop.exe, and which
# then runs this setup program (docs/RELEASE-2.0.0.md). They are written when
# BOOP_LEGACY_URL (an absolute https URL where the bridge will be published) is set;
# the hash is BOOP_LEGACY_SHA256 or, if absent, the hash of the signed bridge next to
# the other exes. BOOP_LEGACY_SIGNATURE (ed25519, base64) is copied as it is.
# The installer fields name this setup program, hashed AFTER signing (it is the
# last step above). Order: sign the exes -> bridge hash -> setup program -> sign it -> hash.
$json = [ordered]@{
  installer_version = $Version
  installer_url     = if ($env:BOOP_INSTALLER_URL) { $env:BOOP_INSTALLER_URL } else { "/download/Boop-Setup-$Version.exe" }
  installer_sha256  = $hash
  min_proto         = 3
  kind              = 'installer'
}
if ($env:BOOP_LEGACY_URL) {
  $bridge = Join-Path (Split-Path $Service -Parent) 'Boop-bridge.exe'
  $legacyHash = $env:BOOP_LEGACY_SHA256
  if (-not $legacyHash -and (Test-Path $bridge)) { $legacyHash = (Get-FileHash $bridge -Algorithm SHA256).Hash.ToLower() }
  if (-not $legacyHash) { throw 'BOOP_LEGACY_URL is set, but there is neither BOOP_LEGACY_SHA256 nor a Boop-bridge.exe next to BoopService.exe' }
  if ($env:BOOP_SIGN -and (Test-Path $bridge) -and (Get-AuthenticodeSignature $bridge).Status -eq 'NotSigned') { throw "BOOP_SIGN is set, but $bridge is not signed" }
  $legacy = [ordered]@{ version = $Version; url = $env:BOOP_LEGACY_URL; sha256 = $legacyHash; mandatory = $false }
  if ($env:BOOP_LEGACY_SIGNATURE) { $legacy.signature = $env:BOOP_LEGACY_SIGNATURE }
  $json = $legacy + $json
}
$json | ConvertTo-Json | Set-Content -Encoding utf8NoBOM (Join-Path $OutDir 'desktop.json')
Write-Host "wrote $(Join-Path $OutDir 'desktop.json')"
