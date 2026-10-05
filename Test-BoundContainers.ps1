param(
    [string]$CacheRoot = 'C:/pdbc',
    [ValidateSet('container', 'ae2')][string]$Kind = 'container',
    [string]$ResourceModsDir,
    [string]$ReportDir = 'docs/test-results/container-binding/crash'
)
$ErrorActionPreference = 'Stop'
if ($Kind -eq 'ae2' -and !$ResourceModsDir) { throw 'AE2 crash tests require ResourceModsDir.' }
$bindingReport = if ([IO.Path]::IsPathFullyQualified($ReportDir)) { $ReportDir } else { Join-Path $PSScriptRoot $ReportDir }
New-Item -ItemType Directory -Force -Path $bindingReport | Out-Null
$bindingStages = if ($Kind -eq 'container') {
    @('source_before_save', 'source_saved', 'source_refund_before_save', 'source_refund_saved', 'source_commit_saved')
} else {
    @('source_before_save', 'source_saved', 'source_commit_saved')
}
foreach ($bindingStage in $bindingStages) {
    $bindingRun = Join-Path $CacheRoot "binding-crash/$Kind-$bindingStage"
    foreach ($bindingMode in @('write', 'read')) {
        $bindingArgs = @{
            CacheRoot = $CacheRoot; Task = 'runGameTestServer'; TestRun = $bindingRun
            ReportDir = $bindingReport; BoundCrashMode = $bindingMode
            BoundCrashKind = $Kind; BoundCrashStage = "$($Kind):$bindingStage"
        }
        if ($ResourceModsDir) { $bindingArgs.ResourceModsDir = $ResourceModsDir }
        $bindingLog = & (Join-Path $PSScriptRoot 'Build.ps1') @bindingArgs
        Copy-Item -LiteralPath $bindingLog -Destination (Join-Path $bindingReport "$Kind-$bindingStage-$bindingMode.log") -Force
    }
}
Write-Host "Cold $Kind binding tests passed. Reports: $bindingReport"
