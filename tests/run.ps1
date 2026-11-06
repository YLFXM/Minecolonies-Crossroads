$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot 'build\classes'
if (-not (Test-Path -LiteralPath $classes)) { throw 'Run build.ps1 first.' }
$classpath = (Get-Content -LiteralPath (Join-Path $projectRoot 'cp.txt') -Raw).Trim()
$javaExe = $null
foreach ($candidate in @((Join-Path $projectRoot '..\tools\jre'), (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'), $env:JAVA_HOME)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $probe = Join-Path $candidate 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $probe)) { continue }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $probe -version 2>&1 | Out-Null; if ($LASTEXITCODE -eq 0) { $javaExe = $probe } }
    finally { $ErrorActionPreference = $previous }
    if ($javaExe) { break }
}
if (-not $javaExe) { throw 'No working Java runtime found.' }
$testClasses = Join-Path $projectRoot 'build\test-classes'
$null = New-Item -ItemType Directory -Force -Path $testClasses
$sources = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | Select-Object -ExpandProperty FullName
& $javaExe -m jdk.compiler/com.sun.tools.javac.Main -proc:none -encoding UTF-8 -cp "$classes;$classpath" -d $testClasses $sources
if ($LASTEXITCODE -ne 0) { throw 'Regression compilation failed.' }
# Bundled assets (unit manifest and blueprints) live in the source tree, like the game jar
# does in a real install: RoadUnitLibrary reads units.json through the class loader.
$resources = Join-Path $projectRoot 'src\main\resources'
# Native AIR tests bootstrap vanilla registries and need the client's language/version resources.
# Keep the mapped/dependency classpath first; the vanilla JAR supplies only missing resources.
$testClientJar = $env:MCRECONSTRUCTION_TEST_CLIENT_JAR
if ([string]::IsNullOrWhiteSpace($testClientJar)) {
    $testClientJar = Join-Path $projectRoot '..\..\.minecraft\versions\模拟殖民地1.21\模拟殖民地1.21.jar'
}
if (-not (Test-Path -LiteralPath $testClientJar)) {
    throw 'Set MCRECONSTRUCTION_TEST_CLIENT_JAR to the vanilla Minecraft 1.21.1 client JAR for native blueprint regression resources.'
}
foreach ($testClass in @('RoadPlanningRegression', 'RoadJointRegression', 'RoadStage7Round1Regression', 'com.YLFXM.rp.crossroads.road.RoadStage7Round2Regression', 'com.YLFXM.rp.crossroads.road.RoadStage7Round3Regression', 'com.YLFXM.rp.crossroads.road.RoadStage7Round4Regression', 'com.YLFXM.rp.crossroads.road.RoadStage7Round5Regression', 'com.YLFXM.rp.crossroads.road.RoadStage7Round6Regression', 'com.YLFXM.rp.crossroads.road.RoadJunctionTableRegression', 'RoadAuditRegression', 'PreviewLifecycleRegression', 'RoadRepairSessionRegression', 'RoadRemovalSessionRegression', 'Round1Regression', 'InitialConstructionRegression', 'RoadHeightRegression', 'RoadCommonRegression', 'RoadRowStateRegression', 'RoadNoticesRegression', 'RoadPackIdentityRegression', 'UiLocalizationRegression', 'NetworkChannelsRegression', 'CrossingPlanningAuthRegression', 'RoadUnitDataRegression', 'com.YLFXM.rp.crossroads.road.RoadStage6FixRegression')) {
    & $javaExe -cp "$classes;$testClasses;$classpath;$resources;$testClientJar" $testClass
    if ($LASTEXITCODE -ne 0) { throw "Regression failed: $testClass" }
}
& python (Join-Path $projectRoot 'tools\export_road_units.py') --verify
if ($LASTEXITCODE -ne 0) { throw 'Bundled unit verification failed.' }
