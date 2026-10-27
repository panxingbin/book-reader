<#
.SYNOPSIS
  Loads the built plugin with the IntelliJ Platform's own descriptor loader.

.DESCRIPTION
  A static XML check cannot tell you whether the IDE will accept a plugin. This
  script compiles tools/descriptor-check/DescriptorCheck.java against the very
  IntelliJ Platform the plugin targets and runs it, so extension points,
  `implementation` classes, keyboard shortcuts, icons and `<depends>` are all
  validated by the same code the IDE runs at startup.

  Requires the platform to be present in the Gradle cache (it is, after any
  `gradle compileKotlin`) and JDK 17.

.EXAMPLE
  pwsh -File tools/descriptor-check/check.ps1
#>
[CmdletBinding()]
param(
    [string] $PluginZip,
    [string] $IntellijVersion = '2023.2',
    [string] $BuildNumber = '232',
    [string] $JavaHome = 'C:\Program Files\Java\jdk-17.0.6'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $PluginZip) {
    $zip = Get-ChildItem (Join-Path $root 'build\distributions') -Filter '*.zip' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $zip) { throw "No plugin zip found in build\distributions - run 'gradle buildPlugin' first." }
    $PluginZip = $zip.FullName
}

$gradleHome = Join-Path $root '.gradle-home'
$ideaRoot = Get-ChildItem (Join-Path $gradleHome 'caches\modules-2\files-2.1\com.jetbrains.intellij.idea\ideaIC') `
    -Recurse -Directory -Filter "ideaIC-$IntellijVersion" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $ideaRoot) {
    $ideaRoot = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.jetbrains.intellij.idea\ideaIC" `
        -Recurse -Directory -Filter "ideaIC-$IntellijVersion" -ErrorAction SilentlyContinue |
        Select-Object -First 1
}
if (-not $ideaRoot) { throw "IntelliJ Platform $IntellijVersion not found in the Gradle cache." }

$lib = Join-Path $ideaRoot.FullName 'lib'
$classpath = (Get-ChildItem $lib -Filter *.jar -Recurse | ForEach-Object { $_.FullName }) -join ';'

$out = Join-Path $root '.verify-descriptor'
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host "platform   : $($ideaRoot.FullName)"
Write-Host "plugin zip : $PluginZip"
Write-Host "compiling DescriptorCheck.java ..."
# -encoding UTF-8: javac would otherwise use the platform default (Big5/CP950 on
# this machine) and reject the em-dashes in the source comments.
& "$JavaHome\bin\javac.exe" -encoding UTF-8 -nowarn -cp $classpath -d $out (Join-Path $PSScriptRoot 'DescriptorCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host "loading descriptor ...`n"
& "$JavaHome\bin\java.exe" `
    "-Didea.home.path=$($ideaRoot.FullName)" `
    "-Dstdout.encoding=UTF-8" `
    "-Dbookreader.buildNumber=$BuildNumber" `
    -cp "$out;$classpath" DescriptorCheck $PluginZip
exit $LASTEXITCODE
