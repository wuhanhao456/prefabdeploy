param([string]$CacheRoot = 'C:/pd14', [string]$ReportDir = 'docs/test-results/release-0.1.4/library')
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path $PSScriptRoot -Parent
$taskCache = [IO.Path]::GetFullPath($CacheRoot)
if ($taskCache.StartsWith('\\')) { throw 'Test cache must use a local drive.' }
$taskReport = if ([IO.Path]::IsPathFullyQualified($ReportDir)) { $ReportDir } else { Join-Path $taskRepo $ReportDir }
New-Item -ItemType Directory -Force -Path $taskReport | Out-Null
foreach ($taskEnabled in @($true, $false)) {
    $taskProfile = if ($taskEnabled) { 'enabled' } else { 'disabled' }
    $taskRun = Join-Path $taskCache ('library-' + $taskProfile + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    if (!$taskEnabled) {
        $taskConfig = Join-Path $taskRun 'world/serverconfig'
        New-Item -ItemType Directory -Force -Path $taskConfig | Out-Null
        Set-Content -LiteralPath (Join-Path $taskConfig 'prefabdeploy-server.toml') -Encoding utf8 -Value "[buildings]`nenableDefaultTestBuildings = false"
    }
    $taskLog = & (Join-Path $taskRepo 'Build.ps1') -CacheRoot $taskCache -Task runGameTestServer -TestRun $taskRun -ReportDir $taskReport -BuildingLibrarySmoke -DefaultBuildingsOffSmoke:(!$taskEnabled)
    Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport ($taskProfile + '.log')) -Force
    if (!(Select-String -LiteralPath $taskLog -SimpleMatch 'PREFAB BUILDING LIBRARY ZIP DIRECTORY UPDATE DELETE DISABLE OVERRIDE ACCESS SNAPSHOT VERIFIED' -Quiet) -or
        !(Select-String -LiteralPath $taskLog -SimpleMatch 'All 1 required tests passed' -Quiet)) { throw "Building library regression failed: $taskLog" }
}
Write-Output "Building library cold-start profiles passed. Evidence: $taskReport"
