// Keeps skiko's Wasm calls out of JS tail position, so Compose renders in Safari 27.2; the why is in
// app/webpack/skiko-no-tail-call.cjs. Appended by the Kotlin Gradle plugin to every generated webpack
// config for :app, where __dirname is build/wasm/packages/<package>/.
//
// The check fails the build if a wrapper still tail-calls Wasm in the emitted bundle — after
// minification, so a minifier folding one back is caught too.
{
    const skikoLoaderPath = require('path').resolve(__dirname, '../../../../app/webpack/skiko-no-tail-call.cjs');
    const { findTailCallWrappers } = require(skikoLoaderPath);

    config.module = config.module || {};
    config.module.rules = config.module.rules || [];
    config.module.rules.push({ test: /[\\/]skiko\.mjs$/, loader: skikoLoaderPath });

    config.plugins = config.plugins || [];
    config.plugins.push({
        apply(compiler) {
            const { Compilation, WebpackError } = compiler.webpack;
            compiler.hooks.thisCompilation.tap('SkikoNoTailCallCheck', (compilation) => {
                compilation.hooks.processAssets.tap(
                    { name: 'SkikoNoTailCallCheck', stage: Compilation.PROCESS_ASSETS_STAGE_ANALYSE },
                    (assets) => {
                        for (const [name, asset] of Object.entries(assets)) {
                            if (!/\.m?js$/.test(name)) continue;
                            const left = findTailCallWrappers(asset.source().toString());
                            if (left.length > 0) {
                                compilation.errors.push(new WebpackError(
                                    `${name}: ${left.length} skiko wrapper(s) still tail-call Wasm, e.g. ${left[0]}`,
                                ));
                            }
                        }
                    },
                );
            });
        },
    });
}
