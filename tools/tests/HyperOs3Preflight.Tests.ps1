$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$modulePath = Join-Path $PSScriptRoot '..\HyperOs3Preflight.psm1'
if (-not (Test-Path -LiteralPath $modulePath)) {
    throw "RED: missing preflight module: $modulePath"
}

Import-Module $modulePath -Force

function Assert-True {
    param(
        [Parameter(Mandatory)] [bool] $Condition,
        [Parameter(Mandatory)] [string] $Message
    )

    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
}

function New-ValidSnapshot {
    [pscustomobject]@{
        Serial = 'physical-device'
        State = 'device'
        Product = 'duchamp'
        Model = '2407FRK8EC'
        Device = 'duchamp'
        Manufacturer = 'Xiaomi'
        AndroidRelease = '16'
        Sdk = '36'
        PrimaryAbi = 'arm64-v8a'
        AbiList = 'arm64-v8a,armeabi-v7a'
        KernelQemu = '0'
        Hardware = 'mt6989'
        Fingerprint = 'Xiaomi/duchamp/duchamp:16/test/release-keys'
        HyperOsVersion = 'OS3.0'
        HyperOsIncremental = '3.0.303.0.WNNCNXM.C11'
    }
}

$valid = Test-HyperOs3Snapshot -Snapshot (New-ValidSnapshot)
Assert-True $valid.Passed 'valid ARM64 HyperOS 3 snapshot should pass'
Assert-True ($valid.Reasons.Count -eq 0) 'valid snapshot should have no rejection reason'

$longManufacturerSnapshot = New-ValidSnapshot
$longManufacturerSnapshot.Manufacturer = 'Xiaomi Communications Co., Ltd.'
$longManufacturer = Test-HyperOs3Snapshot -Snapshot $longManufacturerSnapshot
Assert-True $longManufacturer.Passed 'official long Xiaomi manufacturer value should pass'

$emulatorSnapshot = New-ValidSnapshot
$emulatorSnapshot.Serial = 'emulator-5554'
$emulatorSnapshot.KernelQemu = '1'
$emulatorSnapshot.Model = 'sdk_gphone64_x86_64'
$emulatorSnapshot.PrimaryAbi = 'x86_64'
$emulator = Test-HyperOs3Snapshot -Snapshot $emulatorSnapshot
Assert-True (-not $emulator.Passed) 'emulator must be rejected'
Assert-True ($emulator.Reasons -contains 'emulator') 'emulator reason must be stable'
Assert-True ($emulator.Reasons -contains 'primary-abi') 'x86_64 reason must be stable'

$wrongAndroidSnapshot = New-ValidSnapshot
$wrongAndroidSnapshot.AndroidRelease = '15'
$wrongAndroidSnapshot.Sdk = '35'
$wrongAndroid = Test-HyperOs3Snapshot -Snapshot $wrongAndroidSnapshot
Assert-True (-not $wrongAndroid.Passed) 'wrong Android version must be rejected'
Assert-True ($wrongAndroid.Reasons -contains 'android-release') 'release reason must be stable'
Assert-True ($wrongAndroid.Reasons -contains 'android-sdk') 'SDK reason must be stable'

$wrongAbiSnapshot = New-ValidSnapshot
$wrongAbiSnapshot.PrimaryAbi = 'armeabi-v7a'
$wrongAbi = Test-HyperOs3Snapshot -Snapshot $wrongAbiSnapshot
Assert-True (-not $wrongAbi.Passed) 'non-arm64 primary ABI must be rejected'
Assert-True ($wrongAbi.Reasons -contains 'primary-abi') 'ABI reason must be stable'

$missingHyperOsSnapshot = New-ValidSnapshot
$missingHyperOsSnapshot.HyperOsVersion = ''
$missingHyperOsSnapshot.HyperOsIncremental = ''
$missingHyperOs = Test-HyperOs3Snapshot -Snapshot $missingHyperOsSnapshot
Assert-True (-not $missingHyperOs.Passed) 'missing HyperOS identity must be rejected'
Assert-True ($missingHyperOs.Reasons -contains 'hyperos-version') 'HyperOS reason must be stable'

$unauthorizedSnapshot = New-ValidSnapshot
$unauthorizedSnapshot.State = 'unauthorized'
$unauthorized = Test-HyperOs3Snapshot -Snapshot $unauthorizedSnapshot
Assert-True (-not $unauthorized.Passed) 'unauthorized device must be rejected'
Assert-True ($unauthorized.Reasons -contains 'adb-state') 'ADB state reason must be stable'

Write-Output 'HyperOs3Preflight.Tests.ps1: PASS (7 cases)'
