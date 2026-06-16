package com.example.myapplication.board3d

import kotlin.js.Promise

@Suppress("UNUSED_PARAMETER")
internal fun getProp(obj: JsAny, key: String): JsAny? = js("obj[key]")

@Suppress("UNUSED_PARAMETER")
internal fun getIntProp(obj: JsAny, key: String): Int? = js("obj[key] === undefined ? null : obj[key]")

@Suppress("UNUSED_PARAMETER")
internal fun getStringProp(obj: JsAny, key: String): String? = js("obj[key] === undefined ? null : obj[key]")

@Suppress("UNUSED_PARAMETER")
internal fun getArrayProp(obj: JsAny, key: String): JsArray<JsAny>? = js("obj[key]")

@Suppress("UNUSED_PARAMETER")
internal fun parseJson(str: String): JsAny = js("JSON.parse(str)")

@Suppress("UNUSED_PARAMETER")
internal fun jsNumberToInt(num: JsAny): Int = js("Number(num)")

@Suppress("UNUSED_PARAMETER")
internal fun jsNumberToFloat(num: JsAny): Float = js("Number(num)")

@Suppress("UNUSED_PARAMETER")
internal fun createBlob(bytes: JsAny, mimeType: String): JsAny = js("new Blob([bytes], {type: mimeType})")

@Suppress("UNUSED_PARAMETER")
internal fun createImageBitmap(blob: JsAny): Promise<JsAny> = js("createImageBitmap(blob)")

@Suppress("UNUSED_PARAMETER")
internal fun getBitmapWidth(bitmap: JsAny): Int = js("bitmap.width")

@Suppress("UNUSED_PARAMETER")
internal fun getBitmapHeight(bitmap: JsAny): Int = js("bitmap.height")

@Suppress("UNUSED_PARAMETER")
internal fun createOffscreenCanvas(w: Int, h: Int): JsAny = js("new OffscreenCanvas(w, h)")

@Suppress("UNUSED_PARAMETER")
internal fun getContext2D(canvas: JsAny): JsAny = js("canvas.getContext('2d')")

@Suppress("UNUSED_PARAMETER")
internal fun drawImage(ctx: JsAny, bitmap: JsAny): Unit = js("ctx.drawImage(bitmap, 0, 0)")

@Suppress("UNUSED_PARAMETER")
internal fun getImageDataLen(ctx: JsAny, w: Int, h: Int): Int = js("ctx.getImageData(0, 0, w, h).data.length")

@Suppress("UNUSED_PARAMETER")
internal fun getImageDataArray(ctx: JsAny, w: Int, h: Int): JsAny = js("ctx.getImageData(0, 0, w, h).data")

@Suppress("UNUSED_PARAMETER")
internal fun getU8ArrayByte(arr: JsAny, i: Int): Int = js("arr[i]")
