param(
    [string]$CacheRoot = 'C:/pdr',
    [Parameter(Mandatory)][string]$ResourceModsDir,
    [string]$ReportDir = 'docs/test-results/resources-compat',
    [switch]$Crash,
    [string[]]$CrashStages
)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskBuildScript = Join-Path $taskRoot 'Build.ps1'
$taskReports = if ([IO.Path]::IsPathFullyQualified($ReportDir)) { $ReportDir } else { Join-Path $taskRoot $ReportDir }
$taskReports = [IO.Path]::GetFullPath($taskReports)
$taskRuns = Join-Path $CacheRoot ('resource-tests/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $taskReports, $taskRuns | Out-Null
# Use isolated original JAR directories; never reuse an instance with extra optional mods left behind.
$taskFiles = Get-ChildItem -LiteralPath $ResourceModsDir -Filter '*.jar'
$taskInventory = @{}
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($taskFile in $taskFiles) {
    $taskZip = [IO.Compression.ZipFile]::OpenRead($taskFile.FullName)
    try {
        $taskMetadata = $taskZip.GetEntry('META-INF/neoforge.mods.toml')
        if (!$taskMetadata) { continue }
        $taskReader = [IO.StreamReader]::new($taskMetadata.Open())
        try { $taskText = $taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
        foreach ($taskBlock in [regex]::Matches($taskText, '(?ms)^\[\[mods\]\][^\r\n]*\r?\n(.*?)(?=^\[|\z)')) {
            foreach ($taskId in @('sophisticatedbackpacks', 'sophisticatedcore', 'curios', 'beyonddimensions')) {
                if ($taskBlock.Groups[1].Value -match ('modId\s*=\s*"' + $taskId + '"')) {
                    if ($taskInventory.ContainsKey($taskId)) { throw "Duplicate $taskId JARs in $ResourceModsDir" }
                    $taskInventory[$taskId] = $taskFile.FullName
                }
            }
        }
    } finally { $taskZip.Dispose() }
}
foreach ($taskId in @('sophisticatedbackpacks', 'sophisticatedcore', 'curios', 'beyonddimensions')) {
    if (!$taskInventory.ContainsKey($taskId)) { throw "Missing original $taskId JAR in $ResourceModsDir" }
}
$taskSets = [ordered]@{
    none = @()
    backpacks_no_curios = @('sophisticatedbackpacks', 'sophisticatedcore')
    backpacks = @('sophisticatedbackpacks', 'sophisticatedcore', 'curios')
    network = @('beyonddimensions')
    full = @('sophisticatedbackpacks', 'sophisticatedcore', 'curios', 'beyonddimensions')
}
foreach ($taskSet in $taskSets.GetEnumerator()) {
    $taskModPath = Join-Path $taskRuns ('mods-' + $taskSet.Key)
    New-Item -ItemType Directory -Force -Path $taskModPath | Out-Null
    foreach ($taskId in $taskSet.Value) { Copy-Item -LiteralPath $taskInventory[$taskId] -Destination $taskModPath }
    $taskLog = & $taskBuildScript -CacheRoot $CacheRoot -Task runGameTestServer -ResourceSmoke -ResourceModsDir $taskModPath -TestRun (Join-Path $taskRuns $taskSet.Key)
    Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReports ($taskSet.Key + '.log')) -Force
    Write-Host "Resource matrix passed: $($taskSet.Key)"
}
if ($Crash) {
    if (!$CrashStages) {
        $CrashStages = @('player_saved', 'resources_before_confirmation', 'resources_ready', 'player_commit_saved', 'player_refund_saved')
        foreach ($taskKind in @('backpack', 'linked_backpack', 'network')) {
            foreach ($taskPhase in @('source_before_save', 'source_saved', 'source_commit_saved', 'source_refund_before_save', 'source_refund_saved')) {
                $CrashStages += "${taskKind}:$taskPhase"
            }
        }
    }
    foreach ($taskStage in $CrashStages) {
        $taskName = $taskStage.Replace(':', '-')
        $taskRun = Join-Path $taskRuns ('crash-' + $taskName)
        foreach ($taskMode in @('write', 'read')) {
            $taskLog = & $taskBuildScript -CacheRoot $CacheRoot -Task runGameTestServer -ResourceCrashMode $taskMode -ResourceCrashStage $taskStage -ResourceModsDir (Join-Path $taskRuns 'mods-full') -TestRun $taskRun
            Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReports ("crash-$taskName-$taskMode.log")) -Force
        }
        Write-Host "Cold resource recovery passed: $taskStage"
    }
}
$taskHashes = foreach ($taskId in $taskInventory.Keys | Sort-Object) {
    "$taskId $((Get-FileHash -LiteralPath $taskInventory[$taskId] -Algorithm SHA256).Hash)"
}
$taskHashes | Set-Content -LiteralPath (Join-Path $taskReports 'mod-sha256.txt') -Encoding utf8
Write-Host "Verified resource compatibility. Reports: $taskReports; worlds: $taskRuns"
