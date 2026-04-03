#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <registry-prefix> <tag> [platforms]" >&2
  echo "Example: $0 ghcr.io/eternity1824 v7 linux/amd64,linux/arm64" >&2
  exit 1
fi

registry_prefix="$1"
tag="$2"
platforms="${3:-linux/amd64,linux/arm64}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"

server_image="${registry_prefix}/chatflow-server-v2:${tag}"
consumer_image="${registry_prefix}/chatflow-consumer-v3:${tag}"

cd "${repo_root}"

docker buildx build \
  --platform "${platforms}" \
  -f deployment/docker/server-v2/Dockerfile \
  -t "${server_image}" \
  --push \
  .

docker buildx build \
  --platform "${platforms}" \
  -f deployment/docker/consumer-v3/Dockerfile \
  -t "${consumer_image}" \
  --push \
  .

cat <<EOF
Published:
  ${server_image}
  ${consumer_image}

If you use GHCR, make sure both packages are public before restarting EC2.
EOF
