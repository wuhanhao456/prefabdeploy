param(
    [string[]]$Task = @('build'),
    [string]$CacheRoot = (Join-Path $env:LOCALAPPDATA 'PrefabDeploy'),
    [string]$TestRun,
    [string]$NeoForgeVersion,
    [string]$ReportDir,
    [switch]$Compat,
    [switch]$CompatFixtures,
    [string]$VssJar,
    [ValidateSet('', 'write', 'read')][string]$CrashMode = '',
    [ValidateSet('BLOCKS', 'TICKS', 'COMMIT')][string]$CrashStage = 'TICKS',
    [switch]$ClientSmoke,
    [switch]$BindingTooltipSmoke,
    [switch]$ConnectionSmoke,
    [string]$ConnectionServers,
    [switch]$ModelReloadSmoke,
    [string]$ShaderModsDir,
    [string]$ShaderSmoke,
    [switch]$ReloadSmoke,
    [string]$ResourceModsDir,
    [switch]$ResourceSmoke,
    [ValidateSet('', 'write', 'read')][string]$ResourceCrashMode = '',
    [string]$ResourceCrashStage,
    [ValidateSet('', 'write', 'read')][string]$BoundCrashMode = '',
    [string]$BoundCrashStage,
    [ValidateSet('container', 'ae2')][string]$BoundCrashKind = 'container'
)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$CacheRoot = [IO.Path]::GetFullPath($CacheRoot)
if ($CacheRoot.StartsWith('\\')) { throw 'CacheRoot must be on a local drive for the NeoForge compiler.' }
New-Item -ItemType Directory -Force -Path $CacheRoot, (Join-Path $CacheRoot 'tmp'), (Join-Path $CacheRoot 'logs') | Out-Null
$taskTemp = (Join-Path $CacheRoot 'tmp').Replace('\', '/')
$taskOriginalOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = (($taskOriginalOptions + ' -Djdk.net.unixdomain.tmpdir=' + $taskTemp).Trim())
$taskBuild = (Join-Path $CacheRoot 'build').Replace('\', '/')
$taskArguments = @('-classpath', (Join-Path $taskRoot 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', "-PprefabBuildDir=$taskBuild", '--project-cache-dir', (Join-Path $CacheRoot 'gradle'))
if ($TestRun) { $taskArguments += "-PprefabTestRun=$(([IO.Path]::GetFullPath($TestRun)).Replace('\','/'))" }
if ($NeoForgeVersion) { $taskArguments += "-Pneo_version=$NeoForgeVersion" }
if ($ReportDir) {
    $taskReportPath = if ([IO.Path]::IsPathFullyQualified($ReportDir)) { $ReportDir } else { Join-Path $taskRoot $ReportDir }
    $taskArguments += "-PprefabReportDir=$(([IO.Path]::GetFullPath($taskReportPath)).Replace('\','/'))"
}
if ($Compat) { $taskArguments += '-PcompatTest' }
if ($CompatFixtures) { $taskArguments += '-PcompatFixtures' }
if ($VssJar) { $taskArguments += "-PvssJar=$((Get-Item -LiteralPath $VssJar).FullName.Replace('\','/'))" }
if ($CrashMode) { $taskArguments += "-PcrashMode=$CrashMode", "-PcrashStage=$CrashStage" }
if ($ClientSmoke) { $taskArguments += '-PclientSmoke' }
if ($BindingTooltipSmoke) { $taskArguments += '-PclientSmoke', '-PbindingTooltipSmoke' }
if ($ConnectionSmoke) {
    if (!$ConnectionServers -or !$TestRun) { throw 'ConnectionSmoke requires ConnectionServers and TestRun.' }
    $taskArguments += '-PconnectionSmoke', "-PprefabConnectionServers=$ConnectionServers"
}
if ($ModelReloadSmoke) { $taskArguments += '-PmodelReloadSmoke' }
if ($ShaderModsDir) { $taskArguments += "-PshaderModsDir=$(([IO.Path]::GetFullPath($ShaderModsDir)).Replace('\','/'))" }
if ($ShaderSmoke) { $taskArguments += "-PshaderSmoke=$ShaderSmoke" }
if ($ReloadSmoke) { $taskArguments += '-PreloadSmoke' }
if ($ResourceModsDir) { $taskArguments += "-PresourceModsDir=$(([IO.Path]::GetFullPath($ResourceModsDir)).Replace('\','/'))" }
if ($ResourceSmoke) { $taskArguments += '-PresourceSmoke' }
if ($ResourceCrashMode) {
    if (!$ResourceModsDir -or !$ResourceCrashStage) { throw 'ResourceCrashMode requires ResourceModsDir and ResourceCrashStage.' }
    $taskArguments += "-PresourceCrashMode=$ResourceCrashMode", "-PresourceCrashStage=$ResourceCrashStage"
}
if ($BoundCrashMode) {
    if (!$BoundCrashStage) { throw 'BoundCrashMode requires BoundCrashStage.' }
    $taskArguments += "-PboundCrashMode=$BoundCrashMode", "-PboundCrashStage=$BoundCrashStage", "-PboundCrashKind=$BoundCrashKind"
}
$taskArguments += $Task
$taskLog = Join-Path $CacheRoot ('logs/gradle-' + (Get-Date -Format 'yyyyMMdd-HHmmss-ffff') + '.log')
Write-Host "Gradle $($Task -join ', ') -> $taskLog"
Push-Location -LiteralPath $taskRoot
try {
    & java @taskArguments *> $taskLog
    $taskCode = $LASTEXITCODE
    $taskExpectedCrash = ($CrashMode -eq 'write' -or $ResourceCrashMode -eq 'write' -or $BoundCrashMode -eq 'write') -and (Select-String -LiteralPath $taskLog -SimpleMatch 'PREFAB HARD CRASH READY:' -Quiet) -and (Select-String -LiteralPath $taskLog -SimpleMatch 'exit value 91' -Quiet)
    if ($taskCode -ne 0 -and !$taskExpectedCrash) {
        Get-Content -LiteralPath $taskLog -Tail 40
        throw "Gradle failed ($taskCode). See $taskLog"
    }
    if (($ResourceSmoke -or $ResourceCrashMode -eq 'read' -or $BoundCrashMode -eq 'read') -and
        !(Select-String -LiteralPath $taskLog -Pattern 'All \d+ required tests passed' -Quiet)) {
        throw "Resource GameTests did not report success. See $taskLog"
    }
    if ($Task -contains 'build' -or $Task -contains 'jar') {
        $taskDist = Join-Path $taskRoot 'dist'
        New-Item -ItemType Directory -Force -Path $taskDist | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $CacheRoot 'build/libs') -Filter '*.jar' | Copy-Item -Destination $taskDist -Force
        Write-Host "JARs copied to $taskDist"
    }
    Write-Host $(if ($taskExpectedCrash) { 'Expected test JVM halt verified.' } else { 'Gradle completed.' })
    return $taskLog
} finally {
    Pop-Location
    $env:JAVA_TOOL_OPTIONS = $taskOriginalOptions
}
