# Build the Zarc native library for the Android ABIs and stage it for Gradle.
#
# The Rust core links the vendored libsodium; the C compiler and the Rust
# linker are taken from the Android NDK.
#
# Usage:
#   tools\build-android.ps1 [-Abi arm64-v8a,x86_64] [-Profile release]
#
# The resulting shared libraries are copied into
# android\app\src\main\jniLibs\<abi>\libzarc_jni.so

param(
    [string[]]$Abi = @('arm64-v8a', 'x86_64', 'armeabi-v7a', 'x86'),
    [string]$Profile = 'release',
    [int]$AndroidApi = 24
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$ndkRoot = $env:ANDROID_NDK_ROOT
if (-not $ndkRoot) { $ndkRoot = $env:ANDROID_NDK_HOME }
if (-not $ndkRoot) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if ($sdk) {
        $candidate = Get-ChildItem -Path (Join-Path $sdk 'ndk') -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($candidate) { $ndkRoot = $candidate.FullName }
    }
}
if (-not $ndkRoot) { throw 'The Android NDK could not be located. Set ANDROID_NDK_ROOT.' }

$toolchain = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64'
if (-not (Test-Path $toolchain)) { throw "The NDK toolchain was not found at $toolchain" }
$binDir = Join-Path $toolchain 'bin'

# Rust target triple, Android ABI directory name, NDK clang prefix and the
# minimum API level of the NDK wrappers.
$targets = @{
    'arm64-v8a'   = @{ triple = 'aarch64-linux-android';  clang = 'aarch64-linux-android';  api = 21 }
    'armeabi-v7a' = @{ triple = 'armv7-linux-androideabi'; clang = 'armv7a-linux-androideabi'; api = 21 }
    'x86_64'      = @{ triple = 'x86_64-linux-android';   clang = 'x86_64-linux-android';   api = 21 }
    'x86'         = @{ triple = 'i686-linux-android';     clang = 'i686-linux-android';     api = 21 }
}

$stageRoot = Join-Path $repoRoot 'android\app\src\main\jniLibs'

foreach ($abiName in $Abi) {
    if (-not $targets.ContainsKey($abiName)) { throw "Unknown ABI: $abiName" }
    $triple = $targets[$abiName].triple
    $api = [Math]::Max($targets[$abiName].api, $AndroidApi)

    # The NDK ships one clang wrapper per API level and per architecture.
    $clangPrefix = $targets[$abiName].clang
    $clang = Join-Path $binDir "$clangPrefix$api-clang.cmd"
    if (-not (Test-Path $clang)) {
        $fallback = Get-ChildItem -Path $binDir -Filter "$clangPrefix*-clang.cmd" |
            Sort-Object Name |
            Select-Object -First 1
        if (-not $fallback) { throw "No NDK clang found for $clangPrefix" }
        $clang = $fallback.FullName
    }

    $ar = Join-Path $binDir 'llvm-ar.exe'
    $ranlib = Join-Path $binDir 'llvm-ranlib.exe'

    Write-Host "==> Building $abiName ($triple, API $api, profile $Profile)"

    # Environment understood by the `cc` crate when it compiles the vendored
    # libsodium for the Android target.
    $envKey = $triple.Replace('-', '_')
    Set-Item -Path "env:CC_$envKey" -Value $clang
    Set-Item -Path "env:AR_$envKey" -Value $ar
    Set-Item -Path "env:RANLIB_$envKey" -Value $ranlib
    Set-Item -Path "env:CARGO_TARGET_$($triple.ToUpper().Replace('-', '_'))_LINKER" -Value $clang

    Push-Location $repoRoot
    try {
        & cargo build -p zarc-jni --target $triple --release
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $triple" }
    }
    finally {
        Pop-Location
    }

    $artifact = Join-Path $repoRoot "target\$triple\release\libzarc_jni.so"
    if (-not (Test-Path $artifact)) { throw "The build did not produce $artifact" }

    $destination = Join-Path $stageRoot $abiName
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -Path $artifact -Destination (Join-Path $destination 'libzarc_jni.so') -Force
    Write-Host "    staged $(Join-Path $destination 'libzarc_jni.so') ($((Get-Item $artifact).Length) bytes)"
}

Write-Host 'Done.'
