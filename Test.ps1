param(
    [string]$CacheRoot = (Join-Path $env:LOCALAPPDATA 'PrefabDeploy'),
    [string]$NeoForgeVersion,
    [string]$ReportDir,
    [switch]$Compat,
    [string]$VssJar,
    [switch]$Crash,
    [switch]$Client
)
$ErrorActionPreference = 'Stop'
$taskRunRoot = Join-Path $CacheRoot ('tests/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $taskRunRoot | Out-Null
$taskBuildScript = Join-Path $PSScriptRoot 'Build.ps1'
$taskArgs = @{ CacheRoot = $CacheRoot; Compat = $Compat; VssJar = $VssJar; NeoForgeVersion = $NeoForgeVersion }
$taskReport = if ($ReportDir) {
    $taskReportPath = if ([IO.Path]::IsPathFullyQualified($ReportDir)) { $ReportDir } else { Join-Path $PSScriptRoot $ReportDir }
    [IO.Path]::GetFullPath($taskReportPath)
} else { Join-Path $PSScriptRoot 'docs/test-results' }
$taskArgs.ReportDir = $taskReport
New-Item -ItemType Directory -Force -Path $taskReport | Out-Null
$taskLog = & $taskBuildScript @taskArgs -Task 'test'
Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport 'unit-test.log') -Force
$taskJunit = Join-Path $taskReport 'junit'
New-Item -ItemType Directory -Force -Path $taskJunit | Out-Null
Get-ChildItem -LiteralPath (Join-Path $CacheRoot 'build/test-results/test') -Filter 'TEST-*.xml' | Copy-Item -Destination $taskJunit -Force
$taskServerRun = Join-Path $taskRunRoot 'server'
if ($Compat) {
    $taskScripts = Join-Path $taskServerRun 'kubejs/server_scripts'
    New-Item -ItemType Directory -Force -Path $taskScripts | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'tools/fixtures/prefab-smoke.js') -Destination $taskScripts
}
$taskLog = & $taskBuildScript @taskArgs -Task 'runGameTestServer' -TestRun $taskServerRun -CompatFixtures:$Compat
Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport $(if ($Compat) { 'gametest-compat.log' } else { 'gametest-base.log' })) -Force
if (!(Select-String -LiteralPath $taskLog -Pattern 'All \d+ required tests passed' -Quiet)) { throw 'GameTest did not report success.' }
if ($Crash) {
    foreach ($taskStage in @('BLOCKS', 'TICKS', 'COMMIT')) {
        $taskCrashRun = Join-Path $taskRunRoot "crash-$taskStage"
        $taskLog = & $taskBuildScript -CacheRoot $CacheRoot -NeoForgeVersion $NeoForgeVersion -ReportDir $taskReport -Task 'runGameTestServer' -TestRun $taskCrashRun -CrashMode write -CrashStage $taskStage
        Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport "crash-$taskStage-write.log") -Force
        $taskLog = & $taskBuildScript -CacheRoot $CacheRoot -NeoForgeVersion $NeoForgeVersion -ReportDir $taskReport -Task 'runGameTestServer' -TestRun $taskCrashRun -CrashMode read -CrashStage $taskStage
        Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport "crash-$taskStage-read.log") -Force
    }
}
if ($Client) {
    $taskClientRoot = Join-Path $taskRunRoot 'client'
    $taskWorld = Join-Path $taskClientRoot 'saves/prefab-smoke'
    New-Item -ItemType Directory -Force -Path $taskWorld | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskServerRun 'world/level.dat') -Destination $taskWorld -Force
    @('lang:zh_cn', 'guiScale:2', 'renderDistance:8', 'simulationDistance:5', 'maxFps:200', 'enableVsync:false', 'pauseOnLostFocus:false') | Set-Content -LiteralPath (Join-Path $taskClientRoot 'options.txt') -Encoding utf8
    $taskLog = & $taskBuildScript -CacheRoot $CacheRoot -NeoForgeVersion $NeoForgeVersion -ReportDir $taskReport -Task 'runClient' -TestRun $taskClientRoot -ClientSmoke
    Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport 'client-smoke.log') -Force
    if (!(Select-String -LiteralPath $taskLog -SimpleMatch 'CLIENT SMOKE COMPLETE' -Quiet)) { throw 'Client smoke did not complete.' }
}
Write-Host "Verified. Reports: $taskReport; scratch worlds: $taskRunRoot"
