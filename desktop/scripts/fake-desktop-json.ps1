<#
A fake panel for the update e2e (issue #82): serves, on 127.0.0.1, the files the
update path asks for and logs every request.
  /v1/app/desktop   the document in -Manifest
  /bridge.exe       -Bridge
  /setup.exe        -Setup
Anything else is 404. It runs until the file -StopFile exists. The log (one
"GET /path" per line) is the proof of what was downloaded and how often.
  pwsh fake-desktop-json.ps1 -Port 18444 -Manifest m.json -Bridge b.exe -Setup s.exe -Log requests.log -StopFile stop
#>
param(
  [int]$Port = 18444,
  [Parameter(Mandatory)][string]$Manifest,
  [Parameter(Mandatory)][string]$Bridge,
  [Parameter(Mandatory)][string]$Setup,
  [Parameter(Mandatory)][string]$Log,
  [Parameter(Mandatory)][string]$StopFile
)
$ErrorActionPreference = 'Stop'
$files = @{ '/v1/app/desktop' = $Manifest; '/bridge.exe' = $Bridge; '/setup.exe' = $Setup }
$l = [System.Net.HttpListener]::new()
$l.Prefixes.Add("http://127.0.0.1:$Port/")
$l.Start()
Set-Content -Path $Log -Value "listening $Port"
while (-not (Test-Path $StopFile)) {
  $t = $l.GetContextAsync()
  while (-not $t.Wait(500)) { if (Test-Path $StopFile) { break } }
  if (-not $t.IsCompleted) { continue }
  $ctx = $t.Result
  $path = $ctx.Request.Url.AbsolutePath
  Add-Content -Path $Log -Value "$($ctx.Request.HttpMethod) $path"
  if ($files.ContainsKey($path)) {
    $bytes = [System.IO.File]::ReadAllBytes($files[$path])
    if ($path -eq '/v1/app/desktop') { $ctx.Response.ContentType = 'application/json' }
    $ctx.Response.ContentLength64 = $bytes.Length
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
  } else {
    $ctx.Response.StatusCode = 404
  }
  $ctx.Response.Close()
}
$l.Stop()
