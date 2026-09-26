# TEST-ONLY: rebuild a true-speed video from the frames CrateSmoke captured.
#
# The recorder writes frames/timing.csv (frame,ms relative to the first frame). This script
# regenerates the ffmpeg concat manifest straight from timing.csv so the video is always rebuilt at
# real elapsed time (30fps output samples the captured timestamps without speeding up):
#   frame N lasts exactly (t[N+1] - t[N]) seconds; the last frame reuses the final real interval.
#
# Usage: powershell -ExecutionPolicy Bypass -File tools/crate-smoke/render-video.ps1
param(
    [string]$RunDir = (Join-Path $PSScriptRoot '..\..\build\crate-client-run'),
    [string]$Out = 'D:\Backup\mc mod\.video-analysis\recordings\crate-run.mp4',
    [string]$FrameFolder = 'frames',
    [int]$Crf = 16,
    [string]$Preset = 'medium'
)

$ErrorActionPreference = 'Stop'
$run = (Resolve-Path $RunDir).Path
$frames = Join-Path $run $FrameFolder
$csv = Join-Path $frames 'timing.csv'
if (-not (Test-Path $csv)) { throw "missing $csv -- run the recorder first" }

$rows = @(Import-Csv $csv)
if ($rows.Count -lt 2) { throw "timing.csv has only $($rows.Count) rows" }
$pngs = @(Get-ChildItem $frames -Filter 'f_*.png')
Write-Host ("timing.csv rows: {0}   PNG on disk: {1}" -f $rows.Count, $pngs.Count)

# Per-frame duration; the final frame reuses the last real interval.
$dur = New-Object 'System.Collections.Generic.List[double]'
for ($i = 0; $i -lt $rows.Count - 1; $i++) {
    $dur.Add(([double]$rows[$i + 1].ms - [double]$rows[$i].ms) / 1000.0)
}
$dur.Add($(if ($dur.Count -gt 0) { $dur[$dur.Count - 1] } else { 0.05 }))

$inv = [System.Globalization.CultureInfo]::InvariantCulture
$manifest = Join-Path $frames 'frames.txt'
$sb = New-Object System.Text.StringBuilder
foreach ($i in 0..($rows.Count - 1)) {
    $d = [Math]::Max(0.001, $dur[$i])
    [void]$sb.AppendLine("file 'f_{0:d5}.png'" -f [int]$rows[$i].frame)
    [void]$sb.AppendLine('option framerate 1000')
    [void]$sb.AppendLine('duration ' + $d.ToString('0.000000', $inv))
}
[void]$sb.AppendLine("file 'f_{0:d5}.png'" -f [int]$rows[$rows.Count - 1].frame)
[void]$sb.AppendLine('option framerate 1000')
[System.IO.File]::WriteAllText($manifest, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "manifest: $manifest"

$total = ($dur | Measure-Object -Sum).Sum
Write-Host ("expected: {0} frames, {1:N3}s, avg {2:N2} fps" -f $dur.Count, $total, ($dur.Count / $total))

New-Item -ItemType Directory -Force -Path (Split-Path $Out -Parent) | Out-Null
# Millisecond input timestamps preserve the animation's duration; encode a portable 30fps clip.
Push-Location $frames
try {
    ffmpeg -y -hide_banner -loglevel warning -f concat -safe 0 -i 'frames.txt' `
        -r 30 -fps_mode cfr -pix_fmt yuv420p -c:v libx264 -crf $Crf -preset $Preset -movflags +faststart $Out
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed with exit $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host "=== ffprobe: $Out ==="
ffprobe -v error -select_streams v:0 -count_frames `
    -show_entries stream=nb_read_frames,r_frame_rate,avg_frame_rate,duration,width,height,codec_name `
    -show_entries format=duration,size -of default=noprint_wrappers=1 $Out
Write-Host ''
Write-Host ("expected {0} frames / {1:N3}s" -f $dur.Count, $total)
