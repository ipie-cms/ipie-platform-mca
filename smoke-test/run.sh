#!/usr/bin/env bash
# Verifies the published ipie-build-conventions enforces the platform's quality rules in a project
# that has no ipie-quality-config folder of its own. See README.md for why both assertions matter.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
VERSION="$(grep -E '^version=' "$ROOT/gradle.properties" | cut -d= -f2)"
echo "==> platform version: $VERSION"

echo "==> publishing ipie-build-conventions, ipie-parent and ipie-common-libs to Maven Local"
"$ROOT/gradlew" -p "$ROOT/ipie-build-conventions" publishToMavenLocal -q --no-daemon
"$ROOT/gradlew" -p "$ROOT" :ipie-parent:publishToMavenLocal :ipie-common-libs:publishToMavenLocal -q --no-daemon

rm -rf build
cp -f "$ROOT/gradlew" . 2>/dev/null || true
[ -d gradle ] || cp -r "$ROOT/gradle" .

echo "==> 1/3 ipie.java-conventions: clean code must PASS, config extracted from the plugin jar"
./gradlew -PipiePlatformVersion="$VERSION" :library:checkstyleMain :library:spotbugsMain --no-daemon -q
for f in checkstyle/checkstyle.xml spotbugs/spotbugs-exclude.xml; do
    [ -f "library/build/ipie-quality-config/$f" ] || { echo "FAIL: $f was not extracted from the jar"; exit 1; }
done
echo "    ok - extracted: $(find library/build/ipie-quality-config -type f | wc -l) config files"

echo "==> 2/3 ipie.spring-service-conventions: the plugin a real service applies must resolve"
# This is the one that matters. The plugin carries the platform BOM and the shared test-fixtures
# wiring internally; if those revert to unconditional project(':...') references it fails here with
# "Project with path ':ipie-parent' could not be found" - exactly how the coupling was found while
# extracting ipie-communication-service, which library/ above could never have caught.
./gradlew -PipiePlatformVersion="$VERSION" :service:compileJava :service:checkstyleMain --no-daemon -q
echo "    ok - BOM, shared libraries and quality gates all resolved from published artifacts"

echo "==> 3/3 a deliberate violation must FAIL (proves rules are enforced, not silently absent)"
cat > library/src/main/java/in/gov/ipie/smoke/Violator.java <<'JAVA'
package in.gov.ipie.smoke;

/** Deliberately violates the platform 140-character line limit. Written and deleted by run.sh. */
public final class Violator {

    private Violator() {
    }

    /** @return a long string */
    public static String tooLong() {
        return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    }
}
JAVA
if ./gradlew -PipiePlatformVersion="$VERSION" :library:checkstyleMain --no-daemon -q 2>/dev/null; then
    rm -f library/src/main/java/in/gov/ipie/smoke/Violator.java
    echo "FAIL: the 140-character rule was NOT enforced - the packaged config is not being applied"
    exit 1
fi
rm -f library/src/main/java/in/gov/ipie/smoke/Violator.java
echo "    ok - violation rejected"

echo "==> smoke test passed"
