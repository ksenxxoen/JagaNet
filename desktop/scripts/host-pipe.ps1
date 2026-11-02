<#
Windows e2e helper (CI only): talks to a Go host (`BoopHost.exe --host --pipe \\.\pipe\BoopApp-<name>`)
the way the Tauri shell does, one JSON object per line (hostpipe/host.go), without a shell.
  pwsh scripts/host-pipe.ps1 -Pipe BoopApp-e2e -Request '{"id":1,"method":"call","name":"setAutostart","arg":true}'
Prints the answer line (the one with the same id) and exits 0; exits 1 when the host does not answer.
#>
param(
  [Parameter(Mandatory)][string]$Pipe,
  [Parameter(Mandatory)][string]$Request,
  [int]$TimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
$id = ($Request | ConvertFrom-Json).id
$c = New-Object System.IO.Pipes.NamedPipeClientStream('.', $Pipe, [System.IO.Pipes.PipeDirection]::InOut)
try {
  $c.Connect($TimeoutSeconds * 1000)
  $enc = New-Object System.Text.UTF8Encoding($false)
  $w = New-Object System.IO.StreamWriter($c, $enc)
  $w.AutoFlush = $true
  $r = New-Object System.IO.StreamReader($c, $enc)
  $w.WriteLine($Request)
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    $task = $r.ReadLineAsync()
    if (-not $task.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) { break }
    $line = $task.Result
    if ($null -eq $line) { break }
    $o = $line | ConvertFrom-Json
    if ($o.PSObject.Properties.Name -contains 'id' -and $o.id -eq $id) { Write-Output $line; exit 0 }
  }
  Write-Error 'no answer from the host'
  exit 1
} finally { $c.Dispose() }
