# Canonical product gate: one invocation, no method/process resets, no zero/skip PASS.
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb,
    [string]$Log = 'product-suite.log',
    [int]$ExpectedWidthDp = 205,
    [int]$ExpectedHeightDp = 251,
    [float]$ExpectedFontScale = 1.0
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Adb) {
    $sdk = $env:ANDROID_HOME
    $localProps = Join-Path $repo 'local.properties'
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $sdk = ($line -split '=', 2)[1].Trim().Replace('\\', '\').Replace('\:', ':') }
    }
    $adbName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'adb.exe' } else { 'adb' }
    $Adb = if ($sdk) { Join-Path $sdk "platform-tools/$adbName" } else { 'adb' }
}
$classes = @(
    # Warm the persistent identity first: corruption fixture must still work in this same process.
    'com.aiwatch.probe.IdentityProcessTest',
    'com.aiwatch.probe.memory.MemoryTrustTest',
    'com.aiwatch.probe.CompanionHomeTest',
    'com.aiwatch.probe.ProductShellTest',
    'com.aiwatch.probe.DaylightUiTest',
    'com.aiwatch.probe.AdaptiveWindowTest',
    'com.aiwatch.probe.operator.DeviceOperatorAndroidTest'
)
$expected = 0
foreach ($class in $classes) {
    $src = Join-Path $repo ('app/src/androidTest/kotlin/' + $class.Replace('.', '/') + '.kt')
    $count = [regex]::Matches((Get-Content $src -Raw), '(?m)^\s*@Test\b').Count
    if ($count -eq 0) { throw "No test annotations in $class" }
    $expected += $count
}
$fontScaleText = $ExpectedFontScale.ToString([System.Globalization.CultureInfo]::InvariantCulture)
$output = & $Adb -s $Serial shell am instrument -w -r -e class ($classes -join ',') `
    -e expectedWidthDp $ExpectedWidthDp -e expectedHeightDp $ExpectedHeightDp `
    -e expectedFontScale $fontScaleText `
    com.aiwatch.probe.test/com.aiwatch.probe.ProductTestRunner 2>&1
$code = $LASTEXITCODE
$text = $output -join "`n"
@("serial=$Serial", "classes=$($classes -join ',')", "expected_count=$expected",
  "fixture=${ExpectedWidthDp}x${ExpectedHeightDp}dp fontScale=$ExpectedFontScale", $text) | Set-Content $Log
$output | Write-Output
if ($code -ne 0 -or $text -notmatch "OK \($expected tests\)" -or
    $text -match 'INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)\b') {
    throw "Product suite failed, skipped or did not execute all $expected tests; see $Log"
}
Write-Output "PASS: $expected/$expected product tests in one invocation; SOFTWARE EMULATOR evidence only."
