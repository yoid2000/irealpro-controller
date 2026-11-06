param([string]$Destination = 'app\src\androidTest\assets\sounds')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
New-Item -ItemType Directory -Force $Destination | Out-Null
$soundDestination = (Resolve-Path -LiteralPath $Destination).Path
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)
$samples = @()
foreach ($word in @('bap', 'deet', 'goke', 'shoom')) {
    $rates = @(-2, 0, 2, -1, 3)
    for ($i = 0; $i -lt $rates.Count; $i++) { $samples += ,@("$word-train-$i", $word, $rates[$i], 80 + $i * 4) }
    $samples += ,@("$word-query", $word, 1, 90)
}
$samples += ,@('sequence', 'bap deet goke shoom', 4, 90)
$samples += ,@('repeated', 'bap bap bap bap bap', 4, 90)
foreach ($sample in $samples) {
    $voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $voice.SelectVoice('Microsoft David Desktop')
        $voice.Rate = $sample[2]; $voice.Volume = $sample[3]
        $voice.SetOutputToWaveFile((Join-Path $soundDestination ($sample[0] + '.wav')), $format)
        $voice.Speak($sample[1])
    } finally { $voice.Dispose() }
}
Write-Output $soundDestination
