param([string]$QaRoot = 'C:/pdb/mob-towers-qa')
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path $PSScriptRoot -Parent
$taskQa = [IO.Path]::GetFullPath($QaRoot)
if ($taskQa.StartsWith('\\')) { throw 'QA project must use a local drive.' }
$taskProject = Join-Path $taskQa 'project'
$taskRun = Join-Path $taskQa ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$taskReport = Join-Path $taskRepo 'artifacts/mob_towers/test-results'
New-Item -ItemType Directory -Force -Path $taskProject,$taskRun,$taskReport | Out-Null
if (Test-Path -LiteralPath (Join-Path $taskReport 'mob-towers-runtime.txt')) {
    Remove-Item -LiteralPath (Join-Path $taskReport 'mob-towers-runtime.txt')
}
foreach ($taskName in @('src','gradle')) {
    Copy-Item -LiteralPath (Join-Path $taskRepo $taskName) -Destination $taskProject -Recurse -Force
}
foreach ($taskName in @('build.gradle','settings.gradle','gradle.properties','Build.ps1')) {
    Copy-Item -LiteralPath (Join-Path $taskRepo $taskName) -Destination $taskProject -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fixtures/MobTowerGameTests.java') -Destination (Join-Path $taskProject 'src/main/java/io/github/prefabdeploy/testing/MobTowerGameTests.java') -Force
Add-Content -LiteralPath (Join-Path $taskProject 'build.gradle') -Encoding utf8 -Value @'
neoForge.runs.gameTestServer {
    systemProperty 'neoforge.enabledGameTestNamespaces', 'mobtowersqa'
}
'@
$taskPacks = Join-Path $taskRun 'world/datapacks'
New-Item -ItemType Directory -Force -Path $taskPacks | Out-Null
# Test the exact delivered ZIP through the server resource manager.
Copy-Item -LiteralPath (Join-Path $taskRepo 'artifacts/mob_towers/mob_towers-1.21.1-prefabdeploy-0.1.3.zip') -Destination $taskPacks
$taskFixtures = Join-Path $taskPacks 'mob-towers-qa'
New-Item -ItemType Directory -Force -Path (Join-Path $taskFixtures 'data/mobtowersqa/structure') | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRepo 'src/testFixtures/resources/pack.mcmeta') -Destination $taskFixtures
& python -c "import gzip,struct,pathlib; out=pathlib.Path(r'$taskFixtures/data/mobtowersqa/structure/empty.nbt'); s=lambda x:struct.pack('>H',len(x))+x.encode(); out.write_bytes(gzip.compress(b'\x0a\0\0'+b'\x03'+s('DataVersion')+struct.pack('>i',3955)+b'\x09'+s('size')+b'\x03'+struct.pack('>iiii',3,64,64,64)+b'\x09'+s('palette')+b'\x0a'+struct.pack('>i',0)+b'\x09'+s('blocks')+b'\x0a'+struct.pack('>i',0)+b'\x09'+s('entities')+b'\x0a'+struct.pack('>i',0)+b'\x00',mtime=0))"
if ($LASTEXITCODE -ne 0) { throw 'Fixture NBT creation failed.' }
$taskLog = & (Join-Path $taskProject 'Build.ps1') -CacheRoot (Join-Path $taskQa 'cache') -Task runGameTestServer -TestRun $taskRun -ReportDir $taskReport
Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport 'gametest.log') -Force
if (!(Select-String -LiteralPath $taskLog -Pattern 'All 3 required tests passed' -Quiet)) { throw "Mob tower GameTests failed. See $taskLog" }
Write-Output "Mob tower QA passed. Evidence: $taskReport; test world: $taskRun"
