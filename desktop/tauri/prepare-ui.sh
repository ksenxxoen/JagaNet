#!/usr/bin/env bash
# Builds app/dist/ from ../ui for the Tauri window: the Go window gets one HTML file with the style
# and the script put inline (ui.go: page()); the strict CSP of the Tauri window (script-src 'self')
# needs them as files. The sources are the same, nothing is edited.
set -euo pipefail
cd "$(dirname "$0")"
out=app/dist
rm -rf "$out" && mkdir -p "$out"
cat ../ui/tokens.css ../ui/components.css ../ui/app.css > "$out/style.css"
cp ../ui/app.js "$out/app.js"
python3 - "$out" <<'PY'
import sys
html = open("../ui/index.html", encoding="utf-8").read()
assert "<style>/*CSS*/</style>" in html and "<script>/*JS*/</script>" in html
html = html.replace("<style>/*CSS*/</style>", '<link rel="stylesheet" href="style.css">', 1)
html = html.replace("<script>/*JS*/</script>", '<script src="app.js"></script>', 1)
open(sys.argv[1] + "/index.html", "w", encoding="utf-8").write(html)
PY
echo "prepared $out"
