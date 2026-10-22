# Watch the user interface sources and refresh the app on the device.
#
# This is the closest thing to a hot reload that is possible without the
# Android Studio IDE: every change of a watched source triggers an
# *incremental* debug build, an install and a relaunch of the application.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\watch-ui.ps1
#
# Options:
#   -Device  <serial>   the device to use (the first connected one by default)
#   -Package <id>       the application id (com.zarc.vault.debug by default)
#   -Activity <class>   the activity to launch
#   -NoBuild            only relaunch, do not rebuild
#
# The build is incremental: only the changed Kotlin files are recompiled, and
# the Gradle daemon and the configuration cache keep the rest of the build
# warm. The release build is never touched by this script.

param(
    [string]$Device = "",
    [string]$Package = "com.zarc.vault.debug",
    [string]$Activity = "com.zarc.vault.ui.GalleryActivity",
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot 'android'
$watchPaths = @(
    (Join-Path $androidRoot 'app\src\main\java\com\zarc\vault\ui'),
    (Join-Path $androidRoot 'app\src\main\java\com\zarc\vault\gallery'),
    (Join-Path $androidRoot 'app\src\main\java\com\zarc\vault\core'),
    (Join-Path $androidRoot 'app\src\main\res')
)
$gradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.6.1-bin\4ticwg1pgcbps2hj28r8so764\gradle-9.6.1\bin\gradle.bat"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"

function Get-Target {
    if ($Device) { return $Device }
    $line = (& $adb devices | Select-String "device$" | Select-Object -First 1)
    if (-not $line) { throw 'no device is connected' }
    return ($line.ToString() -split "\s+")[0]
}

function Invoke-Build {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Push-Location $androidRoot
    try {
        & $gradle ':app:assembleDebug' '--offline' '-q'
        if ($LASTEXITCODE -ne 0) { throw 'the build failed' }
    } finally {
        Pop-Location
    }
    $sw.Stop()
    return [int]$sw.Elapsed.TotalSeconds
}

function Install-And-Launch {
    $apk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    & $adb -s $target install -r -t $apk | Out-Null
    & $adb -s $target shell am start -n "$Package/$Activity" | Out-Null
    # The reload receiver refreshes the screen of the running application.
    & $adb -s $target shell am broadcast -a 'com.zarc.vault.action.RELOAD' -p $Package | Out-Null
}

$target = Get-Target
Write-Host "watching the user interface sources for the device $target"

$last = @{}
foreach ($path in $watchPaths) {
    if (Test-Path $path) {
        Get-ChildItem -Path $path -Recurse -File | ForEach-Object { $last[$_.FullName] = $_.LastWriteTimeUtc }
    }
}
Write-Host "watching $($last.Count) files. Press Ctrl+C to stop."

while ($true) {
    Start-Sleep -Milliseconds 700
    $changed = @()
    foreach ($path in $watchPaths) {
        if (-not (Test-Path $path)) { continue }
        Get-ChildItem -Path $path -Recurse -File | ForEach-Object {
            $known = $last[$_.FullName]
            if ($null -eq $known -or $known -ne $_.LastWriteTimeUtc) {
                $changed += $_.FullName
                $last[$_.FullName] = $_.LastWriteTimeUtc
            }
        }
    }
    if ($changed.Count -eq 0) { continue }

    Write-Host ("`n[{0}] {1} file(s) changed" -f (Get-Date -Format 'HH:mm:ss'), $changed.Count)
    $changed | ForEach-Object { Write-Host "    $(Split-Path -Leaf $_)" }
    if (-not $NoBuild) {
        try {
            $seconds = Invoke-Build
            Write-Host "    built in $seconds s"
        } catch {
            Write-Host "    build failed: $($_.Exception.Message)"
            continue
        }
    }
    Install-And-Launch
    Write-Host '    installed and reloaded'
}
