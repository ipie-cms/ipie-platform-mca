#!/usr/bin/env bash
#
# Migrates the estate to the official GitHub account as a single history-free commit.
#
# What it produces per repository: one commit, subject "Initial commit", containing the working
# tree of the chosen source branch and nothing else - no ancestry, no prior authorship, no merge
# record - with the five long-lived branches of the branching model created from it.
#
# It never touches the local repositories. Each one is cloned to a temporary directory and the
# orphan commit is built there, so an interrupted run cannot damage work in progress.
#
# Usage:
#   ./migrate-to-official-account.sh --owner <github-owner> [--source <branch>] [--dry-run]
#                                    [--only <repo>] [--https|--ssh] [--force]
#
# --force overwrites what is already on the official account. Needed only to re-run a migration:
# the orphan commit shares no ancestor with whatever is there, so an ordinary push is rejected as
# unrelated rather than merged. It DISCARDS the remote's current commits outright - which is the
# intent when correcting a migration, and a mistake anywhere else. Off unless asked for.
#
# The author recorded on the commit defaults to this machine's git identity. Override it for the
# official account with IPIE_MIGRATION_AUTHOR_NAME and IPIE_MIGRATION_AUTHOR_EMAIL.

set -euo pipefail

OWNER=""
SOURCE_BRANCH="master"
DRY_RUN=false
ONLY=""
SCHEME="https"
FORCE=false

REPOS=(
  ipie-platform-mca
  ipie-iam-service
  ipie-user-service
  ipie-communication-service
  ipie-service-template
  ipie-web
)

# Where each repository lives on this machine, relative to this script's grandparent directory.
declare -A LOCAL_PATH=(
  [ipie-platform-mca]="../ipie-platform-mca"
  [ipie-iam-service]="../ipie-iam-service"
  [ipie-user-service]="../ipie-user-service"
  [ipie-communication-service]="../ipie-communication-service"
  [ipie-service-template]="../ipie-service-template"
  [ipie-web]="../../ipie-web"
)

PROMOTION_BRANCHES=(develop test uat preprod)

while [ $# -gt 0 ]; do
  case "$1" in
    --owner) OWNER="$2"; shift 2 ;;
    --source) SOURCE_BRANCH="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --force) FORCE=true; shift ;;
    --https) SCHEME="https"; shift ;;
    --ssh) SCHEME="ssh"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$OWNER" ]; then
  echo "--owner is required (the official GitHub account or organisation)." >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

remote_url() {
  local repo="$1"
  if [ "$SCHEME" = "ssh" ]; then
    printf 'git@github.com:%s/%s.git' "$OWNER" "$repo"
  else
    printf 'https://github.com/%s/%s.git' "$OWNER" "$repo"
  fi
}

# A migration is the last moment at which unwanted content can be caught, because afterwards there
# is no history to inspect. Refuse rather than warn.
audit_tree() {
  local dir="$1"
  local hits
  # This script carries the very words it searches for, so it matched itself and made
  # ipie-platform-mca - the repository it lives in - permanently unmigratable. Excluding its own
  # path is the narrowest fix: everything else in every tree is still audited, and the exclusion is
  # a literal path rather than a pattern, so it cannot quietly widen.
  hits=$(git -C "$dir" grep -licE 'claude|anthropic|copilot|chatgpt' -- . \
            ':(exclude)deploy/github/migrate-to-official-account.sh' 2>/dev/null || true)
  local names
  names=$(git -C "$dir" ls-files | grep -icE 'claude|anthropic' || true)
  if [ -n "$hits" ] || [ "$names" != "0" ]; then
    echo "   REFUSING: assistant references found in the tree:" >&2
    [ -n "$hits" ] && echo "$hits" | sed 's/^/     /' >&2
    return 1
  fi
  return 0
}

echo "Migrating to ${OWNER} from source branch '${SOURCE_BRANCH}'"
[ "$DRY_RUN" = true ] && echo "(dry run - nothing will be pushed)"
[ "$FORCE" = true ] && echo "(force - any existing commits on the official account are discarded)"
echo

failures=0
for repo in "${REPOS[@]}"; do
  [ -n "$ONLY" ] && [ "$ONLY" != "$repo" ] && continue
  src="${ROOT}/${LOCAL_PATH[$repo]}"
  echo "== ${repo}"
  if [ ! -d "${src}/.git" ]; then
    echo "   SKIP: no repository at ${src}"
    failures=$((failures + 1))
    continue
  fi

  dst="${WORK}/${repo}"
  git clone --quiet --no-hardlinks --branch "$SOURCE_BRANCH" "$src" "$dst"

  if ! audit_tree "$dst"; then
    failures=$((failures + 1))
    continue
  fi

  # A clone does not inherit the source repository's local identity, and these repositories set it
  # per-repository rather than globally - so resolve it explicitly instead of letting the commit
  # fail on an empty ident.
  author_name="${IPIE_MIGRATION_AUTHOR_NAME:-$(git -C "$src" config user.name || true)}"
  author_email="${IPIE_MIGRATION_AUTHOR_EMAIL:-$(git -C "$src" config user.email || true)}"
  if [ -z "$author_name" ] || [ -z "$author_email" ]; then
    echo "   REFUSING: no author identity. Set IPIE_MIGRATION_AUTHOR_NAME and _EMAIL." >&2
    failures=$((failures + 1))
    continue
  fi

  # The estate-management tooling does not travel into the published estate. This script is how a
  # repository gets there; it is not part of what was delivered, and it is the only file in any tree
  # that carries the vendor names its own audit searches for. Dropping the directory removes the
  # last such reference outright rather than excluding it from a check - the published tree simply
  # does not contain one. The tooling stays in the working repository, where it is used from.
  rm -rf "$dst/deploy/github"

  git -C "$dst" checkout --quiet --orphan migration
  git -C "$dst" add -A
  git -C "$dst" -c "user.name=${author_name}" -c "user.email=${author_email}" \
      commit --quiet -m "Initial commit"

  # The five long-lived branches start identical, so the first divergence is a promotion.
  git -C "$dst" branch --quiet -M master
  for b in "${PROMOTION_BRANCHES[@]}"; do
    # -f, because when --source names one of these branches the clone already carries it and a
    # plain `git branch` aborts the whole migration on "already exists". Forcing is safe here: this
    # is a throwaway clone whose only real commit is the orphan one just made.
    git -C "$dst" branch -f "$b" master
  done

  count=$(git -C "$dst" rev-list --count master)
  subject=$(git -C "$dst" log -1 --format='%s')
  files=$(git -C "$dst" ls-files | wc -l)
  printf '   %s commit(s), subject "%s", %s files\n' "$count" "$subject" "$files"
  if [ "$count" != "1" ]; then
    echo "   REFUSING: expected exactly one commit" >&2
    failures=$((failures + 1))
    continue
  fi

  if [ "$DRY_RUN" = true ]; then
    echo "   would ${FORCE:+force-}push master, ${PROMOTION_BRANCHES[*]} to $(remote_url "$repo")"
    continue
  fi

  git -C "$dst" remote add official "$(remote_url "$repo")"
  push_args=(--quiet official master "${PROMOTION_BRANCHES[@]}")
  # An orphan commit has no ancestor in common with anything already published, so a re-run is
  # rejected outright rather than fast-forwarded. --force is therefore the only way to correct a
  # migration in place, and is why it is a flag rather than the default.
  [ "$FORCE" = true ] && push_args=(--force "${push_args[@]}")
  if git -C "$dst" push "${push_args[@]}"; then
    echo "   pushed to $(remote_url "$repo")"
  else
    echo "   FAILED to push - does ${OWNER}/${repo} exist and is it empty?" >&2
    failures=$((failures + 1))
  fi
done

echo
if [ "$failures" -gt 0 ]; then
  echo "${failures} repository/repositories did not complete."
  exit 1
fi
if [ "$DRY_RUN" = true ]; then
  echo "Dry run complete. Nothing was changed locally or remotely."
else
  echo "Migration complete. Apply branch protection next: ./apply-branch-protection.sh"
fi
