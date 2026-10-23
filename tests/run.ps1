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
foreach ($testClass in @('RoadAuditRegression', 'PreviewLifecycleRegression')) {
    & $javaExe -cp "$classes;$testClasses;$classpath" $testClass
    if ($LASTEXITCODE -ne 0) { throw "Regression failed: $testClass" }
}
