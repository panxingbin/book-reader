<#
  Run the real JetBrains Plugin Verifier locally, against a plugin zip and an IDE.

  Why this exists
  ---------------
  The Marketplace runs a server-side compatibility verifier that local `buildPlugin`
  cannot reproduce. Until now every finding (internal API / override-only /
  non-extendable / deprecated) could only be observed by uploading and reading the
  rejection message, one round-trip per fix. This script closes that loop.

  Usage
  -----
    powershell -File tools/verifier/run-verifier.ps1
    powershell -File tools/verifier/run-verifier.ps1 -PluginZip build\distributions\book-reader-3.5.4.zip
    powershell -File tools/verifier/run-verifier.ps1 -IdeDir C:\ideaIC-2024.1

  It prints the verifier's own verdict and leaves the HTML report on disk.

  The minimum supported IDE is not enough
  ---------------------------------------
  `-IdeDir` defaults to the IDE this build targets (2023.2), which is the *minimum* the
  plugin claims to support. Passing it alone misses a whole class of rejections, because
  deprecations land in *newer* platforms while the plugin still runs on the old one:
  3.5.3 passed here against 2023.2 and was still rejected by the Marketplace for three
  `Deprecated methods usages` / `Deprecated constructor usage`.

  So also run it against a current IDE — either a downloaded release:

      powershell -File tools\verifier\run-verifier.ps1 -IdeDir "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2"

  or a Gradle-resolved one (`-IdeDir` just needs the extracted root; the plugin must be
  self-contained, since only the platform's own modules are loaded). A 2026.2 check takes
  ~10 min and pulls ~1 GB of bundled plugins on the first run; the deprecation findings are
  what matter. Everything else (missing bundled-plugin modules, the `jb.gg` API-changes
  page being unreachable behind the corporate proxy) is noise — the verdict line is what
  to trust.

  Step-by-step, including the exact java invocation and the 3.5.3 vs 3.5.4 results:
  [verify-against-latest.md](verify-against-latest.md).
#>
[CmdletBinding()]
param(
    # Defaults to the newest zip under build\distributions.
    [string]$PluginZip = '',
    # Defaults to the IntelliJ Platform bundled with this build (from the Gradle cache).
    [string]$IdeDir = '',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17.0.6',
    [switch]$KeepHome
)

$ErrorActionPreference = 'Stop'
# $PSScriptRoot is <root>\tools\verifier, so the project root is two levels up.
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# --- resolve inputs -------------------------------------------------------

if (-not $PluginZip) {
    $zipInfo = Get-ChildItem (Join-Path $Root 'build\distributions') -Filter '*.zip' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $zipInfo) { throw "No plugin zip under build\distributions. Build first, or pass -PluginZip." }
    $PluginZip = $zipInfo.FullName
} else {
    $resolved = Resolve-Path (Join-Path $Root $PluginZip) -ErrorAction SilentlyContinue
    if (-not $resolved) { throw "Plugin zip not found: $PluginZip" }
    $PluginZip = $resolved.Path
}

if (-not $IdeDir) {
    $ideCache = Join-Path $Root '.gradle-home\caches\modules-2\files-2.1\com.jetbrains.intellij.idea'
    $ideInfo = Get-ChildItem $ideCache -Directory -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'ideaIC-*' -and (Test-Path (Join-Path $_.FullName 'lib')) } |
        Select-Object -Last 1
    if (-not $ideInfo) { throw "No IDE found in the Gradle cache. Pass -IdeDir." }
    $IdeDir = $ideInfo.FullName
}

# The verifier CLI is fetched by the Gradle plugin's runPluginVerifier task.
$verifier = Get-ChildItem (Join-Path $Root '.gradle-home\caches\modules-2\files-2.1\org.jetbrains.intellij.plugins\verifier-cli') `
    -Recurse -Filter 'verifier-cli-*-all.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
if (-not $verifier) {
    throw "verifier-cli not found. Run this once first:`ngradle runPluginVerifier`   # the IDE download may fail; only the CLI is needed"
}

$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $java)) { throw "java.exe not found under $JavaHome" }

# --- run ------------------------------------------------------------------
# The verifier writes to ~/.pluginVerifier, which is outside the workspace and
# therefore denied by the sandbox. Point user.home inside the workspace.
$vh = Join-Path $Root '.verify-home'
New-Item -ItemType Directory -Path $vh -Force | Out-Null

Write-Host "plugin : $PluginZip"
Write-Host "ide    : $IdeDir"
Write-Host "verifier: $($verifier.FullName)"
Write-Host ''
Write-Host '==== verifier output ===='

& $java "-Duser.home=$vh" -jar $verifier.FullName check-plugin $PluginZip $IdeDir 2>&1 |
    Where-Object { $_ -notmatch '^\s+at ' } | Select-Object -Last 45

# --- verdict --------------------------------------------------------------
# The verifier writes its report directory into the current working directory
# (named verification-<date>), not into user.home.
$report = Get-ChildItem $Root -Directory -Filter 'verification-*' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$verdict = ''
if ($report) {
    $verdictFile = Get-ChildItem $report.FullName -Recurse -Filter 'verification-verdict.txt' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($verdictFile) { $verdict = (Get-Content $verdictFile.FullName -Raw).Trim() }
}

Write-Host ''
Write-Host '==== verdict ===='
if ($verdict -eq 'Compatible') {
    Write-Host "RESULT: PASS  ($verdict)"
} else {
    Write-Host "RESULT: FAIL  ($verdict)"
}
if ($report) { Write-Host "report : $($report.FullName)" }
