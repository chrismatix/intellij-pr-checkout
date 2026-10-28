#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

ide_home=${IDE_HOME:-$(ls -d ~/.cache/JetBrains/RemoteDev/dist/*_idea-* | tail -n 1)}
version=$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' src/main/resources/META-INF/plugin.xml)

rm -rf build
mkdir -p build/classes
"$ide_home/jbr/bin/javac" --release 25 -nowarn -cp "$ide_home/lib/*" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
jar --create --file build/pr-checkout.jar -C build/classes .

cat > updatePlugins.xml <<EOF
<plugins>
    <plugin id="christoph.pr-checkout" url="https://github.com/chrismatix/intellij-pr-checkout/releases/download/v$version/pr-checkout.jar" version="$version">
        <idea-version since-build="262"/>
    </plugin>
</plugins>
EOF
echo "Built $version"
