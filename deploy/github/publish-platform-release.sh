#!/usr/bin/env bash
#
# Uploads a RELEASED platform version's artifacts from this machine's Maven Local to GitHub
# Packages.
#
# It uploads bytes rather than rebuilding them, and that is the whole point. A released version is
# built once; the platform working tree moves on to the next -SNAPSHOT immediately and its code
# changes, so `version=<released> && ./gradlew publish` would produce DIFFERENT bytes under a
# version that is supposed to be immutable. Two developers would then hold different copies of the
# same version - exactly the failure that releasing 0.1.0 was meant to end. Cut a new version
# instead if the platform needs to change.
#
# Takes the version as an argument rather than hardcoding one: a second copy of this script per
# release would drift the same way the four copies of JpaOutboxStore did.
#
# Needs a CLASSIC PAT with write:packages (fine-grained tokens do not work against the Maven
# registry). The account needs push on the target repository - not admin.
#
#   export GITHUB_ACTOR=<github-username> GITHUB_TOKEN=<classic-pat>
#   ./publish-platform-release.sh 0.1.0 [--dry-run]
#
# Idempotent in the sense that matters: GitHub Packages REFUSES to overwrite an existing release
# file, so a second run reports 409 on everything and changes nothing.

set -euo pipefail

usage() { echo "usage: $(basename "$0") <version> [--dry-run]" >&2; exit 1; }

VERSION="${1:-}"
[ -n "$VERSION" ] || usage
shift
# A -SNAPSHOT is mutable by definition; publishing one to a shared registry recreates the exact
# problem cutting 0.1.0 was meant to end, so it is refused rather than warned about.
case "$VERSION" in
  *-SNAPSHOT) echo "refusing to publish a SNAPSHOT ($VERSION) - cut a release version first" >&2; exit 1 ;;
esac
OWNER="${IPIE_PACKAGES_OWNER:-ipie-cms}"
REPO="${IPIE_PACKAGES_REPO:-ipie-platform-mca}"
BASE="https://maven.pkg.github.com/${OWNER}/${REPO}"
M2="${HOME}/.m2/repository"
DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true
[ $# -gt 1 ] && usage

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
