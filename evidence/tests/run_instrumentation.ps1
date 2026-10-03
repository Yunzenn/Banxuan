# Per-method instrumentation runner for the memory trust surface.
#
# HISTORICAL / DIAGNOSTIC RUNNER
# Product tests now use an owned identity fixture and can share one instrumentation process.
# Use run_product_suite.ps1 for the canonical product gate. This runner remains useful for
# diagnosing a specific method; process-per-method is still allowed for third-party Cubism smoke.
#
# WHY THE SERIAL IS EXPLICIT
# More than one device can be attached at once (a workspace AVD plus the Lenovo
# x86_64 instance behind a forwarded port). A bare `adb` then fails loudly with
# "more than one device", which is the good outcome. The dangerous case is a bare
# `adb` with exactly one *wrong* device attached, which would test the wrong
# hardware and report it as evidence.
#
# WHY "OK (0 tests)" IS NOT A PASS
# If the installed test APK is stale, `am instrument -e class X#method` matches
# nothing and still exits successfully with `OK (0 tests)`. That is a silent zero,
# not a passing test. This runner counts only `OK (1 test)` as a pass and reports a
# zero-match separately, because the emulator restores installed APKs from its boot
# snapshot, so a stale APK after a restart is the expected failure mode rather than
# a surprising one.
#
# Usage:
#   pwsh -File evidence/tests/run_instrumentation.ps1 `
#       -Src app/src/androidTest/kotlin/com/aiwatch/probe/memory/MemoryTrustTest.kt `
#       -Class com.aiwatch.probe.memory.MemoryTrustTest `
#       -TestPkg com.aiwatch.probe.test `
#       -Serial emulator-5554 `
#       -Log .tools/instrument.log
param(
    [Parameter(Mandatory = $true)][string]$Src,
    [Parameter(Mandatory = $true)][string]$Class,
    [string]$TestPkg,
    [string]$Serial = 'emulator-5554',
    [string]$Log = 'instrument.log',
    [string]$Adb,
    [string]$Runner = 'com.aiwatch.probe.ProductTestRunner'
)

$ErrorActionPreference = 'Continue'
$runner = $Runner

# Resolve adb the way the build does: local.properties, then the environment, then PATH.
if (-not $Adb) {
    $sdk = $null
    $localProps = Join-Path $PSScriptRoot '..\..\local.properties'
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $sdk = ($line -split '=', 2)[1].Trim().Replace('\\', '\').Replace('\:', ':') }
    }
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    $Adb = if ($sdk) { Join-Path $sdk 'platform-tools\adb.exe' } else { 'adb' }
}

# Derive the method list from the source so the runner cannot drift from the file.
$lines = Get-Content $Src
$methods = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '@Test') {
        for ($j = $i + 1; $j -lt [Math]::Min($i + 4, $lines.Count); $j++) {
            if ($lines[$j] -match 'fun\s+(\w+)') { $methods += $Matches[1]; break }
        }
    }
}

"class=$Class" | Set-Content $Log
"serial=$Serial" | Add-Content $Log
"src=$Src" | Add-Content $Log
"sdk=$(& $Adb -s $Serial shell getprop ro.build.version.sdk)" | Add-Content $Log
"wm_size=$(& $Adb -s $Serial shell wm size | Select-String 'Override')" | Add-Content $Log
"wm_density=$(& $Adb -s $Serial shell wm density | Select-String 'Override')" | Add-Content $Log
"methods=$($methods.Count)" | Add-Content $Log
"" | Add-Content $Log

$pass = 0; $fail = 0; $norun = 0; $failed = @()
foreach ($m in $methods) {
    $target = if ($TestPkg) { "$TestPkg/$runner" } else { $runner }
    $out = & $Adb -s $Serial shell am instrument -w -e class "$Class#$m" $target 2>&1
    "=== $m ===" | Add-Content $Log
    $out | Add-Content $Log
    "" | Add-Content $Log
    $text = ($out | Out-String)
    if ($text -match 'OK \(1 test\)') {
        $pass++; Write-Output ("PASS   {0}" -f $m)
    } elseif ($text -match 'OK \(0 tests\)') {
        $norun++
        Write-Output ("NORUN  {0}   <- matched no test; the installed test APK is probably stale" -f $m)
    } else {
        $fail++; $failed += $m; Write-Output ("FAIL   {0}" -f $m)
    }
}
Write-Output ("--- serial={0} pass={1} norun={2} fail={3} total={4} ---" -f $Serial, $pass, $norun, $fail, $methods.Count)
if ($failed.Count -gt 0) { Write-Output ("failed: " + ($failed -join ', ')) }
exit $(if ($fail -gt 0 -or $norun -gt 0) { 1 } else { 0 })
