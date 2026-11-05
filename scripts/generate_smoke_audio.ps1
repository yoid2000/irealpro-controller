param([string]$Destination = '.downloads\smoke-audio')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
New-Item -ItemType Directory -Force $Destination | Out-Null
$destinationPath = (Resolve-Path -LiteralPath $Destination).Path
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)
$samples = @(
    @('counting', 'one two three four', 3),
    @('counting-slower', 'one two three four', 0),
    @('commands', 'one two three four start stop pause', 0),
    @('repeated', 'one one one one', 0)
)
foreach ($sample in $samples) {
    $voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $voice.SelectVoice('Microsoft David Desktop')
        $voice.Rate = $sample[2]
        $voice.SetOutputToWaveFile((Join-Path $destinationPath ($sample[0] + '.wav')), $format)
        $voice.Speak($sample[1])
    } finally { $voice.Dispose() }
}
Write-Output $destinationPath
