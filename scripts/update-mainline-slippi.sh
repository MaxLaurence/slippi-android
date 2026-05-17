#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/update-mainline-slippi.sh [options]

Fetch and pin a newer Project Slippi Dolphin mainline checkout, then verify
that Source/Android/mainline-patches still applies by running assembleDebug.

Options:
  --remote <name>   Submodule remote to fetch from. Default: origin
  --branch <name>   Remote branch to fetch when --ref is not set. Default: slippi
  --ref <ref>       Specific branch, tag, or SHA to fetch and pin.
  --no-build        Update the submodule pointer without running Gradle.
  -h, --help        Show this help.

Environment overrides:
  MAINLINE_REMOTE, MAINLINE_BRANCH, MAINLINE_REF
USAGE
}

remote="${MAINLINE_REMOTE:-origin}"
branch="${MAINLINE_BRANCH:-slippi}"
target_ref="${MAINLINE_REF:-}"
no_build=0

while (($#)); do
  case "$1" in
    --remote)
      remote="${2:?missing value for --remote}"
      shift 2
      ;;
    --branch)
      branch="${2:?missing value for --branch}"
      shift 2
      ;;
    --ref)
      target_ref="${2:?missing value for --ref}"
      shift 2
      ;;
    --no-build)
      no_build=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

repo_root="$(git rev-parse --show-toplevel)"
submodule_rel="Externals/MainlineSlippiDolphin"
submodule="$repo_root/$submodule_rel"
android_dir="$repo_root/Source/Android"
patch_dir="$android_dir/mainline-patches"

if [[ ! -e "$submodule/.git" ]]; then
  git -C "$repo_root" submodule update --init "$submodule_rel"
fi

if [[ ! -e "$submodule/.git" ]]; then
  echo "Mainline Slippi submodule is missing at $submodule_rel" >&2
  exit 1
fi

if ! git -C "$submodule" diff --quiet || ! git -C "$submodule" diff --cached --quiet; then
  cat >&2 <<'MSG'
Cannot update Externals/MainlineSlippiDolphin because it has local edits.

Move intended vendor changes into Source/Android/mainline-patches first:
  scripts/mainline-patch.sh export

Or clean the submodule manually after you have saved the changes you need.
Current submodule status:
MSG
  git -C "$submodule" status --short >&2
  exit 1
fi

old_sha="$(git -C "$submodule" rev-parse --short=12 HEAD)"
fetch_ref="${target_ref:-$branch}"

echo "Fetching $remote $fetch_ref for $submodule_rel"
if ! git -C "$submodule" fetch --depth=1 "$remote" "$fetch_ref"; then
  git -C "$submodule" fetch "$remote" "$fetch_ref"
fi

new_sha="$(git -C "$submodule" rev-parse --short=12 FETCH_HEAD)"
if [[ "$old_sha" == "$new_sha" ]]; then
  echo "Mainline Slippi is already at $new_sha"
else
  git -C "$submodule" checkout --detach FETCH_HEAD
  echo "Updated Mainline Slippi: $old_sha -> $new_sha"
fi

if [[ ! -d "$patch_dir" ]]; then
  echo "Warning: no patch queue found at $patch_dir" >&2
fi

rm -rf "$android_dir/app/build/mainlineSlippi"

if [[ "$no_build" -eq 0 ]]; then
  java17_selected=0
  for candidate in \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      java17_selected=1
      break
    fi
  done
  if [[ "$java17_selected" -eq 0 ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]] &&
        "$candidate/bin/java" -version 2>&1 | grep -q 'version "17\.'; then
      export JAVA_HOME="$candidate"
    fi
  fi

  echo "Verifying patch queue and APK build"
  (cd "$android_dir" && ./gradlew :app:assembleDebug)
else
  echo "Skipped Gradle verification. Run this when ready:"
  echo "  ./Source/Android/gradlew -p Source/Android :app:assembleDebug"
fi

cat <<MSG
Done.

Review the submodule bump and any patch conflicts before committing:
  git diff --submodule -- .gitmodules $submodule_rel Source/Android/mainline-patches
MSG
