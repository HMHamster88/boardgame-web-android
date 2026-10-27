#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const generatedDirectories = [
    path.join(projectRoot, 'app', 'src', 'main', 'assets', 'nodejs-project'),
    path.join(projectRoot, 'app', 'build'),
    path.join(projectRoot, 'build'),
    path.join(projectRoot, '.gradle'),
    path.join(projectRoot, '.cxx'),
    path.join(projectRoot, 'app', '.cxx')
];

for (const directory of generatedDirectories) {
    fs.rmSync(directory, { recursive: true, force: true });
    console.log(`Removed ${path.relative(projectRoot, directory)}`);
}

console.log('Run "npm run setup" before the next Android build.');
