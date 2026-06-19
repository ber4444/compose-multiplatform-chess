package com.example.myapplication.board3d

/**
 * Phase A.2 web baseline entry point + Phase B JS interop.
 *
 * Exposes the platform-agnostic [VisualBaselineScene] definitions to plain JavaScript so the
 * `web/spikes/threeBaseline.js` (and any other JS-side spike) can render the *same* canonical
 * scenes the Kotlin/Wasm production renderer does, without re-implementing FEN parsing or camera
 * math in JS.
 *
 * Kotlin/Wasm's `@JsExport` is stricter than Kotlin/JS: it cannot export custom classes, exported
 * functions can only return primitives / String / `JsAny` / `JsArray<out JsAny>` / etc., and raw
 * `js("...")` blocks must be a single top-level expression. So we (1) export only top-level
 * functions, (2) declare a [SceneDescription] `external interface : JsAny` for the JS-side shape,
 * (3) build each scene as a plain JS object via a `@JsFun`-annotated builder that the Kotlin
 * compiler treats as an opaque call into JS, and (4) return a `JsArray<SceneDescription>` so JS
 * spike code sees an idiomatic Array-like value with no Kotlin runtime involved.
 *
 * The flat object shape (one entry per scene, every field a primitive) is deliberate: spike JS
 * gets `{ id, fen, widthPx, heightPx, camPosX, …, label }` — scalars where natural.
 */

/** Shape of a baseline scene as seen from JS (a plain JS object). */
external interface SceneDescription : JsAny {
    var id: String
    var fen: String
    var widthPx: Int
    var heightPx: Int
    var camPosX: Float
    var camPosY: Float
    var camPosZ: Float
    var camTgtX: Float
    var camTgtY: Float
    var camTgtZ: Float
    var camUpX: Float
    var camUpY: Float
    var camUpZ: Float
    var camFovYDegrees: Float
    var camAspect: Float
    var label: String
}

/**
 * Builds a plain JS object literal with the given scalar fields, on the JS side. The Kotlin
 * compiler sees only the typed Kotlin signature; the JS body is an opaque ES2015 shorthand.
 */
@JsFun(
    "(id, fen, widthPx, heightPx, camPosX, camPosY, camPosZ, camTgtX, camTgtY, camTgtZ, camUpX, camUpY, camUpZ, camFovYDegrees, camAspect, label) => " +
        "({ id, fen, widthPx, heightPx, camPosX, camPosY, camPosZ, camTgtX, camTgtY, camTgtZ, camUpX, camUpY, camUpZ, camFovYDegrees, camAspect, label })"
)
private external fun makeSceneJs(
    id: String,
    fen: String,
    widthPx: Int,
    heightPx: Int,
    camPosX: Float, camPosY: Float, camPosZ: Float,
    camTgtX: Float, camTgtY: Float, camTgtZ: Float,
    camUpX: Float, camUpY: Float, camUpZ: Float,
    camFovYDegrees: Float,
    camAspect: Float,
    label: String,
): SceneDescription

/** Pushes a [SceneDescription] onto a `JsArray<SceneDescription>` (JS-side `Array.push`). */
@JsFun("(arr, item) => { arr.push(item); return arr; }")
private external fun pushSceneJs(arr: JsArray<SceneDescription>, item: SceneDescription)

/** Constructs an empty JS array; used as the seed for [getBaselineScenes]. */
@JsFun("() => []")
private external fun emptySceneArrayJs(): JsArray<SceneDescription>

/** JS-callable list of every [VisualBaselineScene]. Spike code: `import { getBaselineScenes } from ...`. */
@JsExport
fun getBaselineScenes(): JsArray<SceneDescription> {
    val out = emptySceneArrayJs()
    for (scene in VisualBaselineScenes.ALL) {
        pushSceneJs(out, scene.toJsScene())
    }
    return out
}

/** JS-callable helper: build a stable filename for a scene on the web platform. */
@JsExport
fun baselineSceneFilename(id: String): String = "scene-$id-web.png"

private fun VisualBaselineScene.toJsScene(): SceneDescription = makeSceneJs(
    id = id,
    fen = fen,
    widthPx = widthPx,
    heightPx = heightPx,
    camPosX = camera.position.x, camPosY = camera.position.y, camPosZ = camera.position.z,
    camTgtX = camera.target.x, camTgtY = camera.target.y, camTgtZ = camera.target.z,
    camUpX = camera.up.x, camUpY = camera.up.y, camUpZ = camera.up.z,
    camFovYDegrees = camera.fovYDegrees,
    camAspect = camera.aspect,
    label = label,
)
