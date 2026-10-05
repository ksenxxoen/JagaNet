# One command to try JagaNet locally (Windows PowerShell).
#
#   .\run.ps1            backend + the app in a desktop window (no Android needed)
#   .\run.ps1 android    backend + the app on an Android emulator or USB phone
#   .\run.ps1 server     backend only
#
# If scripts are blocked: powershell -ExecutionPolicy Bypass -File .\run.ps1
param([ValidateSet("desktop", "android", "server")][string]$Mode = "desktop")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Port = if ($env:PORT) { $env:PORT } else { "4000" }
$Api = "http://localhost:$Port"
$Logs = "build\run"
New-Item -ItemType Directory -Force -Path $Logs | Out-Null
$server = $null

function Say($m) { Write-Host "> $m" -ForegroundColor Green }
function Die($m) { Write-Host "x $m" -ForegroundColor Red; exit 1 }
function ServerUp { try { (Invoke-WebRequest "$Api/health" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch { $false } }

try {
    # ---------- Java ----------
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Die "Java is not installed. Install JDK 21 (winget install EclipseAdoptium.Temurin.21.JDK, or https://adoptium.net), then run this again."
    }
    $ver = (& cmd /c "java -version 2>&1" | Select-String 'version "(\d+)').Matches[0].Groups[1].Value
    if ([int]$ver -lt 17) { Die "Java $ver found; Java 17 or newer is needed (21 recommended): https://adoptium.net" }

    # ---------- backend ----------
    if (ServerUp) {
        Say "Backend already running on $Api"
    } else {
        Say "Building the backend (the first run downloads Gradle and dependencies, a few minutes)..."
        & .\gradlew.bat -q :server:installDist
        if ($LASTEXITCODE -ne 0) { Die "Backend build failed." }
        Say "Starting the backend on $Api (log: $Logs\server.log)"
        $env:PORT = $Port
        $server = Start-Process java -ArgumentList '-cp', '"server\build\install\server\lib\*"', 'dev.jaganet.server.SimKt' `
            -RedirectStandardOutput "$Logs\server.log" -RedirectStandardError "$Logs\server.err.log" -NoNewWindow -PassThru
        $ok = $false
        for ($i = 0; $i -lt 120; $i++) {
            if (ServerUp) { $ok = $true; break }
            if ($server.HasExited) { Get-Content "$Logs\server.log" -Tail 20; Die "The backend stopped. See $Logs\server.log" }
            Start-Sleep 1
        }
        if (-not $ok) { Die "The backend didn't start within 2 minutes. See $Logs\server.log" }
    }

    Write-Host @"

  Backend: $Api   (health: $Api/health)
  Sign in with one of these; the 6-digit code is shown in the app:
    alex@example.com    Pro, devices and 30 days of stats
    sam@example.com     Free plan
    owner@jaganet.dev   Owner dashboard (Settings > Business dashboard)

"@

    switch ($Mode) {
        "desktop" {
            Say "Opening the app in a desktop window (simulated tunnel). Close the window to stop."
            & .\gradlew.bat -q :composeApp:run
        }
        "android" {
            $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
                Where-Object { $_ -and (Test-Path "$_\platform-tools") } | Select-Object -First 1
            if (-not $sdk) { Die "Android SDK not found. Install Android Studio (https://developer.android.com/studio), open it once so it installs the SDK, then run this again." }
            if (-not (Test-Path local.properties)) { "sdk.dir=$($sdk -replace '\\', '/')" | Set-Content local.properties }
            $adb = "$sdk\platform-tools\adb.exe"
            $emulator = "$sdk\emulator\emulator.exe"

            $serial = (& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" } | Select-Object -First 1) -replace "`t.*", ""
            if (-not $serial) {
                if (-not (Test-Path $emulator)) { Die "No phone connected and no emulator installed. In Android Studio: Device Manager > Create device." }
                $avd = & $emulator -list-avds | Select-Object -First 1
                if (-not $avd) { Die "No emulator created yet. In Android Studio: Device Manager > Create device, then run this again." }
                Say "Starting emulator `"$avd`"..."
                Start-Process $emulator -ArgumentList "-avd", $avd, "-no-snapshot-save" -WindowStyle Minimized | Out-Null
                & $adb wait-for-device
                while ((& $adb shell getprop sys.boot_completed 2>$null) -ne "1") { Start-Sleep 2 }
                $serial = (& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" } | Select-Object -First 1) -replace "`t.*", ""
            }
            $env:ANDROID_SERIAL = $serial
            Say "Using device $serial"
            # The phone's localhost:$Port -> this computer's backend (emulators and USB phones).
            & $adb reverse "tcp:$Port" "tcp:$Port" | Out-Null

            Say "Building and installing the app (first build takes a few minutes)..."
            & .\gradlew.bat -q :androidApp:installDebug "-Pjaganet.apiUrl=$Api"
            if ($LASTEXITCODE -ne 0) { Die "Android build failed." }
            & $adb shell am start -n dev.jaganet.app/dev.jaganet.android.MainActivity | Out-Null
            Say "JagaNet is open on the device. Backend keeps running. Press Ctrl+C to stop."
            while (ServerUp) { Start-Sleep 5 }
        }
        "server" {
            Say "Press Ctrl+C to stop."
            while (ServerUp) { Start-Sleep 5 }
        }
    }
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force }
}
