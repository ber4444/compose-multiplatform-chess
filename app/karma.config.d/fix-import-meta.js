// Transitive dependencies compiled for Kotlin/Wasm (such as kotlinx-io-core's nodejs interop
// in `game-app-test.import-object.mjs`) include `@JsFun` stubs with `const importMeta = import.meta;`.
//
// When karma-webpack bundles the wasm test runner for browser testing (ChromeHeadless), webpack
// outputs a classic non-ESM script bundle (`commons.js`). In Webpack 5, bare `import.meta` expressions
// without property access are left untouched in the output bundle unless replaced by DefinePlugin.
// When Chrome loads `commons.js` via a classic `<script>` tag, raw `import.meta` causes:
// `Uncaught SyntaxError: Cannot use 'import.meta' outside a module`, aborting Karma before any tests run.
//
// Replacing `import.meta` with an empty object literal `({})` at bundle time satisfies the syntax in
// classic scripts while preserving property lookups like `importMeta.url`.
const webpack = require('webpack');
if (config.webpack) {
    config.webpack.plugins = config.webpack.plugins || [];
    config.webpack.plugins.push(new webpack.DefinePlugin({
        'import.meta': '({})'
    }));
}
