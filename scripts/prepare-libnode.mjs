#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';
import { unzipSync } from 'fflate';

const NODEJS_MOBILE_VERSION = '18.20.4';
const ARCHIVE_NAME = `nodejs-mobile-v${NODEJS_MOBILE_VERSION}-android.zip`;
const DOWNLOAD_URL = `https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v${NODEJS_MOBILE_VERSION}/${ARCHIVE_NAME}`;
const ARCHIVE_SHA256 = 'bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af';
const SUPPORTED_ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86_64'];

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const cacheDir = path.join(projectRoot, '.cache');
const archivePath = path.join(cacheDir, ARCHIVE_NAME);
const libnodeDir = path.join(projectRoot, 'app', 'libnode');
const includeDir = path.join(libnodeDir, 'include');
const binaryDir = path.join(libnodeDir, 'bin');

function isPrepared() {
    const headerExists = fs.existsSync(path.join(includeDir, 'node', 'node.h'));
    const binariesExist = SUPPORTED_ABIS.every(abi =>
        fs.existsSync(path.join(binaryDir, abi, 'libnode.so'))
    );
    return headerExists && binariesExist;
}

function sha256(filePath) {
    return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

async function downloadArchive() {
    fs.mkdirSync(cacheDir, { recursive: true });

    if (fs.existsSync(archivePath)) {
        const hash = sha256(archivePath);
        if (hash === ARCHIVE_SHA256) {
            console.log('Using cached Node.js Mobile archive.');
            return;
        }
        console.warn('Cached Node.js Mobile archive is corrupt, downloading again.');
        fs.rmSync(archivePath, { force: true });
    }

    console.log(`Downloading ${DOWNLOAD_URL}...`);
    const response = await fetch(DOWNLOAD_URL, { redirect: 'follow' });
    if (!response.ok || !response.body) {
        throw new Error(`Failed to download Node.js Mobile (HTTP ${response.status}).`);
    }

    const totalBytes = Number(response.headers.get('content-length') ?? 0);
    let downloadedBytes = 0;
    let lastPercent = -1;
    const progress = new Transform({
        transform(chunk, _encoding, callback) {
            downloadedBytes += chunk.length;
            if (totalBytes > 0) {
                const percent = Math.floor((downloadedBytes / totalBytes) * 100);
                if (percent >= lastPercent + 5) {
                    lastPercent = percent;
                    console.log(`  ${percent}% (${(downloadedBytes / 1024 / 1024).toFixed(1)} MB)`);
                }
            }
            callback(null, chunk);
        }
    });

    const temporaryPath = `${archivePath}.part`;
    await pipeline(
        Readable.fromWeb(response.body),
        progress,
        fs.createWriteStream(temporaryPath)
    );

    const hash = sha256(temporaryPath);
    if (hash !== ARCHIVE_SHA256) {
        fs.rmSync(temporaryPath, { force: true });
        throw new Error(`Checksum mismatch for ${ARCHIVE_NAME}. Expected ${ARCHIVE_SHA256}, got ${hash}.`);
    }

    fs.renameSync(temporaryPath, archivePath);
    console.log('Download complete and checksum verified.');
}

function safeJoin(baseDirectory, relativePath) {
    const resolvedBase = path.resolve(baseDirectory);
    const destination = path.resolve(baseDirectory, relativePath);

    if (destination !== resolvedBase && !destination.startsWith(resolvedBase + path.sep)) {
        throw new Error(`Unsafe path in archive: ${relativePath}`);
    }

    return destination;
}

async function extractArchive() {
    console.log('Extracting libnode binaries and headers...');
    fs.rmSync(includeDir, { recursive: true, force: true });
    fs.rmSync(binaryDir, { recursive: true, force: true });

    const archiveBytes = new Uint8Array(fs.readFileSync(archivePath));
    const extractedFiles = unzipSync(archiveBytes, {
        filter: file => file.name.startsWith('bin/') || file.name.startsWith('include/')
    });

    for (const [entryName, data] of Object.entries(extractedFiles)) {
        const normalizedName = entryName.replaceAll('\\', '/');

        if (normalizedName.startsWith('bin/')) {
            const destination = safeJoin(binaryDir, normalizedName.slice('bin/'.length));
            fs.mkdirSync(path.dirname(destination), { recursive: true });
            fs.writeFileSync(destination, data);
        } else if (normalizedName.startsWith('include/')) {
            const destination = safeJoin(includeDir, normalizedName.slice('include/'.length));
            fs.mkdirSync(path.dirname(destination), { recursive: true });
            fs.writeFileSync(destination, data);
        }
    }
}

if (isPrepared()) {
    console.log('Node.js Mobile is already prepared in app/libnode.');
    process.exit(0);
}

await downloadArchive();
await extractArchive();

if (!isPrepared()) {
    throw new Error('Node.js Mobile extraction did not produce the expected files.');
}

console.log('Node.js Mobile is ready:');
for (const abi of SUPPORTED_ABIS) {
    const libraryPath = path.join(binaryDir, abi, 'libnode.so');
    console.log(`  ${abi}: ${(fs.statSync(libraryPath).size / 1024 / 1024).toFixed(1)} MB`);
}
