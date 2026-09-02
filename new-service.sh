#!/usr/bin/env bash
# Creates a new iPIE microservice from ipie-service-template, ready to build.
#
# Written as a script rather than left to the README because the rename is not one step, it is five
# scattered across 100+ files - the base package, the directory tree that mirrors it, the
# application class, rootProject.name, and the database name in application.yml. Doing four of the
# five leaves a service that compiles and then fails somewhere unhelpful: a missed
# ArchitectureTest.BASE_PACKAGE means the layering rules silently assert nothing, and a missed
# database name means two services quietly share one schema.
#
# It also checks the thing the README cannot: that the platform artifacts this service pins are
# actually resolvable on this machine. A new service whose first build fails on a 401 from GitHub
# Packages looks like a broken template rather than a missing credential.
#
# Usage:  ./new-service.sh ipie-case-service [destination-parent-dir]
#         ./new-service.sh ipie-case-service --from /path/to/ipie-service-template
#         ./new-service.sh ipie-case-service --package casefile   # override the package segment
#
# It does NOT create the GitHub repository, commit, or push - that stays a deliberate human step,
# for the same reason extract-service.sh leaves it alone.
set -euo pipefail

usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }

SERVICE=""
PARENT=""
TEMPLATE=""
PACKAGE_SEGMENT=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage 0 ;;
        --from) TEMPLATE="${2:?--from needs a path}"; shift 2 ;;
        --package) PACKAGE_SEGMENT="${2:?--package needs a segment}"; shift 2 ;;
        -*) echo "unknown option: $1" >&2; usage ;;
        *) if [ -z "$SERVICE" ]; then SERVICE="$1"; else PARENT="$1"; fi; shift ;;
    esac
done
[ -n "$SERVICE" ] || usage

ROOT="$(cd "$(dirname "$0")" && pwd)"
# The template sits beside this repository in the workspace; the destination need not. Deriving
# TEMPLATE from PARENT tied the two together, so naming a destination outside the workspace made the
# script look for the template there too and fail with a misleading "no template" error.
WORKSPACE="$(cd "$ROOT/.." && pwd)"
PARENT="${PARENT:-$WORKSPACE}"
TEMPLATE="${TEMPLATE:-$WORKSPACE/ipie-service-template}"
DEST="$PARENT/$SERVICE"

# --- the name decides the package, so it is validated rather than trusted --------------------------
# ipie-<name>-service -> in.gov.ipie.service.<name>, matching ipie-user-service -> ...service.user.
# A name that does not fit the pattern would produce a package no ArchUnit rule and no convention
# plugin expects, which is worth refusing now rather than explaining later.
if ! printf '%s' "$SERVICE" | grep -qE '^ipie-[a-z0-9]+(-[a-z0-9]+)*-service$'; then
    echo "error: service name must look like ipie-<name>-service, lowercase (e.g. ipie-case-service)" >&2
    exit 1
fi
SHORT="${SERVICE#ipie-}"; SHORT="${SHORT%-service}"; SHORT="${SHORT//-/}"
# Only the PACKAGE segment ever gives way to Java's keywords. The application class and the
# database keep following the service's own name, so ipie-case-service stays CaseServiceApplication
# against ipie_case_service - neither is constrained by the language, so neither should inherit a
# workaround that exists solely for it.
NAME="$SHORT"
CLASS="$(printf '%s' "$NAME" | sed 's/^./\U&/')ServiceApplication"
DB="ipie_${NAME}_service"

# The short name becomes a Java package segment, and a few plausible domain nouns are Java keywords.
# "case" is the one that matters here: caseId appears throughout this platform and `cases` is
# already a table, so ipie-case-service is a service someone will want to create. The package would
# be `in.gov.ipie.service.case`, which fails to parse in every file at once with
# "<identifier> expected" - an error that names neither the cause nor the fix.
#
# The service name is an architectural decision and the package segment is an implementation
# detail, so the segment gives way, not the name. A keyword is mapped to its plural, which is legal
# and reads naturally; --package overrides that for anything the mapping suits badly.
PACKAGE_SEGMENT="${PACKAGE_SEGMENT:-}"
if [ -z "$PACKAGE_SEGMENT" ]; then
    case "$SHORT" in
        case)      PACKAGE_SEGMENT=cases ;;
        class)     PACKAGE_SEGMENT=classes ;;
        record)    PACKAGE_SEGMENT=records ;;
        package)   PACKAGE_SEGMENT=packages ;;
        import)    PACKAGE_SEGMENT=imports ;;
        interface) PACKAGE_SEGMENT=interfaces ;;
        enum)      PACKAGE_SEGMENT=enums ;;
        default)   PACKAGE_SEGMENT=defaults ;;
        switch)    PACKAGE_SEGMENT=switches ;;
        *)         PACKAGE_SEGMENT="$SHORT" ;;
    esac
fi

# Whatever we ended up with - mapped, defaulted or supplied - must itself be a legal segment.
if ! printf '%s' "$PACKAGE_SEGMENT" | grep -qE '^[a-z][a-z0-9]*$'; then
    echo "error: package segment '$PACKAGE_SEGMENT' must be lowercase letters and digits, starting with a letter" >&2
    exit 1
fi
case " abstract assert boolean break byte case catch char class const continue default do double \
else enum extends final finally float for goto if implements import instanceof int interface long \
native new package private protected public return short static strictfp super switch synchronized \
this throw throws transient try void volatile while true false null record var yield permits sealed " in
    *" $PACKAGE_SEGMENT "*)
        echo "error: '$PACKAGE_SEGMENT' is a Java reserved word and cannot be a package segment." >&2
        echo "       Pass --package <segment> to choose another, e.g. --package ${PACKAGE_SEGMENT}s" >&2
        exit 1 ;;
esac
SHORT="$PACKAGE_SEGMENT"

[ -d "$TEMPLATE" ] || { echo "error: no template at $TEMPLATE (pass --from <path>)" >&2; exit 1; }
# Never overwrite. A second run against an existing directory is far more likely to be a typo than
# an intent to discard work.
[ -e "$DEST" ] && { echo "error: $DEST already exists - refusing to overwrite" >&2; exit 1; }

echo "==> $SERVICE"
echo "    package  in.gov.ipie.service.$SHORT"
echo "    class    $CLASS"
echo "    database $DB"
echo "    from     $TEMPLATE"
echo "    into     $DEST"

# --- copy: tracked files only, so build output and IDE state do not travel ------------------------
mkdir -p "$DEST"
if git -C "$TEMPLATE" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$TEMPLATE" ls-files -z | tar -cf - -C "$TEMPLATE" --null -T - | tar -xf - -C "$DEST"
else
    echo "    (template is not a git repository - copying everything except build output)"
    tar -cf - -C "$TEMPLATE" --exclude=build --exclude=.gradle --exclude=.git . | tar -xf - -C "$DEST"
fi

# --- rename, in the order that keeps the tree walkable --------------------------------------------
# Directories first, then contents: moving after rewriting would leave paths that no longer exist.
for tree in "$DEST/src/main/java" "$DEST/src/test/java"; do
    [ -d "$tree/in/gov/ipie/service/template" ] || continue
    mv "$tree/in/gov/ipie/service/template" "$tree/in/gov/ipie/service/$SHORT"
done
mv "$DEST/src/main/java/in/gov/ipie/service/$SHORT/ServiceTemplateApplication.java" \
   "$DEST/src/main/java/in/gov/ipie/service/$SHORT/$CLASS.java" 2>/dev/null || true

# Text, across every file type that carries the name. -print0/-0 because a path may contain spaces.
find "$DEST" -type f \( -name '*.java' -o -name '*.gradle' -o -name '*.yml' -o -name '*.yaml' \
        -o -name '*.md' -o -name '*.properties' -o -name '*.xml' -o -name '*.sql' \) -print0 \
    | xargs -0 sed -i \
        -e "s/in\.gov\.ipie\.service\.template/in.gov.ipie.service.$SHORT/g" \
        -e "s/ServiceTemplateApplication/$CLASS/g" \
        -e "s/ipie_service_template/$DB/g" \
        -e "s/ipie-service-template/$SERVICE/g"

# --- prove nothing was missed ---------------------------------------------------------------------
# A leftover reference is the failure this script exists to prevent, so it is checked rather than
# assumed. README prose about the template repository itself is allowed to survive; code is not.
LEFTOVER="$(grep -rlE 'in\.gov\.ipie\.service\.template|ServiceTemplateApplication|ipie_service_template' \
    "$DEST" --include='*.java' --include='*.gradle' --include='*.yml' --include='*.sql' 2>/dev/null || true)"
if [ -n "$LEFTOVER" ]; then
    echo "error: template references survived the rename:" >&2
    printf '  %s\n' $LEFTOVER >&2
    exit 1
fi

# --- can this service actually resolve the platform it pins? --------------------------------------
VERSION="$(grep -E '^ipiePlatformVersion=' "$DEST/gradle.properties" | cut -d= -f2)"
M2="$HOME/.m2/repository/in/gov/ipie"
if [ -d "$M2/ipie-common-libs/$VERSION" ] && [ -d "$M2/ipie-parent/$VERSION" ] \
   && [ -d "$M2/ipie-build-conventions/$VERSION" ]; then
    PLATFORM="Maven Local ($VERSION)"
elif [ -n "${GITHUB_TOKEN:-}" ] || grep -qs 'ipie.packages.token' "$HOME/.gradle/gradle.properties"; then
    PLATFORM="GitHub Packages ($VERSION)"
else
    PLATFORM=""
fi

echo "==> created $DEST"
if [ -n "$PLATFORM" ]; then
    echo "    platform $VERSION resolves from $PLATFORM"
else
    # Not fatal: the service is correctly generated, it just cannot build here yet. Saying so now
    # is the difference between a missing credential and an apparently broken template.
    cat >&2 <<EOF

    WARNING: platform $VERSION is not resolvable on this machine.
    ipie-parent, ipie-common-libs and ipie-build-conventions are not in ~/.m2, and no GitHub
    Packages credential is configured, so the first build will fail on resolution. Fix either:
      cd $ROOT && ./gradlew publishToMavenLocal
    or set ipie.packages.user / ipie.packages.token in ~/.gradle/gradle.properties.
EOF
fi

echo "    next:"
echo "      cd $DEST && ./gradlew check"
echo "      then create the repository, commit and push"
