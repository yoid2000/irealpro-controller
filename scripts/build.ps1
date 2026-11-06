param([switch]$SkipAssetDownload)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (!$env:JAVA_HOME) {
        $localJdk = Get-ChildItem -LiteralPath (Join-Path $root '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
    }
    if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 (Android Studio includes a JDK).' }
    if (!$env:ANDROID_HOME) {
        $localSdk = Join-Path $root '.tools\android-sdk'
        if (Test-Path -LiteralPath $localSdk) { $env:ANDROID_HOME = $localSdk }
        elseif (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) {
            $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        }
    }
    if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME to an Android SDK with platform 36 installed.' }
    if (!$env:GRADLE_USER_HOME -and (Test-Path -LiteralPath (Join-Path $root '.tools\gradle-user-home'))) {
        $env:GRADLE_USER_HOME = Join-Path $root '.tools\gradle-user-home'
    }
    $sdkPath = $env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:')
    [System.IO.File]::WriteAllText((Join-Path $root 'local.properties'), ('sdk.dir=' + $sdkPath + "`n"), [System.Text.Encoding]::ASCII)
    if (!$SkipAssetDownload) {
        & python -m pip install -r scripts/requirements-assets.txt
        if ($LASTEXITCODE -ne 0) { throw 'Asset preparation dependency installation failed.' }
        & python scripts/prepare_assets.py
        if ($LASTEXITCODE -ne 0) { throw 'Asset download failed.' }
    }
    & .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or validation failed.' }
    Write-Output (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')
} finally { Pop-Location }
