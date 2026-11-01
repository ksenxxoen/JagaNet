<#
Signs one file for Inno Setup's SignTool (build.ps1 passes it when BOOP_SIGN is
set). Same variables as ../build.sh and ../SIGNING.md; secrets come only from the
environment, the PFX password goes through a private file, never the command line.
  BOOP_SIGN = osslsigncode | azure
#>
param([Parameter(Mandatory)][string]$File)
$ErrorActionPreference = 'Stop'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('boopsign-' + [guid]::NewGuid())
New-Item -ItemType Directory $tmp | Out-Null
try {
  $name = if ($env:BOOP_SIGN_NAME) { $env:BOOP_SIGN_NAME } else { 'Boop' }
  $url = if ($env:BOOP_SIGN_URL) { $env:BOOP_SIGN_URL } else { 'https://boopvpn.app' }
  switch ($env:BOOP_SIGN) {
    'osslsigncode' {
      $pfx = $env:BOOP_SIGN_PFX
      if (-not $pfx -and $env:BOOP_SIGN_PFX_BASE64) {
        $pfx = Join-Path $tmp 'key.pfx'
        [IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:BOOP_SIGN_PFX_BASE64))
      }
      if (-not $pfx -or -not (Test-Path $pfx)) { throw 'set BOOP_SIGN_PFX or BOOP_SIGN_PFX_BASE64' }
      $pass = Join-Path $tmp 'pass'
      [IO.File]::WriteAllText($pass, [string]$env:BOOP_SIGN_PASSWORD)
      $tsa = if ($env:BOOP_SIGN_TSA) { $env:BOOP_SIGN_TSA } else { 'https://timestamp.acs.microsoft.com' }
      $ts = if ($tsa -eq 'none') { @() } else { @('-ts', $tsa) }
      $out = Join-Path $tmp 'signed.exe'
      & osslsigncode sign -pkcs12 $pfx -readpass $pass -h sha256 -n $name -i $url @ts -in $File -out $out
      if ($LASTEXITCODE -ne 0) { throw 'osslsigncode failed' }
      Copy-Item $out $File -Force
    }
    'azure' {
      foreach ($v in 'BOOP_AZURE_ENDPOINT', 'BOOP_AZURE_ACCOUNT', 'BOOP_AZURE_PROFILE') {
        if (-not [Environment]::GetEnvironmentVariable($v)) { throw "set $v" }
      }
      if (-not $env:BOOP_AZURE_TOKEN) {
        $env:BOOP_AZURE_TOKEN = (az account get-access-token --resource https://codesigning.azure.net --query accessToken -o tsv)
      }
      $jsign = if ($env:BOOP_SIGN_JSIGN) { $env:BOOP_SIGN_JSIGN } else { 'jsign' }
      $tsa = if ($env:BOOP_SIGN_TSA) { $env:BOOP_SIGN_TSA } else { 'http://timestamp.acs.microsoft.com' }
      $ts = if ($tsa -eq 'none') { @() } else { @('--tsaurl', $tsa, '--tsmode', 'RFC3161') }
      & $jsign --storetype TRUSTEDSIGNING --keystore $env:BOOP_AZURE_ENDPOINT --storepass env:BOOP_AZURE_TOKEN `
        --alias "$($env:BOOP_AZURE_ACCOUNT)/$($env:BOOP_AZURE_PROFILE)" --alg SHA-256 --name $name --url $url @ts $File
      if ($LASTEXITCODE -ne 0) { throw 'jsign failed' }
    }
    default { throw "unknown BOOP_SIGN=$($env:BOOP_SIGN) (osslsigncode or azure)" }
  }
  $verify = if ($env:BOOP_SIGN_VERIFY) { $env:BOOP_SIGN_VERIFY } else { 'strict' }
  if ($verify -ne 'off') {
    $s = Get-AuthenticodeSignature $File
    if ($s.Status -ne 'Valid') {
      if ($verify -eq 'strict') { throw "signature of $File is $($s.Status)" }
      Write-Warning "signature of $File is $($s.Status) (BOOP_SIGN_VERIFY=warn)"
    }
  }
} finally {
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
