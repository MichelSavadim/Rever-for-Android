param([switch]$SkipLint)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keystore = Join-Path $projectRoot 'signing/rever-release.jks'
$passwordFile = Join-Path $projectRoot 'signing/release-password.dpapi'
$lineage = Join-Path $projectRoot 'signing/debug-to-release.lineage'
if (-not (Test-Path -LiteralPath $keystore) -or -not (Test-Path -LiteralPath $passwordFile) -or
    -not (Test-Path -LiteralPath $lineage)) {
    throw 'Chave ou linhagem de assinatura ausente. Restaure os arquivos em signing/ antes de compilar.'
}

$securePassword = Get-Content -LiteralPath $passwordFile -Raw | ConvertTo-SecureString
$plainPassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
$priorKeystore = $env:REVER_RELEASE_KEYSTORE
$priorStorePassword = $env:REVER_RELEASE_STORE_PASSWORD
$priorAlias = $env:REVER_RELEASE_KEY_ALIAS
$priorKeyPassword = $env:REVER_RELEASE_KEY_PASSWORD
try {
    Remove-Item Env:REVER_RELEASE_KEYSTORE -ErrorAction SilentlyContinue
    Remove-Item Env:REVER_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:REVER_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    $env:REVER_RELEASE_STORE_PASSWORD = $plainPassword
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
    $tasks = @('assembleRelease')
    if (-not $SkipLint) { $tasks += 'lintRelease' }
    Push-Location $projectRoot
    try {
        & .\gradlew.bat @tasks --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle falhou com código $LASTEXITCODE" }
        $apksigner = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat'
        $unsigned = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
        $signed = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
        & $apksigner sign --ks $keystore --ks-key-alias rever `
            --ks-pass env:REVER_RELEASE_STORE_PASSWORD --key-pass env:REVER_RELEASE_STORE_PASSWORD `
            --lineage $lineage --rotation-min-sdk-version 33 `
            --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true `
            --out $signed $unsigned
        if ($LASTEXITCODE -ne 0) { throw "Assinatura falhou com código $LASTEXITCODE" }
        & $apksigner verify --min-sdk-version 34 $signed
        if ($LASTEXITCODE -ne 0) { throw "Verificação do APK falhou com código $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $priorKeystore) { Remove-Item Env:REVER_RELEASE_KEYSTORE -ErrorAction SilentlyContinue }
    else { $env:REVER_RELEASE_KEYSTORE = $priorKeystore }
    if ($null -eq $priorStorePassword) { Remove-Item Env:REVER_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue }
    else { $env:REVER_RELEASE_STORE_PASSWORD = $priorStorePassword }
    if ($null -eq $priorAlias) { Remove-Item Env:REVER_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue }
    else { $env:REVER_RELEASE_KEY_ALIAS = $priorAlias }
    if ($null -eq $priorKeyPassword) { Remove-Item Env:REVER_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue }
    else { $env:REVER_RELEASE_KEY_PASSWORD = $priorKeyPassword }
    $plainPassword = $null
}
