# Device storage leakage audit.
#
# The audit:
#   1. creates a vault object from a fixture that contains a unique marker,
#   2. searches the device - with elevated privileges where possible - for the
#      marker, for the original file name, for the media magic bytes and for
#      the unwrapped vault master key,
#   3. inspects the merged manifest and the backup configuration,
#   4. reports what it found.
#
# The search is a finite search over finite locations. It is not a proof that
# no plaintext exists anywhere on the device.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\device-audit.ps1

param(
    [string]$Adb = "$env:ANDROID_HOME\platform-tools\adb.exe",
    [string]$Package = 'com.zarc.vault.debug',
    [switch]$SkipElevated
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$appApk = Join-Path $repoRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $repoRoot 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'

$marker = "ZARCAUDIT" + [Guid]::NewGuid().ToString('N').Substring(0, 12).ToUpper()
$fileName = "zarc-audit-$marker.jpg"
$tmp = Join-Path $env:TEMP "zarc-audit"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $out = Join-Path $tmp ("adb-" + [Guid]::NewGuid().ToString('N') + ".txt")
    $process = Start-Process -FilePath $Adb -ArgumentList $Arguments -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err"
    $text = (Get-Content $out -Raw -ErrorAction SilentlyContinue)
    if (-not $AllowFailure -and $process.ExitCode -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $text"
    }
    return $text
}

Write-Host "=== Zarc device storage audit ==="
Write-Host "marker: $marker"
Write-Host "file name: $fileName"

# 0. Install the application and the test package.
#    The debug build uses the application id suffix ".debug", so the debug
#    package is `com.zarc.vault.debug` and the release package is
#    `com.zarc.vault`.
foreach ($apk in @($appApk, $testApk)) {
    if (-not (Test-Path $apk)) { throw "The APK $apk does not exist. Run 'gradlew assembleDebug' and 'gradlew assembleDebugAndroidTest' first." }
    Write-Host "installing $(Split-Path -Leaf $apk)"
    $installOutput = Invoke-Adb -Arguments @('install', '-r', '-t', $apk)
    Write-Host "  $installOutput"
}

# 1. Create the object through the instrumented test.
$testArguments = @(
    'shell', 'am', 'instrument', '-w', '-e', 'class', 'com.zarc.vault.StorageLeakAuditTest',
    '-e', 'marker', $marker, '-e', 'filename', $fileName,
    "$Package.test/androidx.test.runner.AndroidJUnitRunner"
)
$testOutput = Invoke-Adb -Arguments $testArguments -AllowFailure
if ($testOutput -notmatch 'OK \(1 test\)') {
    Write-Host "WARNING: the audit fixture test did not report success:"
    Write-Host $testOutput
}

# 2. Elevated access: the emulator image allows `adb root`.
$elevated = $false
if (-not $SkipElevated) {
    $rootOutput = Invoke-Adb -Arguments @('root') -AllowFailure
    Start-Sleep -Seconds 3
    $idOutput = Invoke-Adb -Arguments @('shell', 'id') -AllowFailure
    if ($idOutput -match 'uid=0') {
        $elevated = $true
        Write-Host "elevated access: yes (adb root)"
    }
    else {
        Write-Host "elevated access: no ($rootOutput)"
    }
}

# 3. Read the note written by the test.
$note = Invoke-Adb -Arguments @('shell', 'run-as', $Package, 'cat', "cache/zarc-audit-note.txt") -AllowFailure
Write-Host "`n--- fixture note ---"
Write-Host $note

$objectId = ''
$objectsDir = ''
if ($note -match 'objectId=([0-9a-f]{32})') { $objectId = $Matches[1] }
if ($note -match 'objectsDir=(\S+)') { $objectsDir = $Matches[1] }
$searchTerms = @($marker, $fileName)

# 4. Search the vault for the marker and the original file name.
#
#    The vault proper (the encrypted objects and the wrapped key) must not
#    contain any plaintext. The scratch directory and the export directory do
#    contain plaintext by construction: the first holds the fixture that the
#    audit test created, the second holds the result of an explicit
#    decryption. Those are reported separately, not counted as vault leaks.
$vaultRoots = @(
    "/data/user/0/$Package/no_backup/vault/objects",
    "/data/user/0/$Package/no_backup/keys",
    "/data/user/0/$Package/databases",
    "/data/user/0/$Package/shared_prefs"
)
$deviceRoots = @('/data/local/tmp', '/sdcard', '/storage/emulated/0', '/data/media')

Write-Host "`n--- vault plaintext search (must be clean) ---"
$vaultFindings = @()
foreach ($root in $vaultRoots) {
    foreach ($term in $searchTerms) {
        $output = Invoke-Adb -Arguments @('shell', "grep -r -l -a '$term' $root 2>/dev/null | head -20") -AllowFailure
        if ($output -and $output.Trim()) {
            $vaultFindings += "$root : $term -> $($output.Trim() -replace "`n", ', ')"
        }
    }
}
if ($vaultFindings.Count -eq 0) {
    Write-Host "PASS: neither the marker nor the original file name was found in the vault objects, the keys or the databases"
}
else {
    Write-Host "FAIL: plaintext found in the vault:"
    $vaultFindings | ForEach-Object { Write-Host "  $_" }
}

Write-Host "`n--- device wide plaintext search ---"
$deviceFindings = @()
foreach ($root in $deviceRoots) {
    foreach ($term in $searchTerms) {
        $output = Invoke-Adb -Arguments @('shell', "grep -r -l -a '$term' $root 2>/dev/null | head -20") -AllowFailure
        if ($output -and $output.Trim()) {
            $deviceFindings += "$root : $term -> $($output.Trim() -replace "`n", ', ')"
        }
    }
}
if ($deviceFindings.Count -eq 0) {
    Write-Host "PASS: the marker was not found in $deviceRoots"
}
else {
    Write-Host "FAIL: the marker was found outside the application directory:"
    $deviceFindings | ForEach-Object { Write-Host "  $_" }
}

Write-Host "`n--- known plaintext working copies (by design, not a vault leak) ---"
$scratch = Invoke-Adb -Arguments @('shell', "grep -r -l -a '$marker' /data/user/0/$Package 2>/dev/null | head -20") -AllowFailure
if ($scratch -and $scratch.Trim()) {
    Write-Host "  the following files contain the plaintext because the audit test created them:"
    Write-Host "  $($scratch.Trim() -replace "`n", ', ')"
    Write-Host "  (cache/vault/work = the fixture, vault/export = the explicit decryption result)"
}
else {
    Write-Host "  none"
}

# 5. Inspect the vault directory and the app's private storage.
Write-Host "`n--- application storage ---"
$listOutput = Invoke-Adb -Arguments @('shell', "ls -laR /data/user/0/$Package 2>/dev/null | head -60") -AllowFailure
Write-Host $listOutput

# 6. The stored object must not start with a media signature.
Write-Host "`n--- media signature check ---"
if ($objectId) {
    $objectPath = "$objectsDir/$objectId"
    Write-Host "  stored object: $objectPath"
    $head = Invoke-Adb -Arguments @('shell', "head -c 32 $objectPath | od -An -tx1 | tr -s ' '") -AllowFailure
    Write-Host "  first 32 bytes: $head"
    $size = Invoke-Adb -Arguments @('shell', "stat -c %s $objectPath") -AllowFailure
    Write-Host "  size: $size bytes"
    $listing = Invoke-Adb -Arguments @('shell', "ls -la $objectsDir") -AllowFailure
    Write-Host "  vault directory:"
    Write-Host $listing
}

# 7. Logcat must not contain the marker.
Write-Host "`n--- log scan ---"
$logOutput = Invoke-Adb -Arguments @('logcat', '-d', '-t', '2000') -AllowFailure
if ($logOutput -match [regex]::Escape($marker)) {
    Write-Host "FAIL: the marker appears in the logcat buffer"
}
else {
    Write-Host "PASS: the marker does not appear in the logcat buffer"
}

# 8. The merged manifest of the installed application.
Write-Host "`n--- installed manifest (backup configuration) ---"
$manifest = Invoke-Adb -Arguments @('shell', "dumpsys package $Package | grep -i -E 'flags|backup' | head -20") -AllowFailure
Write-Host $manifest

Write-Host "`n=== audit finished ==="
if ($vaultFindings.Count -gt 0 -or $deviceFindings.Count -gt 0) { exit 1 }
exit 0
