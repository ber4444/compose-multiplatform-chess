// Converts Filament's papermill KTX1 cubemaps (R11F_G11F_B10F, 256^2, the files desktop/Android use
// for the visible background + IBL) into equirectangular Radiance .hdr files that three.js's
// RGBELoader consumes. This is what lets the web/iOS three.js boards show the SAME papermill
// environment (vegetation + sun + sky) as the Android Filament reference, instead of a flat
// backdrop + RoomEnvironment.
//
// Run via build.mjs (after `npm install`): regenerates
//   app/src/wasmJsMain/resources/papermill_{skybox,ibl}.hdr   (web: served at /)
//   iosApp/iosApp/Resources/papermill_{skybox,ibl}.hdr        (iOS: bundled alongside chess.glb)
//
// The decode + projection is self-contained (no deps). A PNG preview is written under build/ so a
// human can eyeball orientation; a couple of programmatic sanity checks (sky > ground brightness,
// sun above horizon) run automatically and assert.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '..', '..')

// --- R11F_G11F_B10F decode (UF11 / UF11 / UF10 unsigned-float per channel) ---
function uf11(v) {
  const e = (v >> 6) & 0x1f, m = v & 0x3f
  if (e === 31) return 1e9            // clamp inf/nan to a large HDR value
  if (e === 0) return (m / 64) * Math.pow(2, 1 - 15) // subnormal
  return (1 + m / 64) * Math.pow(2, e - 15)
}
function uf10(v) {
  const e = (v >> 5) & 0x1f, m = v & 0x1f
  if (e === 31) return 1e9
  if (e === 0) return (m / 32) * Math.pow(2, 1 - 15)
  return (1 + m / 32) * Math.pow(2, e - 15)
}

// Parse a Filament KTX1 cubemap, return mip-0 faces as Float32 RGB arrays (6 x W x H x 3).
function parseKtxCube(bytes) {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const F = (o) => dv.getUint32(o, true)
  const width = F(36), height = F(40), faces = F(52), mips = F(56), kvLen = F(60)
  if (faces !== 6 || mips < 1) throw new Error('expected a 6-face cubemap')
  let off = 64 + kvLen
  const mip0Size = F(off); off += 4
  const faceBytes = mip0Size // size of one face at mip 0
  if (faceBytes !== width * height * 4) {
    throw new Error('mip0 face size mismatch: ' + faceBytes + ' vs ' + (width * height * 4))
  }
  const faceData = []
  for (let f = 0; f < 6; f++) {
    const rgb = new Float32Array(width * height * 3)
    for (let i = 0; i < width * height; i++) {
      const packed = dv.getUint32(off + i * 4, true)
      rgb[i * 3] = uf11(packed & 0x7ff)
      rgb[i * 3 + 1] = uf11((packed >> 11) & 0x7ff)
      rgb[i * 3 + 2] = uf10((packed >> 22) & 0x3ff)
    }
    faceData.push({ w: width, h: height, rgb })
    off += faceBytes
  }
  return faceData
}

// OpenGL major-axis direction -> (face index, s in [-1,1], t in [-1,1]). Faces are KTX order:
// +X, -X, +Y, -Y, +Z, -Z. t points UP in the face image (row 0 = top).
function dirToFace(dx, dy, dz) {
  const ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz)
  let face, sc, tc, ma
  if (ax >= ay && ax >= az) { face = dx > 0 ? 0 : 1; sc = dx > 0 ? -dz : dz; tc = dy; ma = ax }
  else if (ay >= ax && ay >= az) { face = dy > 0 ? 2 : 3; sc = dx; tc = dy > 0 ? -dz : dz; ma = ay }
  else { face = dz > 0 ? 4 : 5; sc = dz > 0 ? dx : -dx; tc = dy; ma = az }
  const s = 0.5 * (sc / ma + 1)
  const t = 0.5 * (tc / ma + 1)
  return { face, s, t }
}

function sampleFaceBilinear(face, s, t) {
  const { w, h, rgb } = face
  // row 0 = top => invert t for array indexing
  const fx = s * w - 0.5
  const fy = (1 - t) * h - 0.5
  const x0 = Math.floor(fx), y0 = Math.floor(fy)
  const dx = fx - x0, dy = fy - y0
  const x1 = x0 + 1, y1 = y0 + 1
  const gx0 = Math.min(w - 1, Math.max(0, x0)), gx1 = Math.min(w - 1, Math.max(0, x1))
  const gy0 = Math.min(h - 1, Math.max(0, y0)), gy1 = Math.min(h - 1, Math.max(0, y1))
  const p = (x, y) => [rgb[(y * w + x) * 3], rgb[(y * w + x) * 3 + 1], rgb[(y * w + x) * 3 + 2]]
  const c00 = p(gx0, gy0), c10 = p(gx1, gy0), c01 = p(gx0, gy1), c11 = p(gx1, gy1)
  const out = [0, 0, 0]
  for (let c = 0; c < 3; c++) {
    const top = c00[c] * (1 - dx) + c10[c] * dx
    const bot = c01[c] * (1 - dx) + c11[c] * dx
    out[c] = top * (1 - dy) + bot * dy
  }
  return out
}

// Render the 6 cubemap faces to a W x H equirectangular Float32 RGB buffer (row 0 = zenith/+Y).
function cubeToEquirect(faces, W, H) {
  const out = new Float32Array(W * H * 3)
  let maxLum = 1e-6, maxRow = 0
  for (let y = 0; y < H; y++) {
    const phi = ((y + 0.5) / H) * Math.PI          // 0 (top/+Y) .. PI (bottom/-Y)
    const sinPhi = Math.sin(phi), cosPhi = Math.cos(phi)
    for (let x = 0; x < W; x++) {
      const theta = ((x + 0.5) / W) * 2 * Math.PI
      const dx = sinPhi * Math.cos(theta)
      const dy = cosPhi
      const dz = sinPhi * Math.sin(theta)
      const { face, s, t } = dirToFace(dx, dy, dz)
      const c = sampleFaceBilinear(faces[face], s, t)
      const o = (y * W + x) * 3
      out[o] = c[0]; out[o + 1] = c[1]; out[o + 2] = c[2]
      const lum = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
      if (lum > maxLum) { maxLum = lum; maxRow = y }
    }
  }
  return { out, W, H, maxLum, maxRow }
}

// Radiance RGBE writer with PROPER per-scanline RLE (the "new" RLE format), because three.js's
// RGBELoader treats the file as 32-bit_rle_rgbe and only falls back to a flat read when a
// scanline's first 4 bytes don't match the RLE marker (2,2,W_lo,W_hi). Emitting uncompressed
// scanlines can therefore false-trigger the RLE path on some scanlines and corrupt the image or
// fail the load outright (which silently falls back to a flat background colour = "no sun").
function rleEncodeChannel(bytes, width) {
  const out = []
  let cur = 0
  while (cur < width) {
    const val = bytes[cur]
    let run = 1
    while (cur + run < width && bytes[cur + run] === val && run < 127) run++
    if (run >= 4) {
      out.push(128 + run, val) // RLE run: count byte (128..255), then one value byte
      cur += run
    } else {
      const beg = cur
      while (cur < width) {
        let rc = 1
        while (cur + rc < width && bytes[cur + rc] === bytes[cur]) rc++
        if (rc >= 4) break // a run starts here — leave it for the outer loop to RLE
        cur++
        if (cur - beg >= 128) break
      }
      out.push(cur - beg) // literal run: count byte (1..128), then that many value bytes
      for (let j = beg; j < cur; j++) out.push(bytes[j])
    }
  }
  return out
}

function writeHdr(filePath, rgb, W, H) {
  const header = '#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y ' + H + ' +X ' + W + '\n'
  const enc = new TextEncoder()
  const head = enc.encode(header)
  const body = []
  const rgbe = new Uint8Array(W * 4)
  for (let y = 0; y < H; y++) {
    // pack this scanline to RGBE
    for (let x = 0; x < W; x++) {
      const i = (y * W + x) * 3
      const r = rgb[i], g = rgb[i + 1], b = rgb[i + 2]
      const v = Math.max(r, g, b)
      if (v > 1e-6) {
        let e = Math.ceil(Math.log2(v)) + 129 // +129 keeps max mantissa <= ~0.99*255 (no clamp)
        if (e < 1) e = 1
        if (e > 254) e = 254
        const f = Math.pow(2, e - 128 - 8)
        rgbe[x * 4] = Math.min(255, Math.max(0, Math.round(r / f)))
        rgbe[x * 4 + 1] = Math.min(255, Math.max(0, Math.round(g / f)))
        rgbe[x * 4 + 2] = Math.min(255, Math.max(0, Math.round(b / f)))
        rgbe[x * 4 + 3] = e
      } else {
        rgbe[x * 4 + 3] = 1
      }
    }
    // new-RLE scanline: marker (2,2,W_hi,W_lo) — three.js reads the width BIG-endian as
    // (buf[2]<<8)|buf[3], so the high byte comes first. Then each channel RLE-encoded separately
    // in R,G,B,E order (the decoder reads them sequentially into a 4*W buffer).
    body.push(2, 2, (W >> 8) & 0xff, W & 0xff)
    for (let c = 0; c < 4; c++) {
      const chan = new Uint8Array(W)
      for (let x = 0; x < W; x++) chan[x] = rgbe[x * 4 + c]
      const enc2 = rleEncodeChannel(chan, W)
      for (const b of enc2) body.push(b)
    }
  }
  const all = new Uint8Array(head.length + body.length)
  all.set(head, 0)
  for (let i = 0; i < body.length; i++) all[head.length + i] = body[i]
  writeFileSync(filePath, all)
}

// Round-trip self-check: re-decode the RLE scanlines and assert the brightest input pixel survives
// with roughly its original value. Catches any RLE/RGBE encoding bug before the file ships.
function verifyHdr(filePath, rgb, W, H) {
  const b = readFileSync(filePath)
  // skip text header to the resolution line, then the binary body
  let p = 0
  const nl = (s) => { let i = p; while (i < b.length && b[i] !== 10) i++; const line = b.slice(p, i).toString(); p = i + 1; return line }
  let line = nl(); if (!line.startsWith('#?RADIANCE')) throw new Error('verify: bad magic')
  while (true) { line = nl(); if (line === '') break } // FORMAT + blank
  line = nl() // resolution "-Y H +X W"
  let inMax = 0
  for (let i = 0; i < W * H; i++) { const l = Math.max(rgb[i*3], rgb[i*3+1], rgb[i*3+2]); if (l > inMax) inMax = l }
  let outMax = 0
  for (let y = 0; y < H; y++) {
    if (!(b[p] === 2 && b[p+1] === 2 && b[p+2] === ((W >> 8) & 0xff) && b[p+3] === (W & 0xff))) throw new Error('verify: scanline ' + y + ' missing RLE marker')
    p += 4
    const chans = []
    for (let c = 0; c < 4; c++) {
      const chan = new Uint8Array(W); let x = 0
      while (x < W) {
        const cnt = b[p++]
        if (cnt > 128) { const n = cnt - 128; const v = b[p++]; for (let k = 0; k < n; k++) chan[x++] = v }
        else { for (let k = 0; k < cnt; k++) chan[x++] = b[p++] }
      }
      chans.push(chan)
    }
    for (let x = 0; x < W; x++) {
      const e = chans[3][x]
      if (e === 0) continue
      const f = Math.pow(2, e - 128 - 8)
      const lum = Math.max(chans[0][x], chans[1][x], chans[2][x]) * f
      if (lum > outMax) outMax = lum
    }
  }
  const ratio = outMax / inMax
  console.log('[papermill] verify ' + filePath.split('/').pop() + ': maxIn=' + inMax.toFixed(2) + ' maxOut=' + outMax.toFixed(2) + ' ratio=' + ratio.toFixed(3))
  if (ratio < 0.8 || ratio > 1.25) throw new Error('verify: round-trip max-value drift — RLE/RGBE encoding is wrong')
}

// Minimal PNG encoder (RGBA8, zlib deflate) for eyeballing orientation.
function writePng(filePath, rgb, W, H, exposure) {
  const raw = Buffer.alloc((W * 4 + 1) * H)
  for (let y = 0; y < H; y++) {
    raw[y * (W * 4 + 1)] = 0 // filter type 0
    for (let x = 0; x < W; x++) {
      const o = (y * W + x) * 3
      const tonemap = (v) => {
        const t = v * exposure
        return Math.min(255, Math.max(0, Math.round((t / (t + 1)) * 255))) // Reinhard
      }
      const pi = y * (W * 4 + 1) + 1 + x * 4
      raw[pi] = tonemap(rgb[o]); raw[pi + 1] = tonemap(rgb[o + 1]); raw[pi + 2] = tonemap(rgb[o + 2]); raw[pi + 3] = 255
    }
  }
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
  function chunk(type, data) {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0)
    const t = Buffer.from(type, 'ascii')
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0)
    return Buffer.concat([len, t, data, crc])
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
  const idatData = deflateSync(raw)
  const png = Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idatData), chunk('IEND', Buffer.alloc(0))])
  writeFileSync(filePath, png)
}
const CRC_TABLE = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t.push(c >>> 0) } return t })()
function crc32(buf) { let c = 0xffffffff; for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }

function convert(ktxPath, outPaths, previewPath, label) {
  const bytes = readFileSync(ktxPath)
  const faces = parseKtxCube(bytes)
  const { out, W, H, maxLum, maxRow } = cubeToEquirect(faces, 1024, 512)

  // Sanity checks (no eyeballing required): sky (top third) must average brighter than ground
  // (bottom third), and the brightest pixel (the sun) must be in the top half of the image.
  let topLum = 0, botLum = 0
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const o = (y * W + x) * 3
    const l = 0.2126 * out[o] + 0.7152 * out[o + 1] + 0.0722 * out[o + 2]
    if (y < H / 3) topLum += l; else if (y > (2 * H) / 3) botLum += l
  }
  const topAvg = topLum / (W * (H / 3)), botAvg = botLum / (W * (H / 3))
  console.log(`[papermill] ${label}: skyAvg=${topAvg.toFixed(3)} groundAvg=${botAvg.toFixed(3)} sunRow=${maxRow}/${H} maxLum=${maxLum.toFixed(2)}`)
  if (topAvg < botAvg) throw new Error(label + ': sky is darker than ground — cubemap orientation is wrong, fix dirToFace/t sign')
  if (maxRow > H / 2) throw new Error(label + ': brightest pixel (sun) is below the horizon — orientation is wrong')

  for (const p of outPaths) { mkdirSync(dirname(p), { recursive: true }); writeHdr(p, out, W, H) }
  verifyHdr(outPaths[0], out, W, H)
  if (previewPath) { mkdirSync(dirname(previewPath), { recursive: true }); writePng(previewPath, out, W, H, 1.0) }
  console.log('[papermill] wrote', outPaths.join(', '))
}

const skyboxKtx = resolve(root, 'app/src/commonMain/composeResources/files/env/papermill_skybox.ktx')
const iblKtx = resolve(root, 'app/src/commonMain/composeResources/files/env/papermill_ibl.ktx')
const webRes = resolve(root, 'app/src/wasmJsMain/resources')
const iosRes = resolve(root, 'iosApp/iosApp/Resources')
const buildDir = resolve(root, 'build')

convert(skyboxKtx, [resolve(webRes, 'papermill_skybox.hdr'), resolve(iosRes, 'papermill_skybox.hdr')], resolve(buildDir, 'papermill_skybox_preview.png'), 'skybox')
convert(iblKtx, [resolve(webRes, 'papermill_ibl.hdr'), resolve(iosRes, 'papermill_ibl.hdr')], resolve(buildDir, 'papermill_ibl_preview.png'), 'ibl')
