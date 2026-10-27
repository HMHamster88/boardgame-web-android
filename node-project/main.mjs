/**
 * Entry point executed by Node.js Mobile inside the Android app.
 *
 * Responsibilities:
 *  - point the launcher at the app's private storage
 *  - initialise the SQLite compatibility layer used by the core module
 *  - run the regular boardgame-web-ts launcher bundle
 */
import nodeCrypto from 'node:crypto';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectDirectory = path.dirname(fileURLToPath(import.meta.url));
const port = process.env.BOARDGAME_PORT ?? '8000';
const appDataDirectory = process.env.BOARDGAME_APP_DATA_DIR
    || path.join(os.homedir(), 'boardgame-web-android-data');
const dataDirectory = path.join(appDataDirectory, 'boardgame-web-ts');

fs.mkdirSync(dataDirectory, { recursive: true });
process.chdir(projectDirectory);

process.env.NODE_ENV ??= 'production';
process.env.DATA_DIR = dataDirectory;
process.env.PORT = port;
process.env.CHECK_FOR_UPDATES ??= 'true';
process.env.DOTENV_CONFIG_QUIET ??= 'true';

process.on('unhandledRejection', error => {
    console.error('Unhandled promise rejection in boardgame-web:', error);
});
process.on('uncaughtException', error => {
    console.error('Uncaught exception in boardgame-web:', error);
});

console.log('Boardgame Web for Android');
console.log(`Node.js ${process.version} (Node.js Mobile ${process.versions.mobile ?? 'unknown'})`);
console.log(`Data directory: ${dataDirectory}`);
console.log(`HTTP port: ${port}`);

/*
 * The `uuidv4` package used by the core module expects the browser-style
 * `globalThis.crypto` object. Node.js Mobile does not expose it, so provide
 * the native Web Crypto implementation (or a small random-only fallback).
 */
if (!globalThis.crypto) {
    Object.defineProperty(globalThis, 'crypto', {
        configurable: true,
        enumerable: false,
        value: nodeCrypto.webcrypto ?? {
            getRandomValues(array) {
                return nodeCrypto.randomFillSync(array);
            },
            randomUUID() {
                return nodeCrypto.randomUUID();
            }
        }
    });
    console.log('Installed Web Crypto global');
}

/*
 * Node.js Mobile 18.x does not ship the `node:sqlite` built-in that the
 * boardgame-web-ts core module uses. sql.js provides the same synchronous
 * SQLite API surface through WebAssembly, and `runtime-loader.mjs` redirects
 * `node:sqlite` imports to `sqlite-shim.mjs`. The loader also patches Unicode
 * regex escapes that Node.js Mobile's ICU-less V8 build cannot parse.
 *
 * sql.js is initialised here (asynchronously) so the shim can expose the
 * synchronous `DatabaseSync` constructor that the core module expects.
 */
const require = createRequire(import.meta.url);
const initSqlJs = require('./vendor/sql-wasm.js');
const sqlJs = await initSqlJs({
    locateFile: file => path.join(projectDirectory, 'vendor', file)
});

globalThis.__BOARDGAME_SQL_JS__ = sqlJs;

const { register } = await import('node:module');
register('./runtime-loader.mjs', import.meta.url);

await import('./launcher.mjs');
