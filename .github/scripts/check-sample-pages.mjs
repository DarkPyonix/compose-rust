// Usage: PLAYWRIGHT_DIR=<dir holding node_modules/playwright> [CHROMIUM_EXECUTABLE=<path>] \
//        node .github/scripts/check-sample-pages.mjs <pages-directory> <sample>...
//
// Opens every sample's page in Chromium and fails if one of them does not draw.
//
// A page that does not draw is what a reader of the web build sees when a sample panics
// at startup: the renderer's module loads, the Host's module aborts with `unreachable`,
// and the page keeps its loading spinner forever. The build succeeds, the archive is
// uploaded, and nothing says anything is wrong. Two samples shipped that way.
//
// For each sample and each of two window sizes (a phone and a desktop), the page fails if
//   - it throws: an uncaught exception or an unhandled rejection (a Rust panic arrives
//     as `RuntimeError: unreachable`);
//   - it writes an error to the console;
//   - a request it makes fails or answers with an error status (a missing page is this);
//   - it has not drawn within the time allowed: the spinner is still there, there is no
//     canvas, or a screenshot of the page is a single flat colour.
//
// The samples to open are named on the command line rather than read from the directory,
// so a sample whose page was never built is a failure instead of something nobody opens.
//
// The pages are served by this script itself, so the check needs nothing but Node and
// the pinned Playwright. Wasm has to be served as `application/wasm` or the page's
// `compileStreaming` refuses it.

import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';

const [pagesArgument, ...samples] = process.argv.slice(2);
if (!pagesArgument || samples.length === 0) {
    console.error('usage: check-sample-pages.mjs <pages-directory> <sample>...');
    process.exit(2);
}
const pages = resolve(pagesArgument);
const playwrightDir = process.env.PLAYWRIGHT_DIR;
if (!playwrightDir) {
    console.error('PLAYWRIGHT_DIR is not set: it names the directory Playwright was installed into');
    process.exit(2);
}
const { chromium } = createRequire(join(resolve(playwrightDir), 'package.json'))('playwright');

const SIZES = [
    [420, 860],
    [1280, 800],
];
// How long a page has to draw its first screen. A cold page compiles three wasm modules
// on a software GPU, which takes a few seconds on a CI runner; a page that has not drawn
// in this long is not going to.
const DRAW_TIMEOUT_MS = 60_000;
// After the first screen is up, how long to keep listening. A panic in the first
// composition can land after the renderer's own first frame.
const SETTLE_MS = 2_000;
// A drawn screen has text, a surface and its edges, so it has many colours. The loading
// page is white with a grey spinner.
const MIN_COLOURS = 8;

const TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript',
    '.mjs': 'text/javascript',
    '.css': 'text/css',
    '.json': 'application/json',
    '.wasm': 'application/wasm',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.ttf': 'font/ttf',
    '.otf': 'font/otf',
    '.woff2': 'font/woff2',
    '.xml': 'application/xml',
    '.cvr': 'application/octet-stream',
};

const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost');
    // Browsers ask for one whether the page names one or not. It is not part of a page.
    if (url.pathname === '/favicon.ico') {
        response.writeHead(204).end();
        return;
    }
    let path = normalize(join(pages, decodeURIComponent(url.pathname)));
    if (path !== pages && !path.startsWith(pages + sep)) {
        response.writeHead(403).end();
        return;
    }
    try {
        if ((await stat(path)).isDirectory()) path = join(path, 'index.html');
        const body = await readFile(path);
        response.writeHead(200, {
            'Content-Type': TYPES[extname(path)] ?? 'application/octet-stream',
        });
        response.end(request.method === 'HEAD' ? undefined : body);
    } catch {
        response.writeHead(404).end();
    }
});
await new Promise((ready) => server.listen(0, '127.0.0.1', ready));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({
    headless: true,
    // CI uses the browser the pinned Playwright installs. A local run can point at another.
    executablePath: process.env.CHROMIUM_EXECUTABLE || undefined,
    // CI runners have no GPU. Skia's WebGL backend runs on SwiftShader instead.
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});

/** Whether the page has a canvas, looking inside shadow roots where Compose puts it. */
function hasCanvas() {
    const visit = (root) => {
        if (root.querySelector('canvas')) return true;
        for (const element of root.querySelectorAll('*')) {
            if (element.shadowRoot && visit(element.shadowRoot)) return true;
        }
        return false;
    };
    return visit(document);
}

/** How many colours a screenshot holds, counted over a sample of its pixels. */
async function colours(base64) {
    const image = new Image();
    image.src = 'data:image/png;base64,' + base64;
    await image.decode();
    const canvas = new OffscreenCanvas(image.width, image.height);
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const data = context.getImageData(0, 0, image.width, image.height).data;
    const seen = new Set();
    for (let index = 0; index < data.length; index += 4 * 97) {
        seen.add((data[index] << 16) | (data[index + 1] << 8) | data[index + 2]);
    }
    return seen.size;
}

/** Whether an editable DOM element (possibly inside a shadow root) currently has focus. */
function hasFocusedTextInput() {
    let active = document.activeElement;
    while (active && active.shadowRoot && active.shadowRoot.activeElement) {
        active = active.shadowRoot.activeElement;
    }
    if (!active) return false;
    const tag = active.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || active.isContentEditable === true;
}

/** Reads a UTF-8 key from a compose-rust IndexedDB store, or null if absent. */
async function readIndexedDbText({ store, key }) {
    return await new Promise((resolveValue, rejectValue) => {
        const open = indexedDB.open('compose-rust/' + store, 1);
        open.onupgradeneeded = () => {
            if (!open.result.objectStoreNames.contains('values')) {
                open.result.createObjectStore('values');
            }
        };
        open.onerror = () => rejectValue(open.error || new Error('indexedDB open failed'));
        open.onsuccess = () => {
            const db = open.result;
            if (!db.objectStoreNames.contains('values')) {
                db.close();
                resolveValue(null);
                return;
            }
            const tx = db.transaction('values', 'readonly');
            const req = tx.objectStore('values').get(key);
            req.onerror = () => {
                db.close();
                rejectValue(req.error || new Error('indexedDB get failed'));
            };
            req.onsuccess = () => {
                const value = req.result;
                db.close();
                if (value === undefined || value === null) {
                    resolveValue(null);
                    return;
                }
                const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
                resolveValue(new TextDecoder().decode(bytes));
            };
        };
    });
}

/** Attaches page error, console error, failed request, and HTTP >= 400 listeners. */
function attachProblemListeners(page, problems) {
    page.on('pageerror', (error) => problems.push(`uncaught: ${String(error.stack || error)}`));
    page.on('console', (message) => {
        if (message.type() === 'error') problems.push(`console error: ${message.text()}`);
    });
    page.on('requestfailed', (request) =>
        problems.push(`request failed: ${request.url()} (${request.failure()?.errorText})`),
    );
    page.on('response', (response) => {
        if (response.status() >= 400) {
            problems.push(`HTTP ${response.status()}: ${response.url()}`);
        }
    });
}

/** Waits until the page has cleared its spinner, mounted a canvas, and drawn enough colours. */
async function waitForDraw(page, problems, started) {
    let drawn = false;
    let last = 'the page did not load';
    while (Date.now() - started < DRAW_TIMEOUT_MS && problems.length === 0) {
        const spinner = await page.evaluate(() => !!document.querySelector('body > svg'));
        const canvas = await page.evaluate(hasCanvas);
        if (spinner || !canvas) {
            last = spinner ? 'the loading spinner is still up' : 'there is no canvas';
        } else {
            const shot = (await page.screenshot()).toString('base64');
            const count = await page.evaluate(colours, shot);
            if (count >= MIN_COLOURS) {
                drawn = true;
                break;
            }
            last = `the page is blank (${count} colours)`;
        }
        await page.waitForTimeout(500);
    }
    return { drawn, last };
}

/**
 * Every sample's page draws its first screen without a page error. Returns the pages
 * that did not, by name and size.
 */
async function fr22_web_every_sample_draws_without_a_page_error() {
    const failures = [];
    for (const sample of samples) {
        for (const [width, height] of SIZES) {
            const label = `${sample} at ${width}x${height}`;
            // A locale a real browser reports. Without one, Chromium takes its locale from
            // the runner's environment, and Compose's Locale.current throws
            // "RangeError: Incorrect locale information provided" on what it gets, before
            // the first frame, so every page failed the same way.
            const context = await browser.newContext({ viewport: { width, height }, locale: 'en-US' });
            const page = await context.newPage();
            const problems = [];
            attachProblemListeners(page, problems);

            const started = Date.now();
            let drawn = false;
            let last = 'the page did not load';
            try {
                await page.goto(`${base}/${sample}/`, { waitUntil: 'load', timeout: DRAW_TIMEOUT_MS });
                ({ drawn, last } = await waitForDraw(page, problems, started));
                await page.waitForTimeout(SETTLE_MS);
            } catch (error) {
                problems.push(`navigation: ${error.message}`);
            }
            const seconds = ((Date.now() - started) / 1000).toFixed(1);
            if (!drawn) problems.push(`no first screen after ${seconds}s: ${last}`);
            if (problems.length === 0) {
                console.log(`ok    ${label} (${seconds}s)`);
            } else {
                console.log(`FAIL  ${label}`);
                for (const problem of problems) {
                    console.log(`        ${problem.split('\n').slice(0, 12).join('\n        ')}`);
                }
                failures.push(label);
            }
            await context.close();
        }
    }
    return failures;
}

/**
 * Adds a task in the todo web sample, confirms it is written to IndexedDB storage, reloads
 * the page, and verifies the saved task survives the reload and changes the drawn screen.
 */
async function fr79_todo_list_survives_a_reload_on_the_web() {
    const label = 'todo reload persistence at 1280x800';
    const store = 'dev.darkpyonix.composerust.samples.todo';
    const key = 'tasks.tsv';
    const markerTask = 'Persist across reload';
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-US' });
    const page = await context.newPage();
    const problems = [];
    attachProblemListeners(page, problems);

    const started = Date.now();
    try {
        await page.goto(`${base}/todo/`, { waitUntil: 'load', timeout: DRAW_TIMEOUT_MS });
        const first = await waitForDraw(page, problems, started);
        await page.waitForTimeout(SETTLE_MS);
        if (!first.drawn) {
            problems.push(`no first screen before adding task: ${first.last}`);
        } else if (problems.length === 0) {
            const beforeAdd = (await page.screenshot()).toString('base64');

            // Focus the composer text field: try keyboard Tab traversal first, then click
            // the composer area on the 1280x800 desktop layout if focus has not reached an
            // editable element.
            let focused = false;
            for (let step = 0; step < 20; step += 1) {
                await page.keyboard.press('Tab');
                await page.waitForTimeout(100);
                if (await page.evaluate(hasFocusedTextInput)) {
                    focused = true;
                    break;
                }
            }
            if (!focused) {
                for (const [x, y] of [
                    [560, 120],
                    [640, 108],
                    [520, 140],
                ]) {
                    await page.mouse.click(x, y);
                    await page.waitForTimeout(250);
                    if (await page.evaluate(hasFocusedTextInput)) {
                        focused = true;
                        break;
                    }
                }
            }
            if (!focused) {
                problems.push('could not focus the todo composer text field');
            } else {
                await page.keyboard.type(markerTask, { delay: 20 });
                await page.keyboard.press('Enter');

                const saveDeadline = Date.now() + 10_000;
                let savedText = null;
                while (Date.now() < saveDeadline && problems.length === 0) {
                    savedText = await page.evaluate(readIndexedDbText, { store, key });
                    if (savedText && savedText.includes(markerTask)) break;
                    await page.waitForTimeout(250);
                }
                if (!savedText || !savedText.includes(markerTask)) {
                    problems.push(
                        `added task was not saved to IndexedDB (${store}/${key}): ${JSON.stringify(savedText)}`,
                    );
                } else {
                    const reloadStarted = Date.now();
                    await page.reload({ waitUntil: 'load', timeout: DRAW_TIMEOUT_MS });
                    const second = await waitForDraw(page, problems, reloadStarted);
                    await page.waitForTimeout(SETTLE_MS);
                    if (!second.drawn) {
                        problems.push(`no screen after reload: ${second.last}`);
                    } else {
                        const afterReloadText = await page.evaluate(readIndexedDbText, { store, key });
                        if (!afterReloadText || !afterReloadText.includes(markerTask)) {
                            problems.push(
                                `saved task vanished from IndexedDB after reload: ${JSON.stringify(afterReloadText)}`,
                            );
                        }
                        const afterReload = (await page.screenshot()).toString('base64');
                        if (afterReload === beforeAdd) {
                            problems.push('reloaded todo screen is identical to the empty screen before adding a task');
                        }
                    }
                }
            }
        }
    } catch (error) {
        problems.push(`persistence check: ${error.message}`);
    }

    const seconds = ((Date.now() - started) / 1000).toFixed(1);
    const failures = [];
    if (problems.length === 0) {
        console.log(`ok    ${label} (${seconds}s)`);
    } else {
        console.log(`FAIL  ${label}`);
        for (const problem of problems) {
            console.log(`        ${problem.split('\n').slice(0, 12).join('\n        ')}`);
        }
        failures.push(label);
    }
    await context.close();
    return failures;
}

const failures = await fr22_web_every_sample_draws_without_a_page_error();
if (samples.includes('todo')) {
    failures.push(...(await fr79_todo_list_survives_a_reload_on_the_web()));
}
await browser.close();
server.close();

if (failures.length > 0) {
    console.log(`\n${failures.length} check(s) failed: ${failures.join(', ')}`);
    process.exit(1);
}
console.log(`\nevery page drew its first screen: ${samples.join(' ')}`);
