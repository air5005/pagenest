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
if (-not (Test-Path -LiteralPath $clang -PathType Leaf)) { throw "NDK clang is unavailable: $clang" }
$linker = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\ld.lld.exe'
if (-not (Test-Path -LiteralPath $linker -PathType Leaf)) { throw "NDK linker is unavailable: $linker" }

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$testSource = Join-Path $ProjectDirectory 'src\test\cpp\private_book_store_validation_test.c'
$implementation = Join-Path $ProjectDirectory 'src\main\cpp\private_book_store_validation.c'
$publishTestSource = Join-Path $ProjectDirectory 'src\test\cpp\private_book_store_publish_test.c'
$publishImplementation = Join-Path $ProjectDirectory 'src\main\cpp\private_book_store_publish.c'
$library = Join-Path $OutputDirectory 'private_book_store_validation_test.dll'
$testObject = Join-Path $OutputDirectory 'validation_test.o'
$implementationObject = Join-Path $OutputDirectory 'validation.o'
$publishTestObject = Join-Path $OutputDirectory 'publish_test.o'
$publishImplementationObject = Join-Path $OutputDirectory 'publish.o'
& $clang --target=x86_64-w64-windows-gnu -std=c11 -Wall -Wextra -Werror -ffreestanding -fno-builtin `
    -c $testSource -o $testObject
if ($LASTEXITCODE -ne 0) { throw "Native validator test compilation failed: $LASTEXITCODE" }
& $clang --target=x86_64-w64-windows-gnu -std=c11 -Wall -Wextra -Werror -ffreestanding -fno-builtin `
    -c $implementation -o $implementationObject
if ($LASTEXITCODE -ne 0) { throw "Native validator implementation compilation failed: $LASTEXITCODE" }
& $clang --target=x86_64-w64-windows-gnu -std=c11 -Wall -Wextra -Werror -ffreestanding -fno-builtin `
    -c $publishTestSource -o $publishTestObject
if ($LASTEXITCODE -ne 0) { throw "Native publication test compilation failed: $LASTEXITCODE" }
& $clang --target=x86_64-w64-windows-gnu -std=c11 -Wall -Wextra -Werror -ffreestanding -fno-builtin `
    -c $publishImplementation -o $publishImplementationObject
if ($LASTEXITCODE -ne 0) { throw "Native publication implementation compilation failed: $LASTEXITCODE" }
& $linker -m i386pep --dll --entry DllMainCRTStartup --export-all-symbols `
    $testObject $implementationObject $publishTestObject $publishImplementationObject -o $library
if ($LASTEXITCODE -ne 0) { throw "Native validator link failed: $LASTEXITCODE" }

$escapedLibrary = $library.Replace('\', '\\')
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class PrivateBookStoreValidationNative {
    [DllImport("$escapedLibrary", CallingConvention = CallingConvention.Cdecl)]
    public static extern int private_book_store_validation_self_test();
}
"@
$result = [PrivateBookStoreValidationNative]::private_book_store_validation_self_test()
if ($result -ne 0) { throw "Native validator failed case $result" }
Write-Output 'private_book_store_native_validation=PASS'
