$ErrorActionPreference = 'Stop'
$passwordFile = Join-Path $PSScriptRoot '../signing/release-password.dpapi'
if (-not (Test-Path -LiteralPath $passwordFile)) { throw 'Senha de lançamento ausente.' }
$securePassword = Get-Content -LiteralPath $passwordFile -Raw | ConvertTo-SecureString
Write-Output ([System.Net.NetworkCredential]::new('', $securePassword).Password)
