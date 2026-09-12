# Fetches the sherpa-onnx runtime and assembles the bundled English language pack.
#
# The AAR (47 MB) and the model weights (60 MB) are deliberately NOT committed: they are
# large binaries that git handles badly, and they are reproducible from published
# releases. Run this once after cloning, then build normally.
#
#   powershell -ExecutionPolicy Bypass -File tools\fetch-models.ps1
#
# Everything downloaded here is Apache-2.0 / MIT licensed and published by the
# sherpa-onnx project (github.com/k2-fsa/sherpa-onnx).

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$proj = Split-Path -Parent $PSScriptRoot
$work = Join-Path $env:TEMP "itantra-models"
$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

$SHERPA_VERSION = "1.13.6"
$ASR_MODEL = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
$TTS_MODEL = "vits-piper-en_US-amy-low-int8"

New-Item -ItemType Directory -Force -Path $work | Out-Null

function Fetch($tag, $name) {
    $dest = Join-Path $work $name
    if (Test-Path $dest) {
        Write-Host "  have $name"
        return $dest
    }
    Write-Host "  downloading $name ..."
    Invoke-WebRequest -Uri "$base/$tag/$name" -OutFile $dest -TimeoutSec 1800
    return $dest
}

Write-Host "1/4  runtime"
$aar = Fetch "v$SHERPA_VERSION" "sherpa-onnx-$SHERPA_VERSION.aar"
New-Item -ItemType Directory -Force -Path "$proj\app\libs" | Out-Null
Copy-Item $aar "$proj\app\libs\sherpa-onnx-$SHERPA_VERSION.aar" -Force

Write-Host "2/4  models"
$asrArchive = Fetch "asr-models" "$ASR_MODEL.tar.bz2"
$ttsArchive = Fetch "tts-models" "$TTS_MODEL.tar.bz2"

Write-Host "3/4  extracting"
Push-Location $work
if (-not (Test-Path "$work\$ASR_MODEL")) { tar -xjf "$ASR_MODEL.tar.bz2" }
if (-not (Test-Path "$work\$TTS_MODEL")) { tar -xjf "$TTS_MODEL.tar.bz2" }
Pop-Location

Write-Host "4/4  assembling language pack"
$pack = "$proj\app\src\main\assets\languages\en"
New-Item -ItemType Directory -Force -Path "$pack\asr", "$pack\tts\espeak-ng-data" | Out-Null

# Only the int8 weights are shipped. The float32 copies in the archive are ~3x larger
# for accuracy we cannot afford on a low-end phone (PRD section 8, efficiency).
Copy-Item "$work\$ASR_MODEL\encoder-epoch-99-avg-1.int8.onnx" "$pack\asr\encoder.onnx" -Force
Copy-Item "$work\$ASR_MODEL\decoder-epoch-99-avg-1.int8.onnx" "$pack\asr\decoder.onnx" -Force
Copy-Item "$work\$ASR_MODEL\joiner-epoch-99-avg-1.int8.onnx"  "$pack\asr\joiner.onnx"  -Force
Copy-Item "$work\$ASR_MODEL\tokens.txt" "$pack\asr\tokens.txt" -Force

Copy-Item "$work\$TTS_MODEL\en_US-amy-low.onnx" "$pack\tts\model.onnx" -Force
Copy-Item "$work\$TTS_MODEL\tokens.txt" "$pack\tts\tokens.txt" -Force

# espeak-ng ships pronunciation dictionaries for ~100 languages. Shipping all of them
# would add ~16 MB of dictionaries this pack will never consult. Each language pack
# carries only its own dictionary plus the shared phoneme tables.
$esrc = "$work\$TTS_MODEL\espeak-ng-data"
$edst = "$pack\tts\espeak-ng-data"
foreach ($f in @("intonations", "phondata", "phondata-manifest", "phonindex", "phontab", "en_dict")) {
    Copy-Item "$esrc\$f" "$edst\$f" -Force
}
Copy-Item "$esrc\lang"   "$edst\lang"   -Recurse -Force
Copy-Item "$esrc\voices" "$edst\voices" -Recurse -Force

$mb = (Get-ChildItem $pack -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ""
Write-Host ("done. English pack is {0:N1} MB" -f $mb)
Write-Host "now run:  .\gradlew assembleDebug"
