param([switch]$Online)

$ErrorActionPreference = "Stop"
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
Push-Location -LiteralPath $projectRoot
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        foreach ($candidate in @('C:\Program Files\Java\latest\jdk-21', 'C:\Program Files\Android\Android Studio\jbr')) {
            if (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) { $env:JAVA_HOME = $candidate; break }
        }
    }
    if ($env:JAVA_HOME) { $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path }
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle'
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.android'
    $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    & python (Join-Path $PSScriptRoot 'static_check.py')
    if ($LASTEXITCODE -ne 0) { throw 'Source static checks failed.' }
    $gradleArguments = @('--no-daemon', '-PstaticOnlyBuild=true', "-PapkBuildEpoch=$epoch", ':app:lintDebug', ':app:exportDebugApks')
    if (-not $Online) { $gradleArguments = @('--offline') + $gradleArguments }
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Static checks / debug packaging failed: $LASTEXITCODE" }
    & python (Join-Path $PSScriptRoot 'verify_debug_apks.py')
    if ($LASTEXITCODE -ne 0) { throw 'Finished APK static inspection failed.' }
    $outputs = Get-Content -LiteralPath (Join-Path $projectRoot 'build\debug-export.json') -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($output in $outputs) {
        if ([long]$output.versionCode -ne $epoch) { throw 'APK metadata timestamp mismatch.' }
        $apk = Join-Path $projectRoot $output.file
        if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $output.sha256) {
            throw "APK checksum mismatch: $($output.file)"
        }
        Write-Output ("{0} | version={1} ({2}) | {3:N2} MiB" -f $output.file, $output.versionName, $output.versionCode, ($output.bytes / 1MB))
    }
} finally { Pop-Location }
