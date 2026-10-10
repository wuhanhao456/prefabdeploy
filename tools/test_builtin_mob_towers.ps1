param([string]$QaRoot = 'C:/pd14/mob-towers', [string]$ReportDir = 'docs/test-results/release-0.1.4/mob-towers')
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path $PSScriptRoot -Parent
$taskQa = [IO.Path]::GetFullPath($QaRoot)
if ($taskQa.StartsWith('\\')) { throw 'QA project must use a local drive.' }
$taskSession = Join-Path $taskQa (Get-Date -Format 'yyyyMMdd-HHmmss')
$taskProject = Join-Path $taskSession 'project'
$taskRun = Join-Path $taskSession 'run'
$taskReport = if ([IO.Path]::IsPathFullyQualified($ReportDir)) { $ReportDir } else { Join-Path $taskRepo $ReportDir }
New-Item -ItemType Directory -Force -Path $taskProject,$taskRun,$taskReport | Out-Null
foreach ($taskName in @('src','gradle')) { Copy-Item -LiteralPath (Join-Path $taskRepo $taskName) -Destination $taskProject -Recurse }
foreach ($taskName in @('build.gradle','settings.gradle','gradle.properties','Build.ps1')) { Copy-Item -LiteralPath (Join-Path $taskRepo $taskName) -Destination $taskProject }
$taskTest = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'fixtures/MobTowerGameTests.java')
$taskTest = $taskTest.Replace('var f = prefab(name); boolean zombie = name.startsWith("zombie");', @'
var f = prefab(name); boolean zombie = name.startsWith("zombie");
      var resource = h.getLevel().getServer().getResourceManager().getResource(ResourceLocation.parse("mobtowers:prefabs/" + name + ".json")).orElseThrow();
      h.assertTrue(resource.sourcePackId().equals(BlueprintPacks.BUILTIN_ID), "Tower did not load from the release built-in pack");
'@).Replace('both delivered ZIP definitions valid', 'both built-in definitions valid')
Set-Content -LiteralPath (Join-Path $taskProject 'src/main/java/io/github/prefabdeploy/testing/MobTowerGameTests.java') -Encoding utf8 -Value $taskTest
Add-Content -LiteralPath (Join-Path $taskProject 'build.gradle') -Encoding utf8 -Value @'
neoForge.runs.gameTestServer {
    systemProperty 'neoforge.enabledGameTestNamespaces', 'mobtowersqa'
}
'@
$taskFixtures = Join-Path $taskRun 'world/datapacks/mob-towers-qa'
New-Item -ItemType Directory -Force -Path (Join-Path $taskFixtures 'data/mobtowersqa/structure') | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRepo 'src/testFixtures/resources/pack.mcmeta') -Destination $taskFixtures
$taskFixture = Join-Path $taskFixtures 'data/mobtowersqa/structure/empty.nbt'
& python -c 'import gzip,struct,pathlib,sys; s=lambda x:struct.pack(">H",len(x))+x.encode(); pathlib.Path(sys.argv[1]).write_bytes(gzip.compress(b"\x0a\0\0"+b"\x03"+s("DataVersion")+struct.pack(">i",3955)+b"\x09"+s("size")+b"\x03"+struct.pack(">iiii",3,64,64,64)+b"\x09"+s("palette")+b"\x0a"+struct.pack(">i",0)+b"\x09"+s("blocks")+b"\x0a"+struct.pack(">i",0)+b"\x09"+s("entities")+b"\x0a"+struct.pack(">i",0)+b"\x00",mtime=0))' $taskFixture
if ($LASTEXITCODE -ne 0) { throw 'Fixture NBT creation failed.' }
$taskLog = & (Join-Path $taskProject 'Build.ps1') -CacheRoot (Join-Path $taskQa 'cache') -Task runGameTestServer -TestRun $taskRun -ReportDir $taskReport
Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReport 'gametest.log') -Force
if (!(Select-String -LiteralPath $taskLog -SimpleMatch 'All 3 required tests passed' -Quiet)) { throw "Built-in mob tower tests failed: $taskLog" }
Write-Output "Built-in mob tower survival QA passed. Evidence: $taskReport"
