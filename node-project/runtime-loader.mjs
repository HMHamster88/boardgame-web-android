/**
 * ESM loader hooks for Node.js Mobile.
 *
 * 1. Replaces the `node:sqlite` built-in with the sql.js compatibility shim,
 *    because Node.js Mobile 18.x predates `node:sqlite`.
 * 2. Rewrites Unicode property escapes that V8 cannot parse when Node.js
 *    Mobile is built without ICU. `path-to-regexp` (used by Express) contains
 *    `\p{ID_Start}` and `\p{ID_Continue}`; the ASCII equivalents are
 *    sufficient for HTTP route parameter names.
 */
const sqliteShimUrl = new URL('./sqlite-shim.mjs', import.meta.url).href;

const unicodeEscapeReplacements = [
    ['\\p{ID_Start}', 'A-Za-z'],
    ['\\p{ID_Continue}', 'A-Za-z0-9_']
];

function sourceToString(source) {
    if (typeof source === 'string') {
        return source;
    }

    if (source instanceof ArrayBuffer) {
        return new TextDecoder().decode(new Uint8Array(source));
    }

    if (ArrayBuffer.isView(source)) {
        return new TextDecoder().decode(source);
    }

    return undefined;
}

export async function resolve(specifier, context, nextResolve) {
    if (specifier === 'node:sqlite') {
        return {
            url: sqliteShimUrl,
            shortCircuit: true
        };
    }

    return nextResolve(specifier, context);
}

export async function load(url, context, nextLoad) {
    const result = await nextLoad(url, context);

    if (!url.startsWith('file:') || result.format !== 'module') {
        return result;
    }

    const originalSource = sourceToString(result.source);
    if (originalSource === undefined || !originalSource.includes('\\p{ID_')) {
        return result;
    }

    let source = originalSource;
    for (const [from, to] of unicodeEscapeReplacements) {
        source = source.replaceAll(from, to);
    }

    console.log(`Patched unsupported Unicode regex escapes in ${url}`);

    return {
        ...result,
        source
    };
}
