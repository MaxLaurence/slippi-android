#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/release-merged-upstream-sync.sh

Create the next Android release tag after an upstream-sync PR is merged, create
or update a plain-language GitHub release, and dispatch the Android Release
workflow so APKs are built and uploaded.

Environment:
  UPSTREAM_SYNC_BRANCH       Merged PR head branch. Must start with the sync prefix.
  UPSTREAM_SYNC_PREFIX       Required PR branch prefix. Default: automation/upstream-slippi/
  BASE_BRANCH                Release branch. Default: android-port
  RELEASE_WORKFLOW           Workflow file to dispatch. Default: android-release.yml
  DISPATCH_RELEASE           Dispatch Android Release when APK assets are missing. Default: true
  DRY_RUN                    Print actions without mutating GitHub. Default: false
  GITHUB_REPOSITORY          owner/repo for gh. In GitHub Actions this is set automatically.
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

github_repo_from_origin() {
  local url path
  url="$(git remote get-url origin 2>/dev/null || true)"
  case "$url" in
    git@github.com:*) path="${url#git@github.com:}" ;;
    https://github.com/*) path="${url#https://github.com/}" ;;
    *) return 1 ;;
  esac
  path="${path%.git}"
  [[ -n "$path" ]] || return 1
  printf '%s\n' "$path"
}

ensure_mainline_submodule() {
  if [[ ! -e "$mainline_path/.git" ]]; then
    git submodule update --init "$mainline_rel"
  fi
  [[ -e "$mainline_path/.git" ]] || die "missing submodule at $mainline_rel"
}

fetch_release_tags() {
  git -c submodule.recurse=false fetch --no-recurse-submodules origin \
    "refs/tags/*:refs/tags/*"
  git -C "$mainline_path" -c submodule.recurse=false fetch --no-recurse-submodules origin \
    "refs/tags/*:refs/tags/*"
}

latest_ishiiruka_tag() {
  git tag --merged HEAD --list 'v[0-9]*' --sort=-v:refname |
    grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' |
    head -n 1 || true
}

mainline_tag_for_gitlink() {
  git -C "$mainline_path" tag --points-at HEAD \
    --list 'v4.0.0-mainline-beta.*' --sort=-v:refname |
    head -n 1 || true
}

next_android_release_tag() {
  local upstream_tag="$1"
  local latest existing_release

  latest="$(
    git tag --list "${upstream_tag}-android-r*" --sort=-v:refname |
      head -n 1 || true
  )"

  if [[ -n "$latest" ]] &&
      [[ "$(git rev-parse "$latest^{commit}")" == "$(git rev-parse HEAD)" ]]; then
    printf '%s\n' "$latest"
    return
  fi

  if [[ -z "$latest" ]]; then
    printf '%s-android-r1\n' "$upstream_tag"
    return
  fi

  existing_release="${latest##*-android-r}"
  [[ "$existing_release" =~ ^[0-9]+$ ]] ||
    die "could not parse Android release number from $latest"

  printf '%s-android-r%d\n' "$upstream_tag" "$((existing_release + 1))"
}

release_asset_count() {
  local tag="$1"
  gh release view "$tag" --repo "$repo" --json assets \
    --jq '[.assets[].name | select(endswith(".apk"))] | length' 2>/dev/null ||
    printf '0\n'
}

release_exists() {
  local tag="$1"
  gh release view "$tag" --repo "$repo" >/dev/null 2>&1
}

upstream_release_link() {
  local release_repo="$1"
  local tag="$2"
  gh release view "$tag" --repo "$release_repo" --json url --jq .url 2>/dev/null ||
    printf 'https://github.com/%s/releases/tag/%s\n' "$release_repo" "$tag"
}

write_release_notes() {
  local ishiiruka_url mainline_url
  ishiiruka_url="$(upstream_release_link project-slippi/Ishiiruka "$ishiiruka_tag")"
  mainline_url="$(upstream_release_link project-slippi/dolphin "$mainline_tag")"

  release_notes_file="$(mktemp)"
  cat > "$release_notes_file" <<EOF
## What's new

This Android release was created automatically after merging an upstream-sync PR.
It brings the Android fork up to Slippi/Ishiiruka ${ishiiruka_tag} and updates
the embedded mainline Dolphin core to ${mainline_tag}.

In plain terms:

- The app gets the latest upstream Slippi fixes from the Ishiiruka release.
- The optional mainline Dolphin core is kept in sync with the matching upstream mainline release.
- Our Android-specific mainline patches are replayed before release so both core choices keep working.
- The release build still verifies that each APK contains the expected Android native libraries and mainline assets.

## Downloads

- \`slippi-android-${android_tag}.apk\` is the normal arm64 Android build for modern devices.
- \`slippi-android-${android_tag}-x86_64.apk\` is for x86_64 Android devices or emulators.
- The \`.sha256\` files are checksums for verifying that downloads were not corrupted.

The APKs are uploaded by the Android Release workflow after this release is created.

## Upstream releases

- Ishiiruka: [${ishiiruka_tag}](${ishiiruka_url})
- Mainline Dolphin: [${mainline_tag}](${mainline_url})
EOF
}

create_or_update_release() {
  write_release_notes

  if release_exists "$android_tag"; then
    log "Updating existing release $android_tag"
    if ! is_true "$dry_run"; then
      gh release edit "$android_tag" --repo "$repo" \
        --title "$android_tag" --notes-file "$release_notes_file"
    fi
  else
    log "Creating release $android_tag"
    if ! is_true "$dry_run"; then
      gh release create "$android_tag" --repo "$repo" \
        --target "$target_commit" --title "$android_tag" --notes-file "$release_notes_file"
    fi
  fi
}

create_tag_if_needed() {
  if git rev-parse --verify --quiet "refs/tags/$android_tag" >/dev/null; then
    local tag_commit
    tag_commit="$(git rev-parse "$android_tag^{commit}")"
    [[ "$tag_commit" == "$target_commit" ]] ||
      die "tag $android_tag already exists at $tag_commit, not target $target_commit"
    log "Release tag $android_tag already exists at $target_commit"
    return
  fi

  log "Creating annotated tag $android_tag at $target_commit"
  if ! is_true "$dry_run"; then
    git tag -a "$android_tag" "$target_commit" -m "Release $android_tag"
    git push origin "refs/tags/$android_tag"
  fi
}

dispatch_release_workflow_if_needed() {
  local apk_count
  apk_count="$(release_asset_count "$android_tag")"
  if [[ "$apk_count" =~ ^[0-9]+$ ]] && [[ "$apk_count" -ge 2 ]]; then
    log "Release $android_tag already has $apk_count APK assets; not dispatching $release_workflow."
    return
  fi

  if ! is_true "$dispatch_release"; then
    log "DISPATCH_RELEASE=false; not dispatching $release_workflow."
    return
  fi

  log "Dispatching $release_workflow for ${android_tag#v} on ref $android_tag"
  if ! is_true "$dry_run"; then
    gh workflow run "$release_workflow" --repo "$repo" \
      --ref "$android_tag" -f "version=${android_tag#v}"
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

sync_branch="${UPSTREAM_SYNC_BRANCH:-}"
sync_prefix="${UPSTREAM_SYNC_PREFIX:-automation/upstream-slippi/}"
base_branch="${BASE_BRANCH:-android-port}"
release_workflow="${RELEASE_WORKFLOW:-android-release.yml}"
dispatch_release="${DISPATCH_RELEASE:-true}"
dry_run="${DRY_RUN:-false}"
mainline_rel="Externals/MainlineSlippiDolphin"
mainline_path="$repo_root/$mainline_rel"

if [[ -z "$sync_branch" ]]; then
  die "UPSTREAM_SYNC_BRANCH is required"
fi

if [[ "$sync_branch" != "$sync_prefix"* ]]; then
  log "Merged PR branch '$sync_branch' is not an upstream-sync branch; no release needed."
  exit 0
fi

command -v gh >/dev/null 2>&1 || die "GitHub CLI is required"

repo="${GITHUB_REPOSITORY:-}"
if [[ -z "$repo" ]]; then
  repo="$(github_repo_from_origin || true)"
fi
[[ -n "$repo" ]] || die "could not infer GitHub repository"

ensure_mainline_submodule
fetch_release_tags

target_commit="$(git rev-parse HEAD)"
ishiiruka_tag="$(latest_ishiiruka_tag)"
[[ -n "$ishiiruka_tag" ]] || die "could not find a merged Ishiiruka release tag"

mainline_tag="$(mainline_tag_for_gitlink)"
[[ -n "$mainline_tag" ]] ||
  die "mainline submodule HEAD is not tagged with v4.0.0-mainline-beta.*"

android_tag="$(next_android_release_tag "$ishiiruka_tag")"

log "Merged upstream-sync branch: $sync_branch"
log "Release branch:              $base_branch"
log "Target commit:               $target_commit"
log "Ishiiruka upstream:          $ishiiruka_tag"
log "Mainline upstream:           $mainline_tag"
log "Android release tag:         $android_tag"

create_tag_if_needed
create_or_update_release
dispatch_release_workflow_if_needed
