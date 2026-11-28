// Webpack loader for skiko.mjs, applied by app/webpack.config.d/skiko-no-tail-call.js.
//
// skiko.mjs hands every Skia export to Kotlin/Wasm through a wrapper that ends in a JS tail call into
// skiko's own Wasm module: `(...a) => (X = loadedWasm._["X"])(...a)`. Safari 27.2 (macOS and iOS)
// delivers an f32/f64 argument of 0 or 1 through such a call as NaN once the calling Wasm has been
// optimised (WebKit bug 329421, JetBrains CMP-10986). Compose then measures and draws with garbage,
// Skia issues no draw calls at all, and the page stays white with no error anywhere. A call inside
// `try` is never in tail position, so the rewrite below sidesteps the bug.
//
// Measured on Safari 27.2 / macOS 27.2.1 against the deployed bundle: before, the Compose canvas made
// 82 WebGL calls in 8 s with 0 draws and 0 shader compiles; after, 488 calls, 23 draws and 8 shaders,
// the same as Chromium. Drop this once the Safari versions in use carry the WebKit fix (landed on
// WebKit main 2026-11-03, not in Safari 27.2).
'use strict';

// The wrapper as skiko ships it, `(...a) => (X = loadedWasm._["X"])(...a)`, and its minified form
// `(...e)=>(bn=k._.X)(...e)`.
const TAIL_CALL_WRAPPER =
    /\(\.\.\.([\w$]+)\)\s*=>\s*\(\s*([\w$]+)\s*=\s*([\w$]+)\._(\.[\w$]+|\[(["'])[\w$]+\5\])\s*\)\s*\(\s*\.\.\.\1\s*\)/g;

function rewriteTailCallWrappers(source) {
    let count = 0;
    const code = source.replace(TAIL_CALL_WRAPPER, (match, rest, binding, holder, member) => {
        count++;
        return `(...${rest}) => { try { return (${binding} = ${holder}._${member})(...${rest}) } catch (e) { throw e } }`;
    });
    return { code, count };
}

function findTailCallWrappers(code) {
    return Array.from(code.matchAll(TAIL_CALL_WRAPPER), (match) => match[0]);
}

function skikoNoTailCallLoader(source) {
    const { code, count } = rewriteTailCallWrappers(source);
    // A skiko update that changes the wrappers' shape must fail the build, not quietly bring the
    // white page back.
    if (count === 0) {
        throw new Error(`${this.resourcePath}: no skiko export wrapper matched, so the Safari 27.2 workaround would not apply`);
    }
    return code;
}

module.exports = skikoNoTailCallLoader;
module.exports.rewriteTailCallWrappers = rewriteTailCallWrappers;
module.exports.findTailCallWrappers = findTailCallWrappers;
