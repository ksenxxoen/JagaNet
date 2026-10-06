# JagaNet - one-click setup and launcher for Windows.
# Started by "Start JagaNet.bat" in the project folder. Works with Windows PowerShell 5.1.
#
# First run downloads, into one folder (no admin rights needed):
#   - Java 21 (Eclipse Temurin)
#   - Android command-line tools, emulator and a virtual phone image
# and creates a virtual phone called "JagaNet". Later runs reuse all of it.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 misreads UTF-8 without a BOM.

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $Root

# Tools folder. The Android emulator breaks on paths with spaces or non-English letters,
# so fall back to C:\JagaNetTools when the user profile path has them.
$Tools = Join-Path $env:LOCALAPPDATA "JagaNet"
if ($Tools -notmatch '^[A-Za-z0-9:\\._-]+$') { $Tools = "C:\JagaNetTools" }
$Jdk = Join-Path $Tools "jdk"
$Sdk = Join-Path $Tools "android-sdk"
$Logs = Join-Path $Root "build\run"
$Port = 4000
$Api = "http://localhost:$Port"
$Avd = "JagaNet"
$SysImage = "system-images;android-35;google_apis;x86_64"
$Branch = "claude/adoring-brown-s8fqex"
$Repo = "https://github.com/ksenxxoen/JagaNet"
$Canvas = "https://claude.ai/artifact/V75PnoYtstCU4PBDSVeufG"

New-Item -ItemType Directory -Force -Path $Tools, $Logs | Out-Null
$env:ANDROID_USER_HOME = Join-Path $Tools "android-user"
$env:ANDROID_AVD_HOME = Join-Path $Tools "avd"
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME | Out-Null

# ------------------------------------------------------------------ output helpers

function Title($text) {
    Write-Host ""
    Write-Host "== $text ==" -ForegroundColor Cyan
}
function Step($text) { Write-Host "  > $text" -ForegroundColor Green }
function Note($text) { Write-Host "    $text" -ForegroundColor Gray }
function Warn($text) { Write-Host "  ! $text" -ForegroundColor Yellow }
function Fail($text) { throw $text }
function Pause-Menu { Write-Host ""; Read-Host "Press Enter to go back to the menu" | Out-Null }

# ------------------------------------------------------------------ downloads

function Download($url, $file) {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        & curl.exe -L --fail --retry 3 --progress-bar -o $file $url
        if ($LASTEXITCODE -ne 0) { Fail "Download failed: $url" }
    } else {
        Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
    }
}

function Unzip($zip, $dest) {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $zip -C $dest
        if ($LASTEXITCODE -eq 0) { return }
    }
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}

function Check-Space {
    $drive = (Split-Path -Qualifier $Tools).TrimEnd(':')
    $free = (Get-PSDrive -Name $drive).Free / 1GB
    if ($free -lt 8) { Fail ("Not enough disk space on drive {0}: {1:N1} GB free, about 8 GB needed for the first setup." -f $drive, $free) }
}

# ------------------------------------------------------------------ Java

function Ensure-Java {
    $java = Join-Path $Jdk "bin\java.exe"
    if (-not (Test-Path $java)) {
        Title "Installing Java 21 (one time, about 190 MB)"
        Check-Space
        $arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "aarch64" } else { "x64" }
        $zip = Join-Path $Tools "jdk.zip"
        Download "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$arch/jdk/hotspot/normal/eclipse" $zip
        $tmp = Join-Path $Tools "jdk-tmp"
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Unzip $zip $tmp
        $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
        if (Test-Path $Jdk) { Remove-Item -Recurse -Force $Jdk }
        Move-Item $inner.FullName $Jdk
        Remove-Item -Recurse -Force $tmp, $zip
        Step "Java installed"
    }
    $env:JAVA_HOME = $Jdk
    $env:Path = (Join-Path $Jdk "bin") + ";" + $env:Path
}

# ------------------------------------------------------------------ Android

function SdkTool($name) { Join-Path $Sdk "cmdline-tools\latest\bin\$name.bat" }

function Ensure-AndroidSdk {
    $marker = Join-Path $Sdk ".jaganet-ready"
    if (-not (Test-Path $marker)) {
        if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") {
            Fail "The Android emulator does not run on ARM-based Windows PCs. Use option 2 (desktop window) instead."
        }
        Title "Installing the Android tools and a virtual phone (one time, about 3 GB)"
        Note "This is the longest step. Leave the window open; it can take 10-30 minutes."
        Check-Space
        if (-not (Test-Path (SdkTool "sdkmanager"))) {
            $zip = Join-Path $Tools "cmdline-tools.zip"
            Download "https://dl.google.com/android/repository/commandlinetools-win-9862592_latest.zip" $zip
            $tmp = Join-Path $Tools "cmdline-tmp"
            if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
            Unzip $zip $tmp
            $dest = Join-Path $Sdk "cmdline-tools\latest"
            if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
            New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
            Move-Item (Join-Path $tmp "cmdline-tools") $dest
            Remove-Item -Recurse -Force $tmp, $zip
        }
        Step "Accepting the Android SDK licenses"
        ("y`n" * 40) | & (SdkTool "sdkmanager") --sdk_root="$Sdk" --licenses | Out-Null
        Step "Downloading the emulator and the phone image"
        & (SdkTool "sdkmanager") --sdk_root="$Sdk" "platform-tools" "emulator" $SysImage
        if ($LASTEXITCODE -ne 0) { Fail "Installing Android packages failed (see the messages above)." }
        Set-Content -Path $marker -Value (Get-Date).ToString("s")
        Step "Android tools installed"
    }
    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    $env:Path = (Join-Path $Sdk "platform-tools") + ";" + (Join-Path $Sdk "emulator") + ";" + $env:Path
    # Gradle reads the SDK location from local.properties.
    $props = Join-Path $Root "local.properties"
    Set-Content -Path $props -Value ("sdk.dir=" + ($Sdk -replace '\\', '/')) -Encoding ASCII
}

function Ensure-Avd {
    $existing = & (Join-Path $Sdk "emulator\emulator.exe") -list-avds 2>$null
    if ($existing -contains $Avd) { return }
    Step "Creating the virtual phone `"$Avd`""
    "no" | & (SdkTool "avdmanager") create avd -n $Avd -k $SysImage -d pixel_6 --force | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "Creating the virtual phone failed." }
    # Let the computer keyboard type into the phone, give it enough memory.
    $cfg = Join-Path $env:ANDROID_AVD_HOME "$Avd.avd\config.ini"
    $lines = Get-Content $cfg | Where-Object { $_ -notmatch '^(hw\.keyboard|hw\.ramSize|disk\.dataPartition\.size)=' }
    $lines += "hw.keyboard=yes", "hw.ramSize=2048", "disk.dataPartition.size=4G"
    Set-Content -Path $cfg -Value $lines -Encoding ASCII
}

function Check-Acceleration {
    $emu = Join-Path $Sdk "emulator\emulator.exe"
    $out = & $emu -accel-check 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) { return $true }
    Warn "Your PC's virtualization support is switched off, so the virtual phone cannot start."
    Note ($out.Trim() -split "`n" | Select-Object -Last 2)
    Note "Fix: turn on the Windows feature 'Windows Hypervisor Platform', then restart the PC."
    Note "(If it still fails after that, 'Virtualization' / 'SVM' / 'VT-x' must also be enabled in the BIOS.)"
    $answer = Read-Host "Turn it on now? Windows will ask for permission. [y/n]"
    if ($answer -match '^[yY]') {
        Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -Command "Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart"'
        Warn "Done. Restart your PC, then double-click 'Start JagaNet.bat' again."
    } else {
        Note "Meanwhile you can use option 2 (desktop window): it shows the same app."
    }
    return $false
}

function Adb { & (Join-Path $Sdk "platform-tools\adb.exe") @args }

function Device-Serial {
    $lines = Adb devices 2>$null | Select-Object -Skip 1
    foreach ($l in $lines) {
        $parts = ("$l".Trim()) -split "\s+"
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") { return $parts[0] }
    }
    return $null
}

function Start-Phone {
    $serial = Device-Serial
    if ($serial) { Step "Using the phone that's already running ($serial)"; return $serial }
    if (-not (Check-Acceleration)) { return $null }
    Step "Starting the virtual phone (first start takes 1-3 minutes)"
    Start-Process -FilePath (Join-Path $Sdk "emulator\emulator.exe") -ArgumentList "-avd", $Avd -WindowStyle Normal | Out-Null
    Adb wait-for-device | Out-Null
    $deadline = (Get-Date).AddMinutes(8)
    while ((Get-Date) -lt $deadline) {
        $booted = (Adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($booted -eq "1") { break }
        Write-Host "." -NoNewline
        Start-Sleep 3
    }
    Write-Host ""
    $serial = Device-Serial
    if (-not $serial) { Fail "The virtual phone did not finish starting. Close its window and try again." }
    return $serial
}

# ------------------------------------------------------------------ backend

function Server-Up {
    try { return (Invoke-WebRequest "$Api/health" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch { return $false }
}

function Gradle {
    # Gradle shows its own progress bar, so long first builds don't look frozen.
    & (Join-Path $Root "gradlew.bat") --warning-mode=none @args
    if ($LASTEXITCODE -ne 0) { Fail "Build step failed: gradlew $($args -join ' ')" }
}

function Start-Backend {
    if (Server-Up) { Step "Backend already running at $Api"; return }
    Title "Starting the backend"
    Note "First time: downloads the build tools and libraries, a few minutes."
    Gradle ":server:installDist"
    $lib = Join-Path $Root "server\build\install\server\lib\*"
    $env:PORT = "$Port"
    $p = Start-Process -FilePath (Join-Path $Jdk "bin\java.exe") -ArgumentList "-cp", "`"$lib`"", "dev.jaganet.server.SimKt" `
        -RedirectStandardOutput (Join-Path $Logs "server.log") -RedirectStandardError (Join-Path $Logs "server-errors.log") `
        -NoNewWindow -PassThru
    # Attached to this window: closing the window also stops the backend.
    Set-Content -Path (Join-Path $Logs "server.pid") -Value $p.Id
    for ($i = 0; $i -lt 120; $i++) {
        if (Server-Up) { Step "Backend running at $Api"; return }
        if ($p.HasExited) { Fail "The backend stopped. Details: $Logs\server-errors.log" }
        Start-Sleep 1
    }
    Fail "The backend didn't start within 2 minutes. Details: $Logs\server.log"
}

function Stop-Backend {
    $pidFile = Join-Path $Logs "server.pid"
    if (Test-Path $pidFile) {
        $id = Get-Content $pidFile
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        Remove-Item $pidFile
    }
}

function Show-Accounts {
    Write-Host ""
    Write-Host "  Sign in with one of these e-mails (the 6-digit code is shown in the app):" -ForegroundColor White
    Write-Host "    alex@example.com    - Pro user with devices and statistics"
    Write-Host "    sam@example.com     - Free user"
    Write-Host "    owner@jaganet.dev   - Owner: Settings > Business dashboard"
}

# ------------------------------------------------------------------ menu actions

function Run-Android {
    Ensure-Java
    Ensure-AndroidSdk
    Ensure-Avd
    Start-Backend
    Title "Opening JagaNet on the virtual phone"
    $serial = Start-Phone
    if (-not $serial) { return }
    $env:ANDROID_SERIAL = $serial
    Adb reverse "tcp:$Port" "tcp:$Port" | Out-Null
    Step "Building and installing the app (first time: several minutes)"
    Gradle ":androidApp:installDebug" "-Pjaganet.apiUrl=$Api"
    Adb shell am start -n dev.jaganet.app/dev.jaganet.android.MainActivity | Out-Null
    Step "JagaNet is open on the virtual phone."
    Show-Accounts
    Note "Tip: click into the phone and type with your keyboard."
}

function Run-Desktop {
    Ensure-Java
    Start-Backend
    Title "Opening JagaNet in a desktop window"
    Show-Accounts
    Note "Close the app window to come back to this menu."
    Gradle ":composeApp:run"
}

function Run-Tests {
    Ensure-Java
    Title "Running the automatic tests"
    Note "They check sign-in, plans and limits, devices, protocols (AmneziaWG, WireGuard),"
    Note "traffic statistics, referrals and billing against a real database."
    & (Join-Path $Root "gradlew.bat") --console=plain -q --continue ":shared:jvmTest" ":server:test" | Out-Host
    $passed = 0; $failed = 0
    $files = @(Get-ChildItem -Path (Join-Path $Root "server\build\test-results\test"), (Join-Path $Root "shared\build\test-results\jvmTest") -Filter *.xml -ErrorAction SilentlyContinue)
    foreach ($f in $files) {
        [xml]$x = Get-Content $f.FullName
        foreach ($tc in $x.testsuite.testcase) {
            $name = ($tc.name -replace '\(\)$', '') -replace '\[jvm\]', ''
            if ($tc.failure -or $tc.error) { $failed++; Write-Host "    FAIL  $name" -ForegroundColor Red }
            else { $passed++; Write-Host "    ok    $name" -ForegroundColor Green }
        }
    }
    Write-Host ""
    if ($failed -eq 0 -and $passed -gt 0) { Step "All $passed tests passed." }
    elseif ($passed -eq 0) { Warn "No test results found; see the messages above." }
    else { Warn "$failed of $($passed + $failed) tests failed." }
    $report = Join-Path $Root "server\build\reports\tests\test\index.html"
    if (Test-Path $report) { Note "Opening the detailed report in your browser."; Start-Process $report }
}

function Run-Screenshots {
    Ensure-Java
    Start-Backend
    Title "Taking a screenshot of every screen"
    Gradle ":composeApp:screenshots"
    $dir = Join-Path $Root "composeApp\build\screenshots"
    Step "Done. Opening the folder."
    Start-Process explorer.exe $dir
}

function Show-Overview {
    Title "What has been built"
    Note "Opening in your browser:"
    Note " - the project overview (README)"
    Note " - the design canvas with every screen"
    Note " - the folder with screenshots of the app"
    Start-Process "$Repo/blob/$Branch/README.md"
    Start-Process $Canvas
    Start-Process explorer.exe (Join-Path $Root "docs\screenshots")
}

function Stop-All {
    Title "Stopping everything"
    Stop-Backend
    if (Test-Path (Join-Path $Sdk "platform-tools\adb.exe")) {
        Adb emu kill 2>$null | Out-Null
        Adb kill-server 2>$null | Out-Null
    }
    & (Join-Path $Root "gradlew.bat") --stop -q 2>$null | Out-Null
    Step "Backend, virtual phone and build tools stopped."
}

# ------------------------------------------------------------------ menu

Start-Transcript -Path (Join-Path $Logs "launcher.log") -Append | Out-Null
try {
    while ($true) {
        Clear-Host
        Write-Host ""
        Write-Host "   JagaNet" -ForegroundColor Cyan
        Write-Host "   -------"
        if (Server-Up) { Write-Host "   Backend: running at $Api" -ForegroundColor Green } else { Write-Host "   Backend: not running (starts automatically)" -ForegroundColor Gray }
        Write-Host ""
        Write-Host "   1  Open the app on a virtual Android phone"
        Write-Host "   2  Open the app in a desktop window (quickest)"
        Write-Host "   3  Run the automatic tests and show the results"
        Write-Host "   4  Take screenshots of every screen"
        Write-Host "   5  Show what has been built (overview, design, screenshots)"
        Write-Host "   6  Stop everything"
        Write-Host "   0  Exit (also stops the backend)"
        Write-Host ""
        $choice = Read-Host "   Type a number and press Enter"
        try {
            switch ($choice) {
                "1" { Run-Android; Pause-Menu }
                "2" { Run-Desktop; Pause-Menu }
                "3" { Run-Tests; Pause-Menu }
                "4" { Run-Screenshots; Pause-Menu }
                "5" { Show-Overview; Pause-Menu }
                "6" { Stop-All; Pause-Menu }
                "0" { Stop-Backend; return }
                default { }
            }
        } catch {
            Write-Host ""
            Write-Host "  Something went wrong:" -ForegroundColor Red
            Write-Host "  $($_.Exception.Message)" -ForegroundColor Red
            Note "A full log is in $Logs\launcher.log - send it over if you need help."
            Pause-Menu
        }
    }
} finally {
    Stop-Transcript | Out-Null
}
