param([string]$Adb = 'D:\AIwatch\.android-sdk\platform-tools\adb.exe',
      [string]$Serial = 'emulator-5554', [string]$Log = 'evidence/tests/ADAPTIVE_MATRIX.log')
$ErrorActionPreference = 'Stop'
if ($Serial -ne 'emulator-5554') { throw 'This fixture is restricted to the SDK emulator.' }
$oldSize = (& $Adb -s $Serial shell wm size | Out-String)
$oldDensity = (& $Adb -s $Serial shell wm density | Out-String)
$oldScale = (& $Adb -s $Serial shell settings get system font_scale | Out-String).Trim()
$results = @()
'Adaptive Round 1: SDK emulator only, not hardware certification' | Set-Content $Log
try {
    foreach ($profile in @(@(205,251), @(240,320), @(360,640), @(411,891))) {
        foreach ($scale in @('1.0','1.3')) {
            $w = $profile[0]; $h = $profile[1]
            # API28 limits forced pixels to 2x this AVD's physical 320x640 surface.
            # Keep the permanent compact fixture at 320dpi; phones use 160dpi for exact dp.
            $factor = if ($w -lt 300) { 2 } else { 1 }
            & $Adb -s $Serial shell wm density ($factor * 160)
            & $Adb -s $Serial shell wm size "$($w * $factor)x$($h * $factor)"
            & $Adb -s $Serial shell settings put system font_scale $scale
            Start-Sleep -Seconds 2
            "CELL ${w}x${h} fontScale=$scale" | Tee-Object -FilePath $Log -Append
            & $Adb -s $Serial shell wm size | Tee-Object -FilePath $Log -Append
            & $Adb -s $Serial shell wm density | Tee-Object -FilePath $Log -Append
            $output = & $Adb -s $Serial shell am instrument -w -e class com.aiwatch.probe.AdaptiveWindowTest -e expectedFontScale $scale -e expectedWidthDp $w -e expectedHeightDp $h com.aiwatch.probe.test/androidx.test.runner.AndroidJUnitRunner 2>&1
            $output | Tee-Object -FilePath $Log -Append
            $passed = ($output | Out-String) -match 'OK \(1 test\)'
            $results += $passed
            & $Adb -s $Serial logcat -d -s 'System.out:I' '*:S' |
                Select-String 'ADAPTIVE ' | Select-Object -Last 5 |
                ForEach-Object { $_.Line } | Add-Content $Log
        }
    }
} finally {
    if ($oldSize -match 'Override size: (\d+x\d+)') { & $Adb -s $Serial shell wm size $Matches[1] }
    else { & $Adb -s $Serial shell wm size reset }
    if ($oldDensity -match 'Override density: (\d+)') { & $Adb -s $Serial shell wm density $Matches[1] }
    else { & $Adb -s $Serial shell wm density reset }
    if ($oldScale -eq 'null') { & $Adb -s $Serial shell settings delete system font_scale }
    else { & $Adb -s $Serial shell settings put system font_scale $oldScale }
}
"RESULT $(@($results | Where-Object { $_ }).Count)/8" | Tee-Object -FilePath $Log -Append
if ($results.Count -ne 8 -or $results -contains $false) { exit 1 }
