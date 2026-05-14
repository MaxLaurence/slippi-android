#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/refresh-mainline-patches.sh [options]

Regenerate the Android mainline patch queue from local edits in
Externals/MainlineSlippiDolphin.

Options:
  --patch <path>    Patch file to write. Default:
                    Source/Android/mainline-patches/0001-android-embedded-input-bridge.patch
  -h, --help        Show this help.
USAGE
}

repo_root="$(git rev-parse --show-toplevel)"
submodule_rel="Externals/MainlineSlippiDolphin"
submodule="$repo_root/$submodule_rel"
patch_file="$repo_root/Source/Android/mainline-patches/0001-android-embedded-input-bridge.patch"

while (($#)); do
  case "$1" in
    --patch)
      patch_file="${2:?missing value for --patch}"
      shift 2
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

if [[ "$patch_file" != /* ]]; then
  patch_file="$repo_root/$patch_file"
fi

if [[ ! -e "$submodule/.git" ]]; then
  echo "Mainline Slippi submodule is missing at $submodule_rel" >&2
  exit 1
fi

if git -C "$submodule" diff --quiet HEAD -- .; then
  echo "No tracked local edits found in $submodule_rel"
  exit 0
fi

mkdir -p "$(dirname "$patch_file")"
git -C "$submodule" diff --binary HEAD --output="$patch_file" -- .

if [[ -n "$(git -C "$submodule" ls-files --others --exclude-standard)" ]]; then
  echo "Warning: untracked files in $submodule_rel are not included in the patch:" >&2
  git -C "$submodule" ls-files --others --exclude-standard >&2
fi

display_path="$patch_file"
if [[ "$patch_file" == "$repo_root/"* ]]; then
  display_path="${patch_file#"$repo_root/"}"
fi
echo "Wrote $display_path"
