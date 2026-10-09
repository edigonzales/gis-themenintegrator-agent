#!/bin/sh
# Keep the same file ordering for build, up, exec and down. No edits to gretljobs.
set -eu
native_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
gretl_repo="$native_dir/../../../gretljobs"
oereb=0
processing=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --gretl-repo)
      [ "$#" -gt 1 ] || { printf '%s\n' '--gretl-repo PATH required' >&2; exit 2; }
      gretl_repo="$2"
      shift 2 ;;
    --oereb) oereb=1; shift ;;
    --processing) processing=1; shift ;;
    *) break ;;
  esac
done
gretl_repo=$(CDPATH= cd -- "$gretl_repo" && pwd)
export GIS_POSTGIS_BUILD_CONTEXT="$native_dir"
# Prepend arguments backwards so optional original files precede all ARM overlays.
if [ "$processing" = 1 ]; then set -- -f "$native_dir/compose.processing.arm64.yaml" "$@"; fi
if [ "$oereb" = 1 ]; then set -- -f "$native_dir/compose.oereb_v2.arm64.yaml" "$@"; fi
set -- -f "$native_dir/compose.arm64.yaml" "$@"
if [ "$processing" = 1 ]; then set -- -f "$gretl_repo/compose.processing.yaml" "$@"; fi
if [ "$oereb" = 1 ]; then set -- -f "$gretl_repo/compose.oereb_v2.yaml" "$@"; fi
exec docker compose --project-directory "$gretl_repo" --project-name gretljobs-arm64 \
  -f "$gretl_repo/compose.yaml" "$@"
