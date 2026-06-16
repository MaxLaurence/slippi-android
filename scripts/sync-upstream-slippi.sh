#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/sync-upstream-slippi.sh

Detect the latest tagged Project Slippi Ishiiruka and mainline Dolphin
releases, replay the Android mainline patch queue, and optionally open or
update a GitHub pull request.

Environment:
  BASE_BRANCH          Fork branch to update. Default: android-port
  FORK_REMOTE          Remote used for the fork. Default: fork if it exists, else origin
  UPSTREAM_REMOTE      Remote used for project-slippi/Ishiiruka. Default: origin when
                       it already points there, else upstream-ishiiruka
  UPSTREAM_URL         URL added when UPSTREAM_REMOTE is missing.
                       Default: https://github.com/project-slippi/Ishiiruka.git
  MAINLINE_REMOTE      Remote inside Externals/MainlineSlippiDolphin. Default: origin
  SYNC_BRANCH_PREFIX   Automation branch prefix. Default: automation/upstream-slippi
  PATCH_BRANCH         Mainline patch branch name. Default: android-patches
  VERIFY_PATCHES       Run scripts/mainline-patch.sh verify --patch-only. Default: true
  RUN_DEBUG_BUILD      Run a full Android debug build after patch verification. Default: false
  CREATE_PR            Push the automation branch and create/update a PR. Default: false
  GITHUB_REPOSITORY    owner/repo for gh. In GitHub Actions this is set automatically.
USAGE
}

log() {
  printf '%s\n' "$*"
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

sanitize_component() {
  printf '%s' "$1" | sed -E 's#[^A-Za-z0-9._/-]+#-#g; s#/{2,}#/#g'
}

github_repo_from_remote() {
  local remote="$1"
  local url path
  url="$(git remote get-url "$remote" 2>/dev/null || true)"
  case "$url" in
    git@github.com:*) path="${url#git@github.com:}" ;;
    https://github.com/*) path="${url#https://github.com/}" ;;
    *) return 1 ;;
  esac
  path="${path%.git}"
  [[ -n "$path" ]] || return 1
  printf '%s\n' "$path"
}

ensure_remote() {
  local remote="$1"
  local url="$2"
  if git remote get-url "$remote" >/dev/null 2>&1; then
    return
  fi

  [[ -n "$url" ]] || die "remote '$remote' is missing and no URL was provided"
  git remote add "$remote" "$url"
}

ensure_clean_parent() {
  if ! git diff --quiet || ! git diff --cached --quiet; then
    git status --short >&2
    die "working tree has tracked changes; commit or stash them before syncing upstream"
  fi
}

ensure_mainline_submodule() {
  if [[ ! -e "$submodule/.git" ]]; then
    git submodule update --init "$submodule_rel"
  fi
  [[ -e "$submodule/.git" ]] || die "missing submodule at $submodule_rel"
}

top_level_fetch() {
  git -c submodule.recurse=false fetch --no-recurse-submodules "$@"
}

mainline_fetch() {
  git -C "$submodule" -c submodule.recurse=false fetch --no-recurse-submodules "$@"
}

fetch_top_level_refs() {
  log "Fetching fork branch and upstream Ishiiruka tags"
  top_level_fetch "$fork_remote" \
    "+refs/heads/$base_branch:refs/remotes/$fork_remote/$base_branch" --prune
  top_level_fetch "$fork_remote" "refs/tags/*:refs/tags/*"

  top_level_fetch "$upstream_remote" \
    "+refs/heads/slippi:refs/remotes/$upstream_remote/slippi" --prune
  top_level_fetch "$upstream_remote" "refs/tags/*:refs/tags/*"
}

fetch_mainline_refs() {
  log "Fetching mainline Dolphin tags"
  mainline_fetch "$mainline_remote" \
    "+refs/heads/slippi:refs/remotes/$mainline_remote/slippi" --prune
  mainline_fetch "$mainline_remote" "refs/tags/*:refs/tags/*"
}

latest_ishiiruka_tag() {
  git tag --merged "$upstream_remote/slippi" --list 'v[0-9]*' --sort=-v:refname |
    grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' |
    head -n 1 || true
}

latest_mainline_tag() {
  git -C "$submodule" tag --merged "$mainline_remote/slippi" \
    --list 'v4.0.0-mainline-beta.*' --sort=-v:refname |
    head -n 1
}

latest_android_tag_for() {
  local upstream_tag="$1"
  git tag --merged "$base_ref" --list "${upstream_tag}-android-r*" --sort=-v:refname |
    head -n 1
}

recorded_submodule_sha_for_base() {
  git ls-tree "$base_ref" "$submodule_rel" | awk '{ print $3 }'
}

write_release_excerpt() {
  local repo="$1"
  local tag="$2"
  local label="$3"
  local url body

  url="https://github.com/$repo/releases/tag/$tag"
  body=""
  if command -v gh >/dev/null 2>&1; then
    url="$(gh release view "$tag" --repo "$repo" --json url --jq .url 2>/dev/null || printf '%s' "$url")"
    body="$(gh release view "$tag" --repo "$repo" --json body --jq .body 2>/dev/null || true)"
  fi

  {
    printf '\n### %s\n\n' "$label"
    printf '- Release: [%s](%s)\n' "$tag" "$url"
    if [[ -n "$body" ]]; then
      printf '\n<details><summary>Upstream release notes</summary>\n\n'
      printf '%s\n' "$body"
      printf '\n</details>\n'
    fi
  } >> "$pr_body_file"
}

write_pr_body() {
  pr_body_file="$(mktemp)"
  cat > "$pr_body_file" <<EOF
## Plain-language summary

- Updates the Android port to the latest tagged Slippi/Ishiiruka release.
- Pins the embedded mainline Dolphin core to the matching latest tagged mainline release.
- Replays and exports our Android-only mainline patches so both core choices stay available in the APK.
- Runs the mainline patch verifier so patch conflicts show up before a public Android release.

## Automation result

- Ishiiruka upstream: \`${ishiiruka_tag}\`
- Mainline Dolphin upstream: \`${mainline_tag}\`
- Base branch: \`${base_branch}\`
- Automation branch: \`${sync_branch}\`
EOF

  write_release_excerpt "project-slippi/Ishiiruka" "$ishiiruka_tag" "Ishiiruka upstream"
  write_release_excerpt "project-slippi/dolphin" "$mainline_tag" "Mainline Dolphin upstream"

  cat >> "$pr_body_file" <<'EOF'

## Merge and release checklist

- Confirm the Android build workflow is green.
- Smoke-test the generated debug or release APK if the upstream change touches netplay, replay, input, or runtime behavior.
- After merging this upstream-sync PR, the merge-to-release workflow creates the next `v*-android-r*` release tag and dispatches the Android Release workflow automatically.
EOF
}

create_or_update_pr() {
  command -v gh >/dev/null 2>&1 || die "CREATE_PR=true requires the GitHub CLI"

  local repo existing_pr title
  repo="${GITHUB_REPOSITORY:-}"
  if [[ -z "$repo" ]]; then
    repo="$(github_repo_from_remote "$fork_remote" || true)"
  fi
  [[ -n "$repo" ]] || die "could not infer GitHub repository for gh"

  title="Update Slippi upstream to $ishiiruka_tag and $mainline_tag"
  write_pr_body

  log "Pushing $sync_branch to $fork_remote"
  git push --force-with-lease --set-upstream "$fork_remote" "$sync_branch"

  existing_pr="$(
    gh pr list --repo "$repo" --head "$sync_branch" --base "$base_branch" \
      --json number --jq '.[0].number // ""'
  )"

  if [[ -n "$existing_pr" ]]; then
    log "Updating existing PR #$existing_pr"
    gh pr edit "$existing_pr" --repo "$repo" --title "$title" --body-file "$pr_body_file"
    gh pr view "$existing_pr" --repo "$repo" --json url --jq .url
  else
    log "Creating upstream sync PR"
    gh pr create --repo "$repo" --base "$base_branch" --head "$sync_branch" \
      --title "$title" --body-file "$pr_body_file"
  fi
}

while (($#)); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

base_branch="${BASE_BRANCH:-android-port}"
upstream_url="${UPSTREAM_URL:-https://github.com/project-slippi/Ishiiruka.git}"
submodule_rel="Externals/MainlineSlippiDolphin"
submodule="$repo_root/$submodule_rel"
patch_dir="Source/Android/mainline-patches"
patch_branch="${PATCH_BRANCH:-android-patches}"
sync_branch_prefix="${SYNC_BRANCH_PREFIX:-automation/upstream-slippi}"
verify_patches="${VERIFY_PATCHES:-true}"
run_debug_build="${RUN_DEBUG_BUILD:-false}"
create_pr="${CREATE_PR:-false}"
mainline_remote="${MAINLINE_REMOTE:-origin}"

origin_url="$(git remote get-url origin 2>/dev/null || true)"
if [[ -n "${UPSTREAM_REMOTE:-}" ]]; then
  upstream_remote="$UPSTREAM_REMOTE"
elif [[ "$origin_url" == *project-slippi/Ishiiruka* ]]; then
  upstream_remote="origin"
else
  upstream_remote="upstream-ishiiruka"
fi

if [[ -n "${FORK_REMOTE:-}" ]]; then
  fork_remote="$FORK_REMOTE"
elif git remote get-url fork >/dev/null 2>&1; then
  fork_remote="fork"
else
  fork_remote="origin"
fi

ensure_clean_parent
ensure_remote "$upstream_remote" "$upstream_url"
ensure_mainline_submodule
fetch_top_level_refs
fetch_mainline_refs

base_ref="$fork_remote/$base_branch"
git rev-parse --verify --quiet "$base_ref^{commit}" >/dev/null ||
  die "base ref '$base_ref' was not fetched"

ishiiruka_tag="$(latest_ishiiruka_tag)"
[[ -n "$ishiiruka_tag" ]] || die "could not find a tagged Ishiiruka release on $upstream_remote/slippi"

mainline_tag="$(latest_mainline_tag)"
[[ -n "$mainline_tag" ]] || die "could not find a tagged mainline Dolphin release on $mainline_remote/slippi"

mainline_sha="$(git -C "$submodule" rev-parse "$mainline_tag^{commit}")"
base_mainline_sha="$(recorded_submodule_sha_for_base)"
android_tag="$(latest_android_tag_for "$ishiiruka_tag")"

log "Latest Ishiiruka release: $ishiiruka_tag"
log "Latest mainline release:  $mainline_tag"
log "Base branch:              $base_ref"

if git merge-base --is-ancestor "$ishiiruka_tag" "$base_ref" &&
    [[ "$base_mainline_sha" == "$mainline_sha" ]]; then
  if [[ -n "$android_tag" ]]; then
    log "No upstream sync needed; $base_branch already includes $ishiiruka_tag, $mainline_tag, and $android_tag."
  else
    log "No upstream sync needed; $base_branch is current, but no Android release tag exists for $ishiiruka_tag."
  fi
  exit 0
fi

sync_branch="$(sanitize_component "$sync_branch_prefix/${ishiiruka_tag#v}-${mainline_tag#v}")"
if git show-ref --verify --quiet "refs/heads/$sync_branch"; then
  die "local branch '$sync_branch' already exists; delete it or run the sync in a clean checkout"
fi

log "Creating $sync_branch from $base_ref"
git switch -c "$sync_branch" "$base_ref"

log "Merging Ishiiruka $ishiiruka_tag"
git merge --no-edit "$ishiiruka_tag"

log "Pinning mainline Dolphin $mainline_tag and replaying Android patches"
git -C "$submodule" checkout --detach "$mainline_tag"
scripts/mainline-patch.sh apply --branch "$patch_branch" --base "$mainline_tag" --force
scripts/mainline-patch.sh export --branch "$patch_branch" --base "$mainline_tag"
git -C "$submodule" checkout --detach "$mainline_tag"

if is_true "$verify_patches"; then
  log "Verifying mainline patch queue"
  scripts/mainline-patch.sh verify --patch-only
fi

git add "$submodule_rel" "$patch_dir"
if ! git diff --cached --quiet; then
  git commit -m "Update embedded mainline Slippi to ${mainline_tag#v4.0.0-mainline-}"
fi

if is_true "$run_debug_build"; then
  log "Running Android debug build"
  ./Source/Android/gradlew -p Source/Android :app:assembleDebug
fi

if [[ "$(git rev-list --count "$base_ref..HEAD")" == "0" ]]; then
  log "No commits were created after syncing; nothing to push."
  exit 0
fi

if is_true "$create_pr"; then
  create_or_update_pr
else
  cat <<MSG
Upstream sync branch is ready locally:
  $sync_branch

Set CREATE_PR=true to push this branch and create or update a pull request.
MSG
fi
