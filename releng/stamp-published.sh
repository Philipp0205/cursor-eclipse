#!/bin/sh
# Writes the "last published" line into the site pages.
#
# Only the region between the published:begin and published:end markers is
# rewritten, so the same page can be stamped on every publish without the
# previous stamp piling up or the surrounding page drifting.
set -eu

if [ "$#" -lt 6 ]; then
	echo "usage: stamp-published.sh <iso-timestamp> <version> <commit-sha> <repo-slug> <run-id> <file>..." >&2
	exit 2
fi

published_at=$1
version=$2
commit=$3
repository=$4
run=$5
shift 5

readable=$(printf '%s' "$published_at" | tr 'T' ' ' | sed 's/Z$/ UTC/')
short_commit=$(printf '%s' "$commit" | cut -c1-7)

PUBLISHED_STAMP=$(cat <<HTML
<p class="published">Last published <time datetime="$published_at">$readable</time>
from commit <a href="https://github.com/$repository/commit/$commit"><code>$short_commit</code></a>
(<a href="https://github.com/$repository/actions/runs/$run">build log</a>),
feature version <code>$version</code>.</p>
HTML
)
export PUBLISHED_STAMP

for file in "$@"; do
	if ! grep -q 'published:begin' "$file"; then
		echo "stamp-published.sh: $file has no published:begin marker" >&2
		exit 1
	fi
	awk '
		/published:begin/ { print; print ENVIRON["PUBLISHED_STAMP"]; inside = 1; next }
		/published:end/ { inside = 0 }
		!inside { print }
	' "$file" >"$file.stamped"
	mv "$file.stamped" "$file"
done
