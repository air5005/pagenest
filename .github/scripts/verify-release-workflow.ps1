$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$workflowPath = Join-Path $repositoryRoot '.github\workflows\release-apk.yml'

if (-not (Test-Path -LiteralPath $workflowPath)) {
    throw "Release workflow is missing: $workflowPath"
}

$workflowLines = Get-Content -LiteralPath $workflowPath
$workflow = $workflowLines -join "`n"
$requirements = [ordered]@{
    'PageNest tag trigger'        = "'pagenest-v*'"
    'pinned checkout action'      = 'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1'
    'pinned Java setup action'    = 'actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c'
    'pinned Gradle setup action'  = 'gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb'
    'pinned artifact upload'      = 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a'
    'pinned artifact download'    = 'actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c'
    'JDK 17'                      = "java-version: '17'"
    'Android SDK manager path'    = 'sdkmanager_path="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"'
    'Android SDK licenses'        = 'yes | "${sdkmanager_path}" --licenses'
    'app NDK version'             = 'ndk;27.0.12077973'
    'native library NDK version'  = 'ndk;29.0.13599879'
    'portable Gradle invocation'  = 'bash ./gradlew :app:assembleDebug --no-daemon'
    'exact debug APK output'      = 'app/build/outputs/apk/debug/app-debug.apk'
    'versioned APK asset'         = 'PageNest-${tag}-debug.apk'
    'SHA-256 checksum asset'      = 'SHA256SUMS.txt'
    'release creation'            = 'gh release create'
    'idempotent release upload'   = 'gh release upload'
    'GitHub release token'        = 'secrets.GITHUB_TOKEN'
    'explicit release repository' = 'GH_REPO: ${{ github.repository }}'
}

$missing = @(
    foreach ($entry in $requirements.GetEnumerator()) {
        if (-not $workflow.Contains($entry.Value)) {
            $entry.Key
        }
    }
)

if ($missing.Count -gt 0) {
    throw "Release workflow is missing: $($missing -join ', ')"
}

$jobsLine = [Array]::IndexOf($workflowLines, 'jobs:')
$buildLine = [Array]::IndexOf($workflowLines, '  build:')
$releaseLine = [Array]::IndexOf($workflowLines, '  release:')
if ($jobsLine -lt 0 -or $buildLine -le $jobsLine -or $releaseLine -le $buildLine) {
    throw 'Release workflow must contain ordered build and release jobs.'
}

$globalBlock = ($workflowLines[0..($jobsLine - 1)] -join "`n")
$buildBlock = ($workflowLines[$buildLine..($releaseLine - 1)] -join "`n")
$releaseBlock = ($workflowLines[$releaseLine..($workflowLines.Count - 1)] -join "`n")

if ($globalBlock -notmatch '(?m)^permissions:\s*\r?\n\s{2}contents: read\s*$') {
    throw 'Global workflow permissions must be contents: read.'
}
if ($buildBlock -match 'contents: write|secrets\.GITHUB_TOKEN|GH_TOKEN') {
    throw 'The build job must not receive release write permission or a GitHub token.'
}
if ($buildBlock -notmatch '(?m)^\s{10}persist-credentials: false\s*$') {
    throw 'Checkout must disable persisted Git credentials.'
}
if ($releaseBlock -notmatch '(?m)^\s{4}needs: build\s*$' -or
    $releaseBlock -notmatch '(?m)^\s{6}contents: write\s*$') {
    throw 'The release job alone must depend on build and receive contents: write.'
}

$trackedPackages = @(git -C $repositoryRoot ls-files '*.apk' '*.aab')
if ($trackedPackages.Count -gt 0) {
    throw "APK/AAB files must not be tracked: $($trackedPackages -join ', ')"
}

$ignoreFile = Get-Content -LiteralPath (Join-Path $repositoryRoot '.gitignore') -Raw
foreach ($pattern in @('*.apk', '*.aab')) {
    if ($ignoreFile -notmatch "(?m)^$([regex]::Escape($pattern))$") {
        throw "Repository .gitignore must contain $pattern"
    }
}

Write-Host 'Release workflow contract verified.'
