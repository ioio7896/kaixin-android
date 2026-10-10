param(
    [string]$Device = "",
    [string]$Phase = ("validation-" + (Get-Date -Format "yyyyMMdd-HHmmss")),
    [switch]$SkipBuild,
    [switch]$JvmOnly
)

$ErrorActionPreference = "Stop"
$project = Split-Path -Parent $PSScriptRoot
Set-Location $project

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdk21 = "C:\Program Files\Java\latest\jdk-21"
    if (Test-Path (Join-Path $jdk21 "bin\java.exe")) { $env:JAVA_HOME = $jdk21 }
}
if ($env:JAVA_HOME) { $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path }

if (-not $SkipBuild) {
    & .\gradlew.bat --offline --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "Gradle validation failed with exit code $LASTEXITCODE" }
}
if ($JvmOnly) { exit 0 }

$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found under ANDROID_HOME: $adb" }
if (-not $Device) {
    $Device = (& $adb devices | Select-String "\sdevice$" | Select-Object -First 1).ToString().Split("`t")[0]
}
if (-not $Device) { throw "No Android device or emulator is connected" }

$appApk = "build\offline-ime-app\outputs\apk\debug\app-debug.apk"
$testApk = "build\offline-ime-app\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
& $adb -s $Device install -r $appApk
if ($LASTEXITCODE -ne 0) { throw "Failed to install debug APK" }
& $adb -s $Device install -r $testApk
if ($LASTEXITCODE -ne 0) { throw "Failed to install Android test APK" }
& $adb -s $Device shell settings put secure show_ime_with_hard_keyboard 1

$groups = @("smoke", "input", "panels", "stability", "features", "performance")
foreach ($group in $groups) {
    $runPhase = "$Phase-$group"
    $output = & $adb -s $Device shell am instrument -w -r -e phase $runPhase -e group $group `
        com.example.offlineime.test/com.example.offlineime.FrontendStabilityInstrumentation 2>&1
    $output | Write-Host
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch "Frontend stability: .* 0 failures") {
        throw "Device group '$group' failed"
    }
}

$artifactRoot = Join-Path $project ("test-artifacts\" + $Phase)
New-Item -ItemType Directory -Force $artifactRoot | Out-Null
foreach ($group in $groups) {
    $runPhase = "$Phase-$group"
    $groupRoot = Join-Path $artifactRoot $group
    New-Item -ItemType Directory -Force $groupRoot | Out-Null
    & $adb -s $Device pull "/sdcard/Android/data/com.example.offlineime/files/frontend-stability/$runPhase/." `
        $groupRoot | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to pull artifacts for group '$group'" }
}
Write-Host "Validation artifacts: $artifactRoot"
