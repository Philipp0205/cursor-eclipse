#!/usr/bin/env bash
#
# Builds the plugin and serves the repository root so the update site is
# reachable at the same /p2/ path GitHub Pages publishes.
#
# Usage: test/serve-update-site.sh [port]

set -euo pipefail

PORT="${1:-8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "Building ..."
(cd "$ROOT" && mvn -B -q clean verify -Ppublish-site)

rm -rf "$ROOT/p2"
mkdir "$ROOT/p2"
cp -a "$ROOT/releng/com.cursor.eclipse.repository/target/merged-site/." "$ROOT/p2/"

cat <<EOF

Update site built at $ROOT/p2

Easiest: Help > Install New Software... > Add... > Local... and pick

    $ROOT/p2

Or use it as a URL:

    http://localhost:$PORT/p2/

Eclipse redirects http:// update sites to https:// (CVE-2021-41033), so for the
URL form add this line to eclipse.ini and restart Eclipse:

    -Dp2.httpRule=allow

Press Ctrl+C to stop serving.

EOF

cd "$ROOT"
exec python3 -m http.server "$PORT" --bind 127.0.0.1
