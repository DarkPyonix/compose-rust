#!/usr/bin/env bash
# Draws the browser page and photographs it, without a display and without a native build.
# Usage: ./screenshot.sh [--port N] [--width W] [--height H] [--out DIR] [--no-host]
#
# Produces two images in --out (default /tmp):
#   dxc-web.png          the page as it comes up, which is the tree the Host sent
#   dxc-web-clicked.png  after typing into the field and clicking the button, which is the
#                        Rust handler's state change on screen
#
# Everything it needs is already on the machine after one `./kotlin test -p wasmJs`: that
# run provisions Playwright and a browser for the Kotlin test harness, and the toolchain
# brings its own Node. This borrows all three rather than adding a dependency. If any of
# them is missing it says which and stops.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
module_dir="$(cd "$script_dir/.." && pwd)"
renderer_root="$(cd "$module_dir/.." && pwd)"

port=8790
width=1200
height=800
out=/tmp
with_host=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port) port="$2"; shift 2 ;;
        --width) width="$2"; shift 2 ;;
        --height) height="$2"; shift 2 ;;
        --out) out="$2"; shift 2 ;;
        --no-host) with_host=0; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

mkdir -p "$out"

playwright_project="$renderer_root/build/tasks/_web_testWasmJsDebug/playwright-project"
if [[ ! -d "$playwright_project/node_modules/playwright" ]]; then
    echo "no Playwright at $playwright_project." >&2
    echo "Run './kotlin test -p wasmJs' once: the test harness provisions it." >&2
    exit 1
fi

# `|| true` because `head -1` closes the pipe and `find` dies of SIGPIPE, which under
# pipefail is the status of the whole pipeline.
node="$(find "$HOME/Library/Caches/JetBrains/Kotlin/extract.cache" \
    "$HOME/.cache/JetBrains/Kotlin/extract.cache" \
    -type f -path '*/bin/node' 2>/dev/null | head -1 || true)"
if [[ -z "$node" ]]; then
    node="$(command -v node || true)"
fi
if [[ -z "$node" ]]; then
    echo "no Node. The Kotlin toolchain brings one; run './kotlin test -p wasmJs' once." >&2
    exit 1
fi

# The headless shell if Playwright installed one, otherwise the full browser. Which of the
# two is present depends on the Playwright version, and only one of them usually is.
browser="$(find "$HOME/Library/Caches/ms-playwright" -type f \
    \( -name 'chrome-headless-shell' -o -name 'Google Chrome for Testing' -o -name chrome \) \
    2>/dev/null | head -1 || true)"
if [[ -z "$browser" ]]; then
    echo "no Playwright browser under ~/Library/Caches/ms-playwright." >&2
    echo "Run './kotlin test -p wasmJs' once: the test harness downloads one." >&2
    exit 1
fi

serve_args=(--port "$port")
if [[ $with_host -eq 0 ]]; then
    serve_args+=(--no-host)
fi
"$script_dir/serve.sh" "${serve_args[@]}" >"$out/dxc-web-serve.log" 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT

echo "waiting for http://127.0.0.1:$port/"
for _ in $(seq 1 180); do
    if curl -s -o /dev/null "http://127.0.0.1:$port/"; then
        break
    fi
    sleep 1
done
if ! curl -s -o /dev/null "http://127.0.0.1:$port/"; then
    echo "the page never came up; see $out/dxc-web-serve.log" >&2
    exit 1
fi

cat > "$playwright_project/dxc-screenshot.mjs" <<'EOF'
import { chromium } from 'playwright';

const [url, directory, width, height] = process.argv.slice(2);
const browser = await chromium.launch({ executablePath: process.env.DXC_BROWSER });
const page = await browser.newPage({
  viewport: { width: Number(width), height: Number(height) },
});
page.on('console', (message) => console.log(`[${message.type()}] ${message.text()}`));
page.on('pageerror', (error) => console.log(`[pageerror] ${error}`));

await page.goto(url, { waitUntil: 'networkidle' });
// Compose draws into a canvas, so there is no element to wait for. This waits for the
// first frames to have gone out instead.
await page.waitForTimeout(4000);
await page.screenshot({ path: `${directory}/dxc-web.png` });

// The page is one canvas, so the interaction is by coordinate. The demo tree opens with a
// title, a field and a button, in that order, at the top left.
await page.mouse.click(60, 28);
await page.waitForTimeout(400);
await page.keyboard.type('hello from the browser', { delay: 20 });
await page.waitForTimeout(500);
await page.mouse.click(27, 64);
await page.waitForTimeout(1200);
await page.screenshot({ path: `${directory}/dxc-web-clicked.png` });
console.log(`wrote ${directory}/dxc-web.png and ${directory}/dxc-web-clicked.png`);
await browser.close();
EOF

cd "$playwright_project"
DXC_BROWSER="$browser" "$node" dxc-screenshot.mjs \
    "http://127.0.0.1:$port/" "$out" "$width" "$height"
