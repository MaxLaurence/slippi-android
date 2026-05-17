#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
submodule_rel="Externals/MainlineSlippiDolphin"
submodule="$repo_root/$submodule_rel"
android_dir="$repo_root/Source/Android"
patch_dir="$android_dir/mainline-patches"
default_patch_branch="${MAINLINE_PATCH_BRANCH:-android-patches}"

usage() {
  cat <<'USAGE'
Usage: scripts/mainline-patch.sh <command> [options]

Manage the Android patch stack carried on top of embedded Project Slippi
mainline Dolphin.

Commands:
  status                         Show mainline checkout and patch queue state.
  apply [options]                Rebuild a local patch branch from the queue.
  new <slug> [options]           Add an empty commit slot on the patch branch.
  export [options]               Export patch-branch commits with git format-patch.
  refresh [--patch <path>]       Legacy: write local dirty checkout diff to a patch.
  verify [--patch-only]          Verify the queue through Gradle.

Common options:
  --branch <name>                Patch branch. Default: android-patches.
  --base <ref>                   Upstream base ref. Default: recorded gitlink.
  --force                        Allow apply/new to reset a dirty checkout.

Environment:
  MAINLINE_PATCH_BRANCH          Default patch branch name.
  MAINLINE_BASE_REF              Default upstream base ref.
USAGE
}

die() {
  echo "error: $*" >&2
  exit 1
}

ensure_submodule() {
  if [[ ! -e "$submodule/.git" ]]; then
    git -C "$repo_root" submodule update --init "$submodule_rel"
  fi

  [[ -e "$submodule/.git" ]] ||
    die "Mainline Slippi submodule is missing at $submodule_rel"
}

recorded_gitlink() {
  git -C "$repo_root" ls-files -s "$submodule_rel" |
    awk '$1 == "160000" { print $2; exit }'
}

base_ref_for() {
  local explicit_base="${1:-}"
  if [[ -n "$explicit_base" ]]; then
    printf '%s\n' "$explicit_base"
    return
  fi

  if [[ -n "${MAINLINE_BASE_REF:-}" ]]; then
    printf '%s\n' "$MAINLINE_BASE_REF"
    return
  fi

  local recorded
  recorded="$(recorded_gitlink)"
  if [[ -n "$recorded" ]]; then
    printf '%s\n' "$recorded"
    return
  fi

  git -C "$submodule" rev-parse HEAD
}

submodule_status() {
  git -C "$submodule" status --porcelain --untracked-files=all
}

require_clean_submodule() {
  local reason="$1"
  local status
  status="$(submodule_status)"
  if [[ -n "$status" ]]; then
    cat >&2 <<MSG
Cannot $reason because $submodule_rel has local changes:
$status

Commit or export the intended patch changes first, or clean the submodule
after confirming those edits are represented in Source/Android/mainline-patches.
MSG
    exit 1
  fi
}

patch_files() {
  if [[ ! -d "$patch_dir" ]]; then
    return
  fi
  find "$patch_dir" -maxdepth 1 -type f \( -name '*.patch' -o -name '*.diff' \) | sort
}

use_java_17_if_available() {
  local candidate
  for candidate in \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      return
    fi
  done

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]] &&
        "$candidate/bin/java" -version 2>&1 | grep -q 'version "17\.'; then
      export JAVA_HOME="$candidate"
    fi
  fi
}

is_format_patch() {
  local patch="$1"
  sed -n '1,40p' "$patch" | grep -q '^Subject: '
}

patch_subject() {
  local patch="$1"
  local subject
  if is_format_patch "$patch"; then
    subject="$(sed -n 's/^Subject: \(\[PATCH[^]]*\] \)\{0,1\}//p' "$patch" | head -n 1)"
  else
    subject="$(basename "$patch")"
    subject="${subject%.patch}"
    subject="${subject%.diff}"
    subject="$(printf '%s' "$subject" | sed -E 's/^[0-9]+-//; s/[-_]+/ /g')"
  fi
  [[ -n "$subject" ]] || subject="android mainline patch"
  printf '%s\n' "$subject"
}

parse_branch_base_force() {
  branch="$default_patch_branch"
  base=""
  force=0
  while (($#)); do
    case "$1" in
      --branch)
        branch="${2:?missing value for --branch}"
        shift 2
        ;;
      --base)
        base="${2:?missing value for --base}"
        shift 2
        ;;
      --force)
        force=1
        shift
        ;;
      *)
        die "unknown option: $1"
        ;;
    esac
  done
}

cmd_status() {
  ensure_submodule

  local head branch_name recorded status patches
  head="$(git -C "$submodule" rev-parse --short=12 HEAD)"
  branch_name="$(git -C "$submodule" branch --show-current || true)"
  recorded="$(recorded_gitlink)"
  status="$(submodule_status)"
  patches="$(patch_files || true)"

  echo "mainline checkout: $submodule_rel"
  echo "current HEAD:      $head${branch_name:+ ($branch_name)}"
  if [[ -n "$recorded" ]]; then
    echo "recorded gitlink:  ${recorded:0:12}"
  else
    echo "recorded gitlink:  missing"
  fi
  echo "patch branch:      $default_patch_branch"
  if git -C "$submodule" show-ref --verify --quiet "refs/heads/$default_patch_branch"; then
    echo "patch branch ref:  $(git -C "$submodule" rev-parse --short=12 "$default_patch_branch")"
  else
    echo "patch branch ref:  missing"
  fi

  if [[ -n "$status" ]]; then
    echo
    echo "submodule changes:"
    echo "$status"
  else
    echo "submodule changes: clean"
  fi

  echo
  echo "patch queue:"
  if [[ -n "$patches" ]]; then
    printf '%s\n' "$patches" | sed "s#^$repo_root/##"
  else
    echo "  (empty)"
  fi
}

apply_one_patch_as_commit() {
  local patch="$1"
  local subject
  subject="$(patch_subject "$patch")"

  if git -C "$submodule" apply --reverse --check "$patch" >/dev/null 2>&1; then
    echo "already applied: $(basename "$patch")"
    return
  fi

  if is_format_patch "$patch"; then
    echo "git am --3way: $(basename "$patch")"
    if ! git -C "$submodule" am --3way --keep-cr "$patch"; then
      cat >&2 <<MSG
Failed to apply $(basename "$patch") with git am --3way.

Resolve the conflict in $submodule_rel, then run:
  git -C $submodule_rel am --continue
  scripts/mainline-patch.sh export --branch ${branch}
MSG
      exit 1
    fi
    return
  fi

  echo "git apply --3way: $(basename "$patch")"
  if ! git -C "$submodule" apply --index --3way "$patch"; then
    cat >&2 <<MSG
Failed to apply $(basename "$patch") with git apply --3way.

Resolve the patch against upstream in $submodule_rel, commit the result on
the patch branch, then run:
  scripts/mainline-patch.sh export --branch ${branch}
MSG
    exit 1
  fi

  git -C "$submodule" commit -m "$subject"
}

cmd_apply() {
  parse_branch_base_force "$@"
  ensure_submodule
  [[ "$force" -eq 1 ]] || require_clean_submodule "rebuild the mainline patch branch"

  local resolved_base
  resolved_base="$(base_ref_for "$base")"
  git -C "$submodule" rev-parse --verify --quiet "$resolved_base^{commit}" >/dev/null ||
    die "base ref '$resolved_base' is not available in $submodule_rel"

  git -C "$submodule" checkout -B "$branch" "$resolved_base"

  local found=0
  while IFS= read -r patch; do
    found=1
    apply_one_patch_as_commit "$patch"
  done < <(patch_files)

  [[ "$found" -eq 1 ]] || echo "No patches found in Source/Android/mainline-patches"
  echo "Patch branch '$branch' is ready at $(git -C "$submodule" rev-parse --short=12 HEAD)"
}

cmd_new() {
  local slug="${1:-}"
  [[ -n "$slug" ]] || die "new requires a slug, e.g. scripts/mainline-patch.sh new android-input-fix"
  shift || true
  parse_branch_base_force "$@"
  ensure_submodule

  if ! git -C "$submodule" show-ref --verify --quiet "refs/heads/$branch"; then
    local apply_args=(--branch "$branch")
    [[ -n "$base" ]] && apply_args+=(--base "$base")
    [[ "$force" -eq 1 ]] && apply_args+=(--force)
    cmd_apply "${apply_args[@]}"
  fi

  require_clean_submodule "create a new patch commit"
  git -C "$submodule" checkout "$branch"
  git -C "$submodule" commit --allow-empty -m "$(printf 'android: %s' "$slug" | sed 's/[-_][-_]*/ /g')"

  cat <<MSG
Created an empty patch commit on $branch.

Edit files in $submodule_rel, then amend that commit and export:
  git -C $submodule_rel commit --amend -a
  scripts/mainline-patch.sh export --branch $branch
MSG
}

cmd_export() {
  parse_branch_base_force "$@"
  ensure_submodule
  require_clean_submodule "export the mainline patch branch"

  local resolved_base tmp count
  resolved_base="$(base_ref_for "$base")"
  git -C "$submodule" rev-parse --verify --quiet "$resolved_base^{commit}" >/dev/null ||
    die "base ref '$resolved_base' is not available in $submodule_rel"
  git -C "$submodule" show-ref --verify --quiet "refs/heads/$branch" ||
    die "patch branch '$branch' does not exist"

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  git -C "$submodule" format-patch --zero-commit --no-signature --full-index --binary \
    --output-directory "$tmp" "$resolved_base..$branch" >/dev/null

  count="$(find "$tmp" -maxdepth 1 -type f -name '*.patch' | wc -l | tr -d ' ')"
  [[ "$count" != "0" ]] || die "no commits to export from $resolved_base..$branch"

  mkdir -p "$patch_dir"
  find "$patch_dir" -maxdepth 1 -type f \( -name '*.patch' -o -name '*.diff' \) -delete
  find "$tmp" -maxdepth 1 -type f -name '*.patch' -print0 |
    sort -z |
    while IFS= read -r -d '' patch; do
      mv "$patch" "$patch_dir/"
    done

  echo "Exported $count patches to Source/Android/mainline-patches"
}

cmd_refresh() {
  ensure_submodule
  local patch_file="$patch_dir/0001-android-embedded-input-bridge.patch"
  while (($#)); do
    case "$1" in
      --patch)
        patch_file="${2:?missing value for --patch}"
        shift 2
        ;;
      -h|--help)
        cat <<'USAGE'
Usage: scripts/mainline-patch.sh refresh [--patch <path>]

Legacy helper: write tracked dirty edits from Externals/MainlineSlippiDolphin
to one raw patch file. Prefer `scripts/mainline-patch.sh export` for new
commit-backed patch work.
USAGE
        return
        ;;
      *)
        die "unknown option: $1"
        ;;
    esac
  done

  [[ "$patch_file" == /* ]] || patch_file="$repo_root/$patch_file"

  if git -C "$submodule" diff --quiet HEAD -- .; then
    echo "No tracked local edits found in $submodule_rel"
    exit 0
  fi

  mkdir -p "$(dirname "$patch_file")"
  git -C "$submodule" diff --binary HEAD --output="$patch_file" -- .

  local untracked
  untracked="$(git -C "$submodule" ls-files --others --exclude-standard)"
  if [[ -n "$untracked" ]]; then
    echo "Warning: untracked files in $submodule_rel are not included in the patch:" >&2
    echo "$untracked" >&2
  fi

  local display_path="$patch_file"
  [[ "$display_path" == "$repo_root/"* ]] && display_path="${display_path#"$repo_root/"}"
  echo "Wrote $display_path"
}

cmd_verify() {
  local gradle_task=":app:assembleDebug"
  while (($#)); do
    case "$1" in
      --patch-only)
        gradle_task=":app:applyMainlineSlippiPatches"
        shift
        ;;
      *)
        die "unknown option: $1"
        ;;
    esac
  done

  use_java_17_if_available

  (cd "$android_dir" && ./gradlew "$gradle_task")
}

command="${1:-}"
if [[ -z "$command" || "$command" == "-h" || "$command" == "--help" ]]; then
  usage
  exit 0
fi
shift

case "$command" in
  status) cmd_status "$@" ;;
  apply) cmd_apply "$@" ;;
  new) cmd_new "$@" ;;
  export) cmd_export "$@" ;;
  refresh) cmd_refresh "$@" ;;
  verify) cmd_verify "$@" ;;
  *)
    usage >&2
    exit 2
    ;;
esac
