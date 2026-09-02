#!/usr/bin/env bash
#
# Applies the branch protection required by the branching model (Development Environment
# Configuration, Section 45) to every repository in the estate.
#
# The rules below are the specification, held in runnable form so that the control is applied by
# executing a reviewed script rather than by someone clicking through five settings pages per
# repository from memory.
#
# Status on 2026-09-02: this runs. It could not on 2026-08-15, when the repositories were private
# under a personal account and both the branch-protection and rulesets APIs answered
#
#     403 "Upgrade to GitHub Pro or make this repository public to enable this feature."
#
# What changed is visibility, not billing: GitHub charges for branch protection on PRIVATE
# repositories only, and all five are public under the official account. That is a real trade rather
# than a free win - making any of them private again disables its protection until the account is on
# Pro, Team or Enterprise. Confirmed before running: the protection endpoint answers 404 (available,
# unset) rather than 403 (plan-blocked).
#
# Usage:  ./apply-branch-protection.sh [--enforce-admins] [--dry-run]
#
# Token resolution, in order: GITHUB_TOKEN, then the git credential helper.

set -euo pipefail

OWNER="${IPIE_GITHUB_OWNER:-ipie-cms}"
# Every repository on the official account. ipie-web was absent until 2026-09-02 because it had not
# been migrated - protecting it then would have applied the estate's rules to a repository living
# under a different owner. It now sits on ipie-cms like the rest, so it is protected like the rest.
#
# It is the one repository here carrying real pre-handover history (9 commits) rather than a single
# Initial commit: its audit came back clean, so there was nothing to flatten. That difference does
# not change what the branching model requires of it.
REPOS=(
  ipie-platform-mca
  ipie-iam-service
  ipie-user-service
  ipie-communication-service
  ipie-service-template
  ipie-web
)

# branch:approvals - the approval counts come from Section 45's protection column.
#   develop  one approving review (peer)
#   test     one approving review (QA Lead)
#   uat      two (QA Lead and business)
#   preprod  two (Release Manager)
#   master   two (Release Manager and Operations Head), plus a change record outside GitHub
BRANCHES=(
  "develop:1"
  "test:1"
  "uat:2"
  "preprod:2"
  "master:2"
)

ENFORCE_ADMINS=false
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --enforce-admins) ENFORCE_ADMINS=true ;;
    --dry-run) DRY_RUN=true ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# enforce_admins is deliberately off by default. GitHub does not let an author approve their own
# pull request, so with a single committer and a required approval, turning it on makes every
# long-lived branch unmergeable. Turn it on with --enforce-admins as soon as the team is larger
# than one reviewer; until then the exception is recorded rather than silently relied upon.

token() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    printf '%s' "$GITHUB_TOKEN"
    return
  fi
  printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null \
    | sed -n 's/^password=//p'
}

TOKEN="$(token)"
if [ -z "$TOKEN" ]; then
  echo "No GitHub token available. Set GITHUB_TOKEN or sign in to the git credential helper." >&2
  exit 1
fi

payload() {
  local approvals="$1"
  cat <<JSON
{
  "required_status_checks": null,
  "enforce_admins": ${ENFORCE_ADMINS},
  "required_pull_request_reviews": {
    "required_approving_review_count": ${approvals},
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true,
  "required_linear_history": false
}
JSON
}

# required_status_checks stays null until the CI/CD pipeline publishes named checks. Once it does,
# add them here - a protection rule that requires no check is a review gate, not a quality gate.

failures=0
for repo in "${REPOS[@]}"; do
  echo "== ${OWNER}/${repo}"
  for entry in "${BRANCHES[@]}"; do
    branch="${entry%%:*}"
    approvals="${entry##*:}"
    if [ "$DRY_RUN" = true ]; then
      printf '   %-8s would require %s approving review(s)\n' "$branch" "$approvals"
      continue
    fi
    code=$(curl -sS -o /tmp/ipie-protect-resp.json -w '%{http_code}' -X PUT \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "https://api.github.com/repos/${OWNER}/${repo}/branches/${branch}/protection" \
      -d "$(payload "$approvals")")
    if [ "$code" = "200" ]; then
      printf '   %-8s protected (%s approval(s), force-push and deletion blocked)\n' \
        "$branch" "$approvals"
    else
      printf '   %-8s FAILED http %s: %s\n' "$branch" "$code" \
        "$(python3 -c 'import json,sys;print(json.load(open("/tmp/ipie-protect-resp.json")).get("message",""))' 2>/dev/null)"
      failures=$((failures + 1))
    fi
  done
done

if [ "$DRY_RUN" = true ]; then
  echo
  echo "Dry run only - nothing was changed. ${#REPOS[@]} repositories x ${#BRANCHES[@]} branches."
  exit 0
fi

if [ "$failures" -gt 0 ]; then
  echo
  echo "${failures} rule(s) failed to apply."
  exit 1
fi
echo
echo "Branch protection applied across ${#REPOS[@]} repositories."
