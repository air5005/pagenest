$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')
$clang = Join-Path $env:LOCALAPPDATA (
    'Android\Sdk\ndk\29.0.13599879\toolchains\llvm\prebuilt\' +
    'windows-x86_64\bin\clang.exe'
)
$outputDirectory = Join-Path $repoRoot 'mobi\build\native-tests'
$outputLibrary = Join-Path $outputDirectory 'protection_test.dll'
$sourceDirectory = Join-Path $repoRoot 'mobi\src\main\cpp\libmobi\src'
$toolchainDirectory = Split-Path $clang
$testLinker = Join-Path $outputDirectory 'lld-link.exe'

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
Copy-Item (Join-Path $toolchainDirectory 'ld.lld.exe') $testLinker -Force
$env:PATH = "$outputDirectory;$toolchainDirectory;$env:PATH"
& $clang `
    --target=x86_64-pc-windows-msvc `
    -std=c99 `
    -DSAFE_COVER_TESTING `
    -Wall `
    -Wextra `
    -Werror `
    -mno-stack-arg-probe `
    -nostdlib `
    -shared `
    -fuse-ld=lld `
    '-Wl,/noentry' `
    '-Wl,/export:run_protection_tests' `
    '-Wl,/export:run_safe_cover_tests' `
    -I $sourceDirectory `
    -I (Join-Path $repoRoot 'mobi\src\main\cpp\util') `
    (Join-Path $PSScriptRoot 'protection_test.c') `
    (Join-Path $PSScriptRoot 'safe_cover_test.c') `
    (Join-Path $sourceDirectory 'protection_core.c') `
    (Join-Path $repoRoot 'mobi\src\main\cpp\util\safe_cover_writer.c') `
    -o $outputLibrary
if ($LASTEXITCODE -ne 0) {
    throw "Native protection test compilation failed with exit code $LASTEXITCODE"
}

$escapedLibrary = $outputLibrary.Replace('\', '\\')
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class NativeProtectionTests {
    [DllImport("$escapedLibrary", CallingConvention = CallingConvention.Cdecl)]
    public static extern int run_protection_tests();

    [DllImport("$escapedLibrary", CallingConvention = CallingConvention.Cdecl)]
    public static extern int run_safe_cover_tests();
}
"@
$failures = [NativeProtectionTests]::run_protection_tests()
if ($failures -ne 0) {
    throw "$failures native protection fixture(s) failed"
}
Write-Output '9 native protection fixtures passed'
$coverFailures = [NativeProtectionTests]::run_safe_cover_tests()
if ($coverFailures -ne 0) {
    throw "$coverFailures native safe-cover fixture(s) failed"
}
Write-Output '9 native safe-cover fixtures passed'
