[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string] $Serial,

    [string] $AdbPath = '',

    [string] $EvidenceRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Import-Module (Join-Path $PSScriptRoot 'HyperOs3Preflight.psm1') -Force

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    } else {
        $AdbPath = 'adb'
    }
}

function Invoke-AdbText {
    param([Parameter(Mandatory)] [string[]] $Arguments)

    $output = & $AdbPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed for selected device (exit $LASTEXITCODE)"
    }
    return (($output | Out-String).Trim())
}

function Get-DeviceProperty {
    param([Parameter(Mandatory)] [string] $Name)

    if ($state -ne 'device') {
        return ''
    }

    return Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'getprop', $Name)
}

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    $EvidenceRoot = Join-Path $env:TEMP ("PageNest-HyperOS3-Preflight-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null

$devicesOutput = & $AdbPath devices 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Unable to enumerate ADB devices (exit $LASTEXITCODE)"
}

$selectedLine = $devicesOutput |
    Where-Object { $_ -match ('^' + [regex]::Escape($Serial) + '\s+(\S+)') } |
    Select-Object -First 1
if ($null -eq $selectedLine) {
    throw 'The explicitly selected ADB serial is not connected.'
}
$null = $selectedLine -match ('^' + [regex]::Escape($Serial) + '\s+(\S+)')
$state = $Matches[1]

$snapshot = [pscustomobject]@{
    Serial = $Serial
    State = $state
    Product = Get-DeviceProperty 'ro.product.name'
    Model = Get-DeviceProperty 'ro.product.model'
    Device = Get-DeviceProperty 'ro.product.device'
    Manufacturer = Get-DeviceProperty 'ro.product.manufacturer'
    AndroidRelease = Get-DeviceProperty 'ro.build.version.release'
    Sdk = Get-DeviceProperty 'ro.build.version.sdk'
    PrimaryAbi = Get-DeviceProperty 'ro.product.cpu.abi'
    AbiList = Get-DeviceProperty 'ro.product.cpu.abilist'
    KernelQemu = Get-DeviceProperty 'ro.kernel.qemu'
    Hardware = Get-DeviceProperty 'ro.hardware'
    Fingerprint = Get-DeviceProperty 'ro.build.fingerprint'
    HyperOsVersion = Get-DeviceProperty 'ro.mi.os.version.name'
    HyperOsIncremental = Get-DeviceProperty 'ro.mi.os.version.incremental'
}

$result = Test-HyperOs3Snapshot -Snapshot $snapshot
$evidence = [ordered]@{
    CapturedAt = (Get-Date).ToString('o')
    Gate = 'PageNest HyperOS 3 / Android 16 ARM64'
    Passed = $result.Passed
    Reasons = $result.Reasons
    Device = $snapshot
}

$jsonPath = Join-Path $EvidenceRoot 'device-preflight.json'
$textPath = Join-Path $EvidenceRoot 'device-preflight.txt'
$evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding utf8
@(
    "Passed=$($result.Passed)"
    "Reasons=$($result.Reasons -join ',')"
    "Manufacturer=$($snapshot.Manufacturer)"
    "Model=$($snapshot.Model)"
    "Android=$($snapshot.AndroidRelease)"
    "Sdk=$($snapshot.Sdk)"
    "PrimaryAbi=$($snapshot.PrimaryAbi)"
    "HyperOsVersion=$($snapshot.HyperOsVersion)"
    "HyperOsIncremental=$($snapshot.HyperOsIncremental)"
    "Fingerprint=$($snapshot.Fingerprint)"
) | Set-Content -LiteralPath $textPath -Encoding utf8

Write-Output "preflight_passed=$($result.Passed)"
Write-Output "preflight_reasons=$($result.Reasons -join ',')"
Write-Output "evidence_root=$EvidenceRoot"

if (-not $result.Passed) {
    exit 2
}
