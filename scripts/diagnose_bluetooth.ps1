param([string]$AdbPath, [string]$DeviceSerial)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$AdbPath) {
    $localAdb = Join-Path $root '.tools\android-sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $localAdb) { $AdbPath = $localAdb }
    elseif ($env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
        $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    } else { $AdbPath = (Get-Command adb -ErrorAction Stop).Source }
}
$inventory = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) { throw 'Could not communicate with adb.' }
$ready = @($inventory | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (!$DeviceSerial) {
    if ($ready.Count -eq 0) {
        throw 'No authorized device found. Connect A by USB, enable USB debugging, and accept the debugging prompt on A.'
    }
    if ($ready.Count -ne 1) { throw 'More than one device is connected. Pass -DeviceSerial for device A.' }
    $DeviceSerial = $ready[0]
}
if ($DeviceSerial -notin $ready) { throw 'The selected device is not connected and authorized for USB debugging.' }
$report = [System.Collections.Generic.List[string]]::new()
$report.Add('VoiceKeys Bluetooth diagnostics (read-only)')
$report.Add('Collected UTC: ' + [DateTime]::UtcNow.ToString('u'))
$voiceProcessId = ([string](& $AdbPath -s $DeviceSerial shell pidof com.paul.voicekeys)).Trim()
$logArguments = @('logcat', '-d', '-v', 'threadtime')
if ($voiceProcessId -match '^\d+$') { $logArguments += '--pid=' + $voiceProcessId }
$logArguments += @('VoiceKeysBluetooth:V', 'BluetoothAdapter:D', 'BluetoothHidDevice:D',
    'BluetoothProfileConnector:D', 'HidDeviceService:V', 'AdapterService:W', 'BluetoothManagerService:W', '*:S')
$sections = @(
    @('Phone model', @('shell', 'getprop', 'ro.product.model')),
    @('Android API', @('shell', 'getprop', 'ro.build.version.sdk')),
    @('Android build', @('shell', 'getprop', 'ro.build.fingerprint')),
    @('System HID device profile property (empty means unspecified)', @('shell', 'getprop', 'bluetooth.profile.hid.device.enabled')),
    @('VoiceKeys package and granted permissions', @('shell', 'dumpsys', 'package', 'com.paul.voicekeys')),
    @('VoiceKeys app operations', @('shell', 'appops', 'get', 'com.paul.voicekeys')),
    @('Recent Bluetooth profile logs', $logArguments)
)
foreach ($section in $sections) {
    $report.Add('')
    $report.Add('--- ' + $section[0] + ' ---')
    $arguments = @('-s', $DeviceSerial) + $section[1]
    $output = & $AdbPath @arguments 2>&1
    if ($section[0] -eq 'VoiceKeys package and granted permissions') {
        $output = $output | Select-String -Pattern 'versionCode=|versionName=|BLUETOOTH|RECORD_AUDIO|MODIFY_AUDIO_SETTINGS|runtime permissions:|requested permissions:'
    } elseif ($section[0] -eq 'VoiceKeys app operations') {
        $output = $output | Select-String -Pattern 'BLUETOOTH|RECORD_AUDIO|RESTRICTED_SETTINGS'
    } elseif ($section[0] -eq 'Recent Bluetooth profile logs') {
        $output = $output | Select-Object -Last 500
    }
    foreach ($line in $output) { $report.Add($line.ToString()) }
}
$report.Add('')
$report.Add('--- HID-related Bluetooth service state ---')
$state = & $AdbPath -s $DeviceSerial shell dumpsys bluetooth_manager 2>&1
foreach ($line in ($state | Select-String -Pattern '^Profile:|HidDeviceService|mSupportedProfiles|mStartedProfiles|supported profiles|Service 00001812|appName: com.paul.voicekeys')) {
    $report.Add($line.ToString())
}
$outputDir = Join-Path $root '.downloads\device-diagnostics'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$path = Join-Path $outputDir ('bluetooth-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.txt')
[System.IO.File]::WriteAllLines($path, $report, [System.Text.UTF8Encoding]::new($false))
Write-Output $path
