#!/bin/bash
# Builds a shader pack with Voxy support for the CI smoke test:
#   ci/build-pack.sh <id> <output.zip>
# shaderpack/packs/<id>/source names the original pack:
#   repo <dir>                    a pack kept in shaderpack/<dir>
#   modrinth <sha1> [fallback]    the exact original file, looked up on Modrinth by its SHA-1
#     fallback github:<owner>/<repo>@<rev>[:crlf]   used when Modrinth does not have it
# and shaderpack/packs/<id>/voxy.patch (optional) holds the Voxy changes, applied on top.
set -e
id=$1; out=$(realpath -m "$2")
dir=shaderpack/packs/$id
work=$(mktemp -d)
read -r kind arg fallback < "$dir/source"
case "$kind" in
  repo) cp -r "shaderpack/$arg/." "$work/" ;;
  modrinth)
    url=$(curl -s "https://api.modrinth.com/v2/version_file/$arg?algorithm=sha1" | jq -r '.files[] | select(.hashes.sha1 == "'"$arg"'") | .url' | head -1)
    if [ -n "$url" ] && [ "$url" != "null" ]; then
      echo "[$id] original from Modrinth: $url"
      curl -sL -o "$work.zip" "$url"
      echo "$arg  $work.zip" | sha1sum -c -
      (cd "$work" && unzip -q "$work.zip")
    elif [[ "$fallback" == modrinth-search:* ]]; then
      # The exact file is not on Modrinth: take the release with that version number from a project found by name
      spec=${fallback#modrinth-search:}; query=${spec%%@*}; ver=${spec#*@}
      for slug in $(curl -s -G "https://api.modrinth.com/v2/search" --data-urlencode "query=$query" --data-urlencode 'facets=[["project_type:shader"]]' | jq -r '.hits[].slug'); do
        url=$(curl -s "https://api.modrinth.com/v2/project/$slug/version" | jq -r --arg v "$ver" '[.[] | select((.version_number|contains($v)) or (.name|contains($v)))][0].files[0].url // empty')
        echo "[$id] Modrinth project $slug: ${url:-no $ver}"
        [ -n "$url" ] && break
      done
      [ -n "$url" ] || { echo "[$id] $query $ver not found on Modrinth"; exit 2; }
      curl -sL -o "$work.zip" "$url"; sha1sum "$work.zip"
      (cd "$work" && unzip -q "$work.zip")
    elif [[ "$fallback" == github:* ]]; then
      spec=${fallback#github:}; repo=${spec%@*}; rev=${spec#*@}; crlf=; [[ "$rev" == *:crlf ]] && { crlf=1; rev=${rev%:crlf}; }
      echo "[$id] original from GitHub $repo@$rev"
      git clone -q "https://github.com/$repo.git" "$work.git" && git -C "$work.git" archive "$rev" | tar -x -C "$work"
      [ -n "$crlf" ] && find "$work" -type f \( -name '*.glsl' -o -name '*.fsh' -o -name '*.vsh' -o -name '*.csh' -o -name '*.properties' -o -name '*.lang' -o -name '*.txt' \) -exec sed -i 's/\r*$/\r/' {} +
    else
      echo "[$id] original pack not found on Modrinth (sha1 $arg)"; exit 2
    fi ;;
esac
if [ -f "$dir/voxy.patch" ]; then (cd "$work" && patch -s -p1 --binary < "$OLDPWD/$dir/voxy.patch"); fi
# Optional sed expression over the pack's Voxy programs (CI diagnostics)
if [ -n "$DIAG" ]; then find "$work" -path '*voxy*' -name '*.glsl' -exec sed -i "$DIAG" {} +; grep -rn "PackLightmaps = \|sampledColour.rgb" "$work" | grep -i voxy | head; fi
# Mesa's llvmpipe allows only 32 KiB of compute shared memory; Photon's sky-SH compute pass uses 36 KiB
# (fine on real GPUs), so CI builds leave that one pass out
rm -f "$work"/shaders/world*/deferred4_a.csh
[ "$id" = photon ] || [ "$id" = photon-opt ] || true
(cd "$work" && rm -f "$out" && zip -qr "$out" .)
echo "[$id] built $out"
