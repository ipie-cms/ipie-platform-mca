#!/usr/bin/env bash
#
# Uploads the RELEASED platform 0.1.0 artifacts from this machine's Maven Local to GitHub Packages.
#
# It uploads bytes rather than rebuilding them, and that is the whole point. 0.1.0 was built on
# 2026-08-31 and every service pins it; the platform working tree has moved on to 0.2.0-SNAPSHOT and
# its code has changed since, so `version=0.1.0 && ./gradlew publish` would produce DIFFERENT bytes
# under a version that is supposed to be immutable. Two developers would then hold different 0.1.0s
# - exactly the failure that releasing 0.1.0 was meant to end. Cut a new version instead if the
# platform needs to change.
#
# Needs a CLASSIC PAT with write:packages (fine-grained tokens do not work against the Maven
# registry). The account needs push on the target repository - not admin.
#
#   export GITHUB_ACTOR=<github-username> GITHUB_TOKEN=<classic-pat>
#   ./publish-platform-0.1.0.sh [--dry-run]
#
# Idempotent in the sense that matters: GitHub Packages REFUSES to overwrite an existing release
# file, so a second run reports 409 on everything and changes nothing.

set -euo pipefail

VERSION="0.1.0"
OWNER="${IPIE_PACKAGES_OWNER:-ipie-cms}"
REPO="${IPIE_PACKAGES_REPO:-ipie-platform-mca}"
BASE="https://maven.pkg.github.com/${OWNER}/${REPO}"
M2="${HOME}/.m2/repository"
DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true

if [ "$DRY_RUN" = false ]; then
  : "${GITHUB_ACTOR:?set GITHUB_ACTOR to the GitHub username}"
  : "${GITHUB_TOKEN:?set GITHUB_TOKEN to a classic PAT with write:packages}"
fi

# The Maven layout under ~/.m2/repository is already the repository layout, so a file's path
# relative to it IS its path in the registry. maven-metadata-local.xml is deliberately excluded:
# it describes this machine's local cache and means nothing in a shared registry, which builds its
# own metadata. Services pin an exact version, so none of them needs it.
mapfile -t FILES < <(cd "$M2" && find \
  "in/gov/ipie"/*/"$VERSION" \
  "ipie"/*/*/"$VERSION" \
  -type f ! -name 'maven-metadata-local.xml' 2>/dev/null | sort)

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "No ${VERSION} artifacts found under ${M2} - nothing to publish." >&2
  exit 1
fi

echo "Publishing ${#FILES[@]} files of platform ${VERSION} to ${BASE}"
[ "$DRY_RUN" = true ] && echo "(dry run - nothing is uploaded)"
echo

failures=0
uploaded=0
for rel in "${FILES[@]}"; do
  src="${M2}/${rel}"
  printf '  %-92s ' "$rel"
  if [ "$DRY_RUN" = true ]; then
    echo "would PUT ($(stat -c%s "$src") bytes)"
    continue
  fi
  code=$(curl -sS -o /dev/null -w '%{http_code}' -u "${GITHUB_ACTOR}:${GITHUB_TOKEN}" \
              -X PUT --upload-file "$src" "${BASE}/${rel}")
  case "$code" in
    200|201|202) echo "OK ($code)"; uploaded=$((uploaded + 1)) ;;
    409)         echo "already published ($code) - left as it is" ;;
    *)           echo "FAILED ($code)"; failures=$((failures + 1)) ;;
  esac
done

echo
if [ "$DRY_RUN" = true ]; then
  echo "Dry run complete."
  exit 0
fi
echo "Uploaded ${uploaded}, failed ${failures}."

# Verify by reading back the one file a consuming build reaches for first: without the parent BOM
# nothing else resolves, and a service fails at settings evaluation naming an ipie.* plugin rather
# than the missing artifact - so check it explicitly instead of trusting the upload codes.
probe="in/gov/ipie/ipie-parent/${VERSION}/ipie-parent-${VERSION}.pom"
code=$(curl -sS -o /dev/null -w '%{http_code}' -u "${GITHUB_ACTOR}:${GITHUB_TOKEN}" "${BASE}/${probe}")
echo "Read-back of ${probe}: HTTP ${code}"
[ "$code" = "200" ] && [ "$failures" -eq 0 ] || exit 1
echo "Platform ${VERSION} is resolvable from ${BASE}."
