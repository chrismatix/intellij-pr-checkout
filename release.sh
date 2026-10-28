#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./build.sh
version=$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' src/main/resources/META-INF/plugin.xml)
git add updatePlugins.xml
git diff --cached --quiet || git commit -m "Release $version"
git push origin main
gh release create "v$version" build/pr-checkout.jar --title "$version" --notes ""
