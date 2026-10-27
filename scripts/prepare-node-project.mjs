#!/usr/bin/env node
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const launcherRoot = path.resolve(projectRoot, '..', 'boardgame-web-ts', 'launcher');
const templateDir = path.join(projectRoot, 'node-project');
const assetsDir = path.join(projectRoot, 'app', 'src', 'main', 'assets', 'nodejs-project');
const sqlJsDistDir = path.join(projectRoot, 'node_modules', 'sql.js', 'dist');

function copyDirectory(source, destination) {
    fs.mkdirSync(destination, { recursive: true });

    for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
        const sourcePath = path.join(source, entry.name);
        const destinationPath = path.join(destination, entry.name);

        if (entry.isDirectory()) {
            copyDirectory(sourcePath, destinationPath);
        } else if (entry.isFile()) {
            fs.copyFileSync(sourcePath, destinationPath);
        }
    }
}

function assertExists(filePath, hint) {
    if (!fs.existsSync(filePath)) {
        throw new Error(`Missing "${filePath}". ${hint}`);
    }
}

if (!fs.existsSync(templateDir)) {
    throw new Error(`Missing Node.js project template directory "${templateDir}".`);
}

assertExists(
    path.join(projectRoot, 'node_modules', 'sql.js', 'dist', 'sql-wasm.js'),
    'Run "npm install" in boardgame-web-android first.'
);

if (process.env.BOARDGAME_SKIP_LAUNCHER_BUILD !== '1') {
    console.log('Building boardgame-web-ts launcher...');
    execSync('npm run build', {
        cwd: launcherRoot,
        stdio: 'inherit',
        env: process.env
    });
}

const launcherBundle = path.join(launcherRoot, 'dist', 'bundled', 'launcher.mjs');
assertExists(
    launcherBundle,
    'Run "npm run build -w launcher" inside boardgame-web-ts, or unset BOARDGAME_SKIP_LAUNCHER_BUILD.'
);

console.log(`Preparing Node.js project in "${assetsDir}"...`);

fs.rmSync(assetsDir, { recursive: true, force: true });
copyDirectory(templateDir, assetsDir);

fs.copyFileSync(launcherBundle, path.join(assetsDir, 'launcher.mjs'));

const vendorDir = path.join(assetsDir, 'vendor');
fs.mkdirSync(vendorDir, { recursive: true });
fs.copyFileSync(
    path.join(sqlJsDistDir, 'sql-wasm.js'),
    path.join(vendorDir, 'sql-wasm.js')
);
fs.copyFileSync(
    path.join(sqlJsDistDir, 'sql-wasm.wasm'),
    path.join(vendorDir, 'sql-wasm.wasm')
);

console.log('Node.js project prepared:');
for (const entry of fs.readdirSync(assetsDir, { withFileTypes: true })) {
    const entryPath = path.join(assetsDir, entry.name);
    const size = entry.isFile() ? ` (${fs.statSync(entryPath).size} bytes)` : '';
    console.log(`  ${entry.isDirectory() ? '[dir] ' : ''}${entry.name}${size}`);
}
