# Installe l'API Luminescence (libs/luminescence-2026.1.0.jar) dans le Maven local,
# requis pour le jar-in-jar (`include`) du build. À lancer une fois avant ./gradlew build.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar  = Join-Path $root "libs\luminescence-2026.1.0.jar"
if (-not (Test-Path $jar)) { throw "Introuvable : $jar (place l'API Luminescence dans libs/)" }
$m2 = Join-Path $env:USERPROFILE ".m2\repository\io\github\ayydxn\luminescence\2026.1.0"
New-Item -ItemType Directory -Force -Path $m2 | Out-Null
Copy-Item $jar (Join-Path $m2 "luminescence-2026.1.0.jar") -Force
@"
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.ayydxn</groupId>
  <artifactId>luminescence</artifactId>
  <version>2026.1.0</version>
  <packaging>jar</packaging>
</project>
"@ | Set-Content -Path (Join-Path $m2 "luminescence-2026.1.0.pom") -Encoding UTF8
Write-Host "OK: io.github.ayydxn:luminescence:2026.1.0 installé dans mavenLocal."
