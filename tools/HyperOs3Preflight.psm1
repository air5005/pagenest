Set-StrictMode -Version Latest

function Get-SnapshotText {
    param(
        [Parameter(Mandatory)] [object] $Snapshot,
        [Parameter(Mandatory)] [string] $Name
    )

    $property = $Snapshot.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return ''
    }

    return ([string] $property.Value).Trim()
}

function Test-HyperOs3Snapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [object] $Snapshot
    )

    $reasons = [System.Collections.Generic.List[string]]::new()
    $serial = Get-SnapshotText -Snapshot $Snapshot -Name 'Serial'
    $state = Get-SnapshotText -Snapshot $Snapshot -Name 'State'
    $product = Get-SnapshotText -Snapshot $Snapshot -Name 'Product'
    $model = Get-SnapshotText -Snapshot $Snapshot -Name 'Model'
    $device = Get-SnapshotText -Snapshot $Snapshot -Name 'Device'
    $manufacturer = Get-SnapshotText -Snapshot $Snapshot -Name 'Manufacturer'
    $release = Get-SnapshotText -Snapshot $Snapshot -Name 'AndroidRelease'
    $sdk = Get-SnapshotText -Snapshot $Snapshot -Name 'Sdk'
    $primaryAbi = Get-SnapshotText -Snapshot $Snapshot -Name 'PrimaryAbi'
    $kernelQemu = Get-SnapshotText -Snapshot $Snapshot -Name 'KernelQemu'
    $fingerprint = Get-SnapshotText -Snapshot $Snapshot -Name 'Fingerprint'
    $hyperOsVersion = Get-SnapshotText -Snapshot $Snapshot -Name 'HyperOsVersion'
    $hyperOsIncremental = Get-SnapshotText -Snapshot $Snapshot -Name 'HyperOsIncremental'

    if ($state -ne 'device') {
        $reasons.Add('adb-state')
    }

    $emulatorIdentity = "$serial $product $model $device $fingerprint"
    if (
        $kernelQemu -eq '1' -or
        $serial -match '^emulator-' -or
        $emulatorIdentity -match '(?i)sdk_gphone|generic_x86|emulator|emu64'
    ) {
        $reasons.Add('emulator')
    }

    if ($manufacturer -notmatch '(?i)^(xiaomi|redmi)(?:\s|$)') {
        $reasons.Add('manufacturer')
    }

    if ($release -ne '16') {
        $reasons.Add('android-release')
    }

    if ($sdk -ne '36') {
        $reasons.Add('android-sdk')
    }

    if ($primaryAbi -ne 'arm64-v8a') {
        $reasons.Add('primary-abi')
    }

    $hyperOsIdentity = "$hyperOsVersion $hyperOsIncremental".Trim()
    if (
        [string]::IsNullOrWhiteSpace($hyperOsIdentity) -or
        $hyperOsIdentity -notmatch '(?i)(?:hyperos|os)?\s*3(?:\.|\s|$)'
    ) {
        $reasons.Add('hyperos-version')
    }

    return [pscustomobject]@{
        Passed = ($reasons.Count -eq 0)
        Reasons = [string[]] $reasons.ToArray()
    }
}

Export-ModuleMember -Function Test-HyperOs3Snapshot
