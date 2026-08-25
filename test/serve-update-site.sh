#!/usr/bin/env bash
#
# Builds the plugin and serves the update site on localhost so it can be added
# to Eclipse as a normal HTTP update site.
#
# Usage: test/serve-update-site.sh [port]

set -euo pipefail

PORT="${1:-8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SITE="${SITE_DIR:-/tmp/cursor-eclipse-site}"

VERSION="$(sed -n 's/.*<version>\(.*\)-SNAPSHOT<\/version>.*/\1/p' "$ROOT/pom.xml" | head -1)"
if [ -z "$VERSION" ]; then
	VERSION="0.0.0"
fi

echo "Building $VERSION ..."
(cd "$ROOT" && mvn -B -q verify)

"$ROOT/releng/publish-site.sh" "$VERSION" \
	"$ROOT/releng/com.cursor.eclipse.repository/target/repository" "$SITE"

cat <<EOF

Update site built at $SITE

Easiest: Help > Install New Software... > Add... > Local... and pick

    $SITE

Or use it as a URL:

    http://localhost:$PORT/

Eclipse redirects http:// update sites to https:// (CVE-2021-41033), so for the
URL form add this line to eclipse.ini and restart Eclipse:

    -Dp2.httpRule=allow

Press Ctrl+C to stop serving.

EOF

cd "$SITE"
exec python3 -m http.server "$PORT" --bind 127.0.0.1
