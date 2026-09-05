param(
    [Parameter(Mandatory = $true)] [string] $ProjectDirectory,
    [Parameter(Mandatory = $true)] [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ndkRoot = Join-Path $sdk 'ndk'
$ndk = Get-ChildItem -LiteralPath $ndkRoot -Directory -ErrorAction Stop |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $ndk) { throw "No Android NDK found below $ndkRoot" }
$clang = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$linker = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\ld.lld.exe'
if (-not (Test-Path -LiteralPath $clang -PathType Leaf)) { throw "NDK clang is unavailable: $clang" }
if (-not (Test-Path -LiteralPath $linker -PathType Leaf)) { throw "NDK linker is unavailable: $linker" }

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$testSource = Join-Path $ProjectDirectory 'src\test\cpp\pixel_buffer_size_test.cpp'
$testObject = Join-Path $OutputDirectory 'pixel_buffer_size_test.o'
$library = Join-Path $OutputDirectory 'pixel_buffer_size_test.dll'
& $clang --target=x86_64-w64-windows-gnu -std=c++11 -Wall -Wextra -Werror `
    -ffreestanding -fno-builtin -fno-exceptions -fno-rtti -c $testSource -o $testObject
if ($LASTEXITCODE -ne 0) { throw "ARGB size test compilation failed: $LASTEXITCODE" }
& $linker -m i386pep --dll --entry DllMainCRTStartup --export-all-symbols $testObject -o $library
if ($LASTEXITCODE -ne 0) { throw "ARGB size test link failed: $LASTEXITCODE" }

$escapedLibrary = $library.Replace('\', '\\')
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class PageNestPixelBufferSizeNative {
    [DllImport("$escapedLibrary", CallingConvention = CallingConvention.Cdecl)]
    public static extern int pixel_buffer_size_self_test();
}
"@
$result = [PageNestPixelBufferSizeNative]::pixel_buffer_size_self_test()
if ($result -ne 0) { throw "ARGB buffer size validation failed case $result" }
Write-Output 'pixel_buffer_size_validation=PASS (17 boundary cases)'
