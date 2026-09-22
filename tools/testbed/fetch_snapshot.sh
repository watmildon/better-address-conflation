#!/usr/bin/env bash
# Fetch a "known good" OSM snapshot (buildings, addresses, highways) for a bbox.
#
# Usage: tools/testbed/fetch_snapshot.sh <south,west,north,east> <out.osm>
#
# Reads the Overpass endpoint from ./.overpassurl (repo override) or ~/.overpassurl.
# The endpoint value is never printed. To use the public instance instead, set
# OVERPASS_URL=https://overpass-api.de/api/interpreter (expect rate limiting).
set -euo pipefail

bbox="${1:?bbox south,west,north,east}"
out="${2:?output .osm path}"

urlfile="${HOME}/.overpassurl"
[ -f ./.overpassurl ] && urlfile=./.overpassurl
endpoint="${OVERPASS_URL:-$(cat "$urlfile")}"
if [ -z "$endpoint" ] || [[ "$endpoint" == *MYPRIVATEURL* ]]; then
  echo "Overpass endpoint not configured (see ~/.overpassurl)" >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"

query="[out:xml][timeout:180][bbox:${bbox}];
(
  way[\"building\"];
  relation[\"building\"];
  node[\"addr:housenumber\"];
  way[\"addr:housenumber\"][!\"building\"];
  way[\"highway\"];
);
out meta;
>;
out meta;"

# stderr is scrubbed so a failed request cannot echo the endpoint.
curl -sS --fail --data-urlencode "data=${query}" "$endpoint" -o "$out" \
  2>&1 | sed "s|${endpoint}|<OVERPASS-URL>|g"
echo "wrote $out ($(wc -c < "$out" | tr -d ' ') bytes)"
