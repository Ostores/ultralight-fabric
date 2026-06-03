#!/usr/bin/env bash
# Installe l'API Luminescence (libs/luminescence-2026.1.0.jar) dans le Maven local,
# requis pour le jar-in-jar (`include`) du build. À lancer une fois avant ./gradlew build.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
jar="$root/libs/luminescence-2026.1.0.jar"
[ -f "$jar" ] || { echo "Introuvable : $jar (place l'API Luminescence dans libs/)" >&2; exit 1; }
m2="$HOME/.m2/repository/io/github/ayydxn/luminescence/2026.1.0"
mkdir -p "$m2"
cp "$jar" "$m2/luminescence-2026.1.0.jar"
cat > "$m2/luminescence-2026.1.0.pom" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.ayydxn</groupId>
  <artifactId>luminescence</artifactId>
  <version>2026.1.0</version>
  <packaging>jar</packaging>
</project>
POM
echo "OK: io.github.ayydxn:luminescence:2026.1.0 installé dans mavenLocal."
