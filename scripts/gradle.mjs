#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const isWindows = process.platform === 'win32';
const gradleCommand = isWindows ? 'gradlew.bat' : './gradlew';
const arguments_ = process.argv.slice(2);

if (arguments_.length === 0) {
    console.error('Usage: node scripts/gradle.mjs <gradle task...>');
    process.exit(1);
}

const result = spawnSync(path.join(projectRoot, gradleCommand), arguments_, {
    cwd: projectRoot,
    stdio: 'inherit',
    env: process.env,
    shell: isWindows
});

if (result.error) {
    console.error(result.error);
    process.exit(1);
}

process.exit(result.status ?? 1);
