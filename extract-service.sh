#!/usr/bin/env bash
# Extracts one service module from this monorepo into a standalone repository that builds against
# the platform's PUBLISHED artifacts.
#
# Written as a script rather than done by hand because the transformation is identical for every
# service and easy to get subtly wrong: the module's build.gradle has to stop using project(':...')
# for the shared library AND for the test-fixtures variant, and the new build needs its own
# settings.gradle, gradle.properties and wrapper. Doing that from memory four times is how one
# service ends up quietly different from the others.
#
# Usage:  ./extract-service.sh ipie-user-service [destination-parent-dir]
#
# It does NOT create the GitHub repository, commit or push - that stays a deliberate human step.
set -euo pipefail

MODULE="${1:?usage: extract-service.sh <module-name> [destination-parent-dir]}"
PARENT="${2:-$(cd .. && pwd)}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$PARENT/$MODULE"

[ -d "$ROOT/$MODULE" ] || { echo "no such module: $ROOT/$MODULE"; exit 1; }
VERSION="$(grep -E '^version=' "$ROOT/gradle.properties" | cut -d= -f2)"

echo "==> extracting $MODULE against platform $VERSION -> $DEST"
rm -rf "$DEST"; mkdir -p "$DEST"

# Only the module's own tracked files; flattened so the module directory becomes the repo root.
git -C "$ROOT" ls-files "$MODULE" | sed "s|^$MODULE/||" > /tmp/extract-files.txt
tar -cf - -C "$ROOT/$MODULE" -T /tmp/extract-files.txt | tar -xf - -C "$DEST"

cp -r "$ROOT/gradle" "$DEST/"
cp "$ROOT/gradlew" "$ROOT/gradlew.bat" "$ROOT/.gitignore" "$DEST/"
# lombok.config is NOT optional. It sits at the monorepo root, so a module inherited it silently;
# an extracted repository does not. It carries lombok.copyableAnnotations += ...Value, without which
# Lombok drops the @Value("${...}") annotations from @RequiredArgsConstructor-generated constructor
# parameters and Spring fails to start with NoSuchBeanDefinitionException - which is exactly how
# ipie-user-service's extraction failed. The file's own comment predicts the failure.
cp "$ROOT/lombok.config" "$DEST/"
# The platform standards travel with every service so a developer can check their work locally.
cp "$ROOT/MASTER_CODE_STANDARDS.md" "$ROOT/MASTER_CODE_STANDARDS.docx" "$DEST/" 2>/dev/null || true

cat >> "$DEST/.gitignore" <<'IGNORE'

# The platform standards travel with every service - see MASTER_CODE_STANDARDS.md. Regenerate the
# .docx from the .md rather than editing it, or the two drift and the .docx becomes the stale one.
!MASTER_CODE_STANDARDS.md
!MASTER_CODE_STANDARDS.docx
IGNORE

cat > "$DEST/gradle.properties" <<PROPS
org.gradle.jvmargs=-Xmx2g
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

# The ipie-platform-mca version this service builds against. One property, one deliberate bump -
# that is the trade the extraction buys: this service is no longer dragged by every platform change,
# but it must choose when to take one. Automate the bump as a PR gated on this repository's own CI.
# Never a dynamic 1.+ or -SNAPSHOT range: builds stop being reproducible and one bad platform commit
# would reach every service unchecked.
ipiePlatformVersion=$VERSION
PROPS

cat > "$DEST/settings.gradle" <<SETTINGS
// Standalone service repository. The ipie.* convention plugins, the version BOM and the shared
// libraries all resolve as PUBLISHED artifacts from ipie-platform-mca - there is no includeBuild
// and no project(':...') here, which is the whole point of the extraction.
//
// pluginManagement must be the first block in a settings file, so the platform version is read from
// gradle.properties here rather than from a variable declared above it.
pluginManagement {
    def ipieVersion = settings.providers.gradleProperty('ipiePlatformVersion').get()

    repositories {
        mavenLocal()
        maven {
            name = 'ipiePlatform'
            url = uri('https://maven.pkg.github.com/ipie-cms/ipie-platform-mca')
            credentials {
                username = providers.gradleProperty('ipie.packages.user')
                        .orElse(providers.environmentVariable('GITHUB_ACTOR')).getOrElse('')
                password = providers.gradleProperty('ipie.packages.token')
                        .orElse(providers.environmentVariable('GITHUB_TOKEN')).getOrElse('')
            }
        }
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith('ipie.')) {
                useModule("in.gov.ipie:ipie-build-conventions:\${ipieVersion}")
            }
        }
    }
}

rootProject.name = '$MODULE'
SETTINGS

python3 - "$DEST/build.gradle" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

header = """// Standalone service build. Every platform dependency below is a PUBLISHED artifact rather than a
// project(':...') - the version comes from ipiePlatformVersion in gradle.properties.
def ipiePlatform = providers.gradleProperty('ipiePlatformVersion').get()

repositories {
    mavenLocal()
    maven {
        name = 'ipiePlatform'
        url = uri('https://maven.pkg.github.com/ipie-cms/ipie-platform-mca')
        credentials {
            username = providers.gradleProperty('ipie.packages.user')
                    .orElse(providers.environmentVariable('GITHUB_ACTOR')).getOrElse('')
            password = providers.gradleProperty('ipie.packages.token')
                    .orElse(providers.environmentVariable('GITHUB_TOKEN')).getOrElse('')
        }
    }
    mavenCentral()
}

"""
s = s.replace("dependencies {", header + "dependencies {", 1)

# The BOM was supplied by the convention plugin's project(':ipie-parent'); declare it explicitly.
s = s.replace("    implementation project(':ipie-common-libs')",
"""    implementation platform("in.gov.ipie:ipie-parent:${ipiePlatform}")
    annotationProcessor platform("in.gov.ipie:ipie-parent:${ipiePlatform}")
    testImplementation platform("in.gov.ipie:ipie-parent:${ipiePlatform}")
    testAnnotationProcessor platform("in.gov.ipie:ipie-parent:${ipiePlatform}")

    implementation "in.gov.ipie:ipie-common-libs:${ipiePlatform}\"""")

# The published test fixtures must be consumed via testFixtures(), NOT as a ":test-fixtures"
# classifier. A classifier fetches the jar but bypasses Gradle Module Metadata variant selection, so
# the fixtures' own testFixturesApi dependencies - Testcontainers, spring-boot-starter-test - never
# come with it, and the first Testcontainers-based test fails with NoClassDefFoundError on
# PostgreSQLContainer. testFixtures() selects the testFixturesApiElements variant and its
# dependencies. Gradle supports it for external modules, not just projects.
s = s.replace("    testImplementation testFixtures(project(':ipie-common-libs'))",
              '    testImplementation testFixtures("in.gov.ipie:ipie-common-libs:${ipiePlatform}")')

assert "project(':ipie-common-libs')" not in s, "a project(':ipie-common-libs') reference survived"
open(p, 'w', encoding='utf-8').write(s)
PY

echo "==> done. Next:"
echo "    cd $DEST && ./gradlew check"
echo "    then create the repository, commit and push"
