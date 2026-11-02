<#
.SYNOPSIS
  Manual helper for the antivirus check (docs/av-test.md). Run it in a clean
  Windows VM, as administrator. Not part of the distribution; the app never calls it.

.DESCRIPTION
  Starts Process Monitor with the capture filters from docs/av-test.md, waits while
  you walk through one scenario by hand, stops the capture, saves the .pml and writes
  a CSV of the suspicious events: process creation of schtasks/reg/powershell/cmd,
  SetSecurityFile, Run-key changes, .exe/.dll created outside the installer.

.PARAMETER Scenario
  Short name of the scenario (for the file names), e.g. "02-install".

.PARAMETER ProcmonPath
  Path to Procmon64.exe (Sysinternals). Default: Procmon64.exe next to this script
  or on PATH.

.PARAMETER OutDir
  Where to write <Scenario>.pml and <Scenario>-suspicious.csv.

.PARAMETER Av
  Name of the antivirus installed in this snapshot (goes into the file name).

.EXAMPLE
  .\av-trace.ps1 -Scenario 02-install -Av Defender
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$Scenario,
  [string]$ProcmonPath,
  [string]$OutDir = (Join-Path $PWD 'av-results'),
  [string]$Av = 'unknown'
)

$ErrorActionPreference = 'Stop'

if (-not $ProcmonPath) {
  $local = Join-Path $PSScriptRoot 'Procmon64.exe'
  if (Test-Path $local) { $ProcmonPath = $local }
  else {
    $cmd = Get-Command Procmon64.exe -ErrorAction SilentlyContinue
    if ($cmd) { $ProcmonPath = $cmd.Source } else { throw 'Procmon64.exe not found: pass -ProcmonPath (download: https://learn.microsoft.com/sysinternals/downloads/procmon)' }
  }
}
New-Item -ItemType Directory -Force $OutDir | Out-Null
$base = Join-Path $OutDir ("{0}-{1}" -f $Av, $Scenario)
$pml = "$base.pml"
$csv = "$base.csv"
$report = "$base-suspicious.csv"
Remove-Item $pml, $csv, $report -ErrorAction SilentlyContinue

# The capture is unfiltered (a Process Monitor filter hides events from the file only
# in the view, not in the log), and the filtering happens below on the CSV export.
Write-Host "Starting Process Monitor, writing $pml"
$null = Start-Process -FilePath $ProcmonPath -ArgumentList '/AcceptEula', '/Quiet', '/Minimized', '/BackingFile', "`"$pml`""
Start-Sleep -Seconds 5

Read-Host "Scenario '$Scenario' ($Av): do it now by hand, then press Enter here to stop the capture" | Out-Null

& $ProcmonPath /Terminate
Start-Sleep -Seconds 3
& $ProcmonPath /AcceptEula /Quiet /OpenLog "`"$pml`"" /SaveAs "`"$csv`""
$deadline = (Get-Date).AddSeconds(120)
while (-not (Test-Path $csv) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
if (-not (Test-Path $csv)) { throw "Process Monitor did not write $csv" }
& $ProcmonPath /Terminate

$shell = '^(schtasks|reg|powershell|pwsh|cmd|wscript|cscript|mshta|rundll32|regsvr32|sc|netsh|takeown|icacls)\.exe$'
$rows = Import-Csv $csv
$hits = foreach ($r in $rows) {
  $op = $r.Operation
  $path = $r.Path
  $why = $null
  if ($op -eq 'Process Create' -and $path -match $shell) { $why = 'process create' }
  elseif ($op -eq 'SetSecurityFile') { $why = 'SetSecurityFile' }
  elseif ($op -in 'RegSetValue', 'RegCreateKey', 'RegDeleteValue' -and $path -match '\\CurrentVersion\\Run(Once)?\\?') { $why = 'Run key' }
  elseif ($op -eq 'CreateFile' -and $path -match '\.(exe|dll|sys)$' -and $r.Detail -match 'Disposition: (Create|OverwriteIf|Supersede)' -and
          $r.'Process Name' -notmatch '^(Boop-Setup.*|unins\d+|Boop-Setup.*\.tmp|msiexec|TrustedInstaller|svchost)\.exe$') { $why = 'binary created outside the installer' }
  if ($why) {
    [pscustomobject]@{
      Why = $why; Time = $r.'Time of Day'; Process = $r.'Process Name'; PID = $r.PID
      Operation = $op; Path = $path; Detail = $r.Detail
    }
  }
}
@($hits) | Export-Csv -NoTypeInformation -Encoding UTF8 $report
Write-Host ("Suspicious events: {0}. Files: {1}, {2}" -f @($hits).Count, $pml, $report)
if (@($hits).Count -gt 0) { @($hits) | Group-Object Why | ForEach-Object { Write-Host ("  {0}: {1}" -f $_.Name, $_.Count) } }
