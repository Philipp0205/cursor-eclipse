#!/usr/bin/env bash
#
# Adds a built p2 repository to a composite update site.
#
# A composite site lets one stable URL keep serving every release: each build is
# copied to releases/<version>/ and the generated composite metadata at the root
# points at all of them, so users never have to change the URL they added to
# Eclipse.
#
# Usage: publish-site.sh <version> <built-repository-dir> <site-dir>

set -euo pipefail

if [ "$#" -ne 3 ]; then
	echo "usage: $(basename "$0") <version> <built-repository-dir> <site-dir>" >&2
	exit 2
fi

VERSION="$1"
BUILT_REPO="$2"
SITE="$3"

REPOSITORY_NAME="Cursor for Eclipse"

if [ ! -f "$BUILT_REPO/artifacts.jar" ] && [ ! -f "$BUILT_REPO/artifacts.xml.xz" ]; then
	echo "error: $BUILT_REPO does not look like a p2 repository" >&2
	exit 1
fi

mkdir -p "$SITE/releases/$VERSION"
rm -rf "${SITE:?}/releases/$VERSION"
cp -r "$BUILT_REPO" "$SITE/releases/$VERSION"

# Newest first, so Eclipse resolves the latest version without extra hints.
mapfile -t VERSIONS < <(find "$SITE/releases" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -Vr)
COUNT="${#VERSIONS[@]}"
TIMESTAMP="$(date +%s)000"

write_composite() {
	local file="$1" type="$2" processing="$3"
	{
		printf '<?xml version="1.0" encoding="UTF-8"?>\n'
		printf '<?%s version="1.0.0"?>\n' "$processing"
		printf '<repository name="%s" type="%s" version="1.0.0">\n' "$REPOSITORY_NAME" "$type"
		printf '  <properties size="1">\n'
		printf '    <property name="p2.timestamp" value="%s"/>\n' "$TIMESTAMP"
		printf '  </properties>\n'
		printf '  <children size="%s">\n' "$COUNT"
		local version
		for version in "${VERSIONS[@]}"; do
			printf '    <child location="releases/%s"/>\n' "$version"
		done
		printf '  </children>\n'
		printf '</repository>\n'
	} >"$file"
}

write_composite "$SITE/compositeArtifacts.xml" \
	"org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository" \
	"compositeArtifactRepository"
write_composite "$SITE/compositeContent.xml" \
	"org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository" \
	"compositeMetadataRepository"

cat >"$SITE/p2.index" <<'EOF'
version=1
metadata.repository.factory.order=compositeContent.xml,\!
artifact.repository.factory.order=compositeArtifacts.xml,\!
EOF

LATEST="${VERSIONS[0]}"
{
	cat <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$REPOSITORY_NAME update site</title>
<style>
  body { max-width: 46rem; margin: 3rem auto; padding: 0 1.2rem;
         font: 16px/1.6 system-ui, sans-serif; color: #24292f; }
  code, pre { background: #f6f8fa; border-radius: 6px; }
  code { padding: 2px 6px; }
  pre { padding: 12px; overflow-x: auto; }
  a { color: #0969da; }
  ol { padding-left: 1.2rem; }
  @media (prefers-color-scheme: dark) {
    body { background: #1e1f22; color: #e6edf3; }
    code, pre { background: #161b22; }
    a { color: #58a6ff; }
  }
</style>
</head>
<body>
<h1>$REPOSITORY_NAME</h1>
<p>An Eclipse update site for the Cursor agent chat plugin. Latest version:
<strong>$LATEST</strong>.</p>

<h2>Install</h2>
<ol>
  <li>In Eclipse open <strong>Help &rarr; Install New Software&hellip;</strong></li>
  <li>Paste this URL into <em>Work with</em> and press Enter:
    <pre>https://philipp0205.github.io/cursor-eclipse/</pre></li>
  <li>Select <strong>Cursor</strong>, then finish the wizard and restart Eclipse.</li>
  <li>Open <strong>Window &rarr; Show View &rarr; Other&hellip; &rarr; Cursor &rarr; Cursor</strong>.</li>
</ol>

<h2>Requirements</h2>
<ul>
  <li>Eclipse 2025-09 (4.37) or later on Java 21</li>
  <li>The <a href="https://cursor.com/docs/cli/using">Cursor CLI</a> on <code>PATH</code>, signed in with <code>agent login</code></li>
  <li>Linux: WebKitGTK (for example <code>libwebkit2gtk-4.1-0</code>) for the chat view</li>
</ul>

<h2>Versions</h2>
<ul>
EOF
	for version in "${VERSIONS[@]}"; do
		printf '  <li><a href="releases/%s/">%s</a></li>\n' "$version" "$version"
	done
	cat <<'EOF'
</ul>
<p><a href="https://github.com/Philipp0205/cursor-eclipse">Source on GitHub</a></p>
</body>
</html>
EOF
} >"$SITE/index.html"

# GitHub Pages would otherwise strip directories such as plugins/ that Jekyll ignores.
touch "$SITE/.nojekyll"

echo "Published $VERSION to $SITE"
echo "Composite children: ${VERSIONS[*]}"
