# One command verification gate.
#
# Runs every check that can be executed without a device, then the Android
# build, and finally the on-device tests if a device is connected.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1 -SkipAndroid
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1 -SkipDevice

param(
    [switch]$SkipAndroid,
    [switch]$SkipDevice,
    [switch]$SkipFuzz
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$results = @()

function Invoke-Step {
    param([string]$Name, [scriptblock]$Action, [switch]$Optional)
    Write-Host "`n=== $Name ==="
    $start = Get-Date
    try {
        & $Action
        if ($LASTEXITCODE -ne 0) { throw "exit code $LASTEXITCODE" }
        $script:results += [pscustomobject]@{ Step = $Name; Result = 'PASS'; Seconds = [int]((Get-Date) - $start).TotalSeconds }
    }
    catch {
        if ($Optional) {
            $script:results += [pscustomobject]@{ Step = $Name; Result = 'SKIPPED'; Seconds = [int]((Get-Date) - $start).TotalSeconds }
            Write-Host "skipped: $($_.Exception.Message)"
        }
        else {
            $script:results += [pscustomobject]@{ Step = $Name; Result = 'FAIL'; Seconds = [int]((Get-Date) - $start).TotalSeconds }
            Write-Host "FAILED: $($_.Exception.Message)"
        }
    }
}

Push-Location $repoRoot
try {
    Invoke-Step -Name 'cargo fmt --check' -Action { cargo fmt --all -- --check }
    Invoke-Step -Name 'cargo clippy -D warnings' -Action { cargo clippy --workspace --all-targets -- -D warnings }
    Invoke-Step -Name 'cargo test --workspace' -Action { cargo test --workspace }
    Invoke-Step -Name 'dependency and license audit' -Action { python tools/audit-deps.py }

    if (-not $SkipFuzz) {
        Invoke-Step -Name 'fuzz targets build' -Optional -Action {
            $env:PATH = "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Tools\MSVC\14.50.35717\bin\HostX64\x64;" + $env:PATH
            Push-Location fuzz
            try { cargo +nightly fuzz build } finally { Pop-Location }
        }
    }

    if (-not $SkipAndroid) {
        Invoke-Step -Name 'android native libraries (4 ABIs)' -Action {
            foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
                & powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-android.ps1 -Abi $abi
                if ($LASTEXITCODE -ne 0) { throw "the $abi build failed" }
            }
        }
        Invoke-Step -Name 'android debug APK' -Action {
            Push-Location android
            try { & .\gradlew.bat assembleDebug --no-daemon } finally { Pop-Location }
        }
        Invoke-Step -Name 'android release APK (R8)' -Action {
            Push-Location android
            try { & .\gradlew.bat assembleRelease --no-daemon } finally { Pop-Location }
        }
    }

    if (-not $SkipDevice) {
        Invoke-Step -Name 'android instrumented tests on the device' -Optional -Action {
            Push-Location android
            try { & .\gradlew.bat connectedDebugAndroidTest --no-daemon } finally { Pop-Location }
        }
        Invoke-Step -Name 'device storage leakage audit' -Optional -Action {
            & powershell -NoProfile -ExecutionPolicy Bypass -File tools\device-audit.ps1
        }
    }
}
finally {
    Pop-Location
}

Write-Host "`n================ SUMMARY ================"
$results | Format-Table -AutoSize
$failed = @($results | Where-Object { $_.Result -eq 'FAIL' })
Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "RESULT: FAIL ($($failed.Count) step(s) failed)"
    exit 1
}
Write-Host 'RESULT: PASS (every executed step succeeded)'
exit 0
