param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $PSScriptRoot '../.android-sdk/platform-tools/adb.exe'
function Invoke-Adb([string[]]$Arguments) {
    $result = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $result" }
    return $result
}
foreach ($apk in @('../app/build/outputs/apk/debug/app-debug.apk', '../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    Invoke-Adb @('install', '-r', '-t', (Join-Path $PSScriptRoot $apk)) | Out-Null
}
function Read-Identity {
    $testOutput = Invoke-Adb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.aiwatch.probe.IdentityProcessTest', 'com.aiwatch.probe.test/com.aiwatch.probe.ProductTestRunner')
    if (($testOutput -join "`n") -notmatch 'OK \(1 test\)') { throw "Instrumentation did not pass: $testOutput" }
    return @(Invoke-Adb @('shell', 'run-as', 'com.aiwatch.probe', 'cat', 'files/identity-process-evidence.txt'))
}
$first = Read-Identity
Invoke-Adb @('shell', 'am', 'force-stop', 'com.aiwatch.probe') | Out-Null
$second = Read-Identity
if ($first.Count -ne 3 -or $second.Count -ne 3) { throw 'Incomplete identity evidence' }
if ($first[0] -ne $second[0] -or $first[1] -ne $second[1]) { throw 'Identity changed after process restart' }
if ($first[2] -eq $second[2]) { throw 'Different process not proven; repeat test' }
Write-Output 'PASS: two different Android processes returned the same persisted identity. No reset or clear-data performed.'
Write-Output "Process evidence: first PID=$($first[2]), second PID=$($second[2]); device identifiers redacted."
