$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')
$outputDirectory = Join-Path $repoRoot 'mobi\build\native-string-tests'
$outputExecutable = Join-Path $outputDirectory 'utf8_count_test.exe'
$compiler = Get-ChildItem `
    (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages') `
    -Recurse `
    -Filter 'g++.exe' `
    -ErrorAction Stop |
    Where-Object FullName -Like '*BrechtSanders.WinLibs.POSIX.UCRT*' |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $compiler) {
    throw 'WinLibs g++ compiler was not found'
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

& $compiler `
    -std=c++17 `
    -Wall `
    -Wextra `
    -D_GLIBCXX_DEBUG `
    -static `
    -static-libgcc `
    -static-libstdc++ `
    -I (Join-Path $repoRoot 'mobi\src\test\native\host_stubs') `
    -I (Join-Path $repoRoot 'mobi\src\main\cpp\util') `
    -I (Join-Path $repoRoot 'mobi\src\main\cpp\utfcpp\source') `
    (Join-Path $repoRoot 'mobi\src\test\native\utf8_count_test.cpp') `
    (Join-Path $repoRoot 'mobi\src\main\cpp\util\string_ext.cpp') `
    -o $outputExecutable
if ($LASTEXITCODE -ne 0) {
    throw "Native UTF-8 count test compilation failed with exit code $LASTEXITCODE"
}

& $outputExecutable
if ($LASTEXITCODE -ne 0) {
    throw "Native UTF-8 count fixtures failed with exit code $LASTEXITCODE"
}
