#!/usr/bin/env python3
"""Add the three verdict-coloured highlight quads to `chess.glb` (B19).

The asset ships one highlight quad, node `Highlight`, whose blue comes from its material's
`emissiveFactor` — **not** from `baseColorFactor` (white) and **not** from alpha blending
(`alphaMode` is OPAQUE; the see-through look is `KHR_materials_transmission`). Several comments in
the repo claimed otherwise; they were wrong, and the difference matters because it rules out the
obvious runtime fix of tinting `baseColorFactor`.

Rather than have four Filament backends each poke a `MaterialInstance` parameter whose name varies
by Filament version, the tones live in the asset: three more nodes, identical geometry, differing
only in emissive colour. Every backend already picks its highlight renderable *by node name*, so
supporting tones is a one-line change per backend and there is no runtime material API involved.

Geometry is not duplicated. The mesh is Draco-compressed (`KHR_draco_mesh_compression` is a
*required* extension here), and the new primitives reference the same `bufferView` and accessors as
the original, so this edits only the JSON chunk — the binary chunk is untouched and the file grows
by roughly a kilobyte.

Idempotent: re-running detects the added nodes and does nothing.

    python3 tools/add_highlight_tones_to_glb.py
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

GLB = Path(__file__).resolve().parents[1] / "app/src/commonMain/composeResources/files/models/chess.glb"

# Keep in sync with ChessSetConventions.HIGHLIGHT_NODE_NAMES (ordinal order of HighlightTone).
# NEUTRAL is the pre-existing node and is not generated here.
TONES = [
    # (node name, material name, emissiveFactor)
    ("HighlightGood", "highlight-good", [0.0, 0.40, 0.05]),
    ("HighlightInaccurate", "highlight-inaccurate", [0.42, 0.22, 0.0]),
    ("HighlightBad", "highlight-bad", [0.45, 0.0, 0.0]),
]

JSON_CHUNK = 0x4E4F534A
BIN_CHUNK = 0x004E4942


def read_glb(path: Path):
    data = path.read_bytes()
    if data[:4] != b"glTF":
        raise SystemExit(f"{path} is not a GLB")
    chunks, off = [], 12
    while off < len(data):
        length, ctype = struct.unpack_from("<II", data, off)
        chunks.append((ctype, data[off + 8 : off + 8 + length]))
        off += 8 + length + (-length % 4)
    return chunks


def write_glb(path: Path, gltf: dict, binary: bytes | None) -> None:
    js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    js += b" " * (-len(js) % 4)
    out = bytearray()
    body = bytearray()
    body += struct.pack("<II", len(js), JSON_CHUNK) + js
    if binary is not None:
        pad = b"\0" * (-len(binary) % 4)
        body += struct.pack("<II", len(binary) + len(pad), BIN_CHUNK) + binary + pad
    out += b"glTF" + struct.pack("<II", 2, 12 + len(body)) + body
    path.write_bytes(bytes(out))


def main() -> int:
    chunks = read_glb(GLB)
    gltf = json.loads(next(c for t, c in chunks if t == JSON_CHUNK).decode("utf-8"))
    binary = next((c for t, c in chunks if t == BIN_CHUNK), None)

    node_names = {n.get("name") for n in gltf["nodes"]}
    if all(name in node_names for name, _, _ in TONES):
        print("already present; nothing to do")
        return 0

    src_node_i = next(i for i, n in enumerate(gltf["nodes"]) if n.get("name") == "Highlight")
    src_node = gltf["nodes"][src_node_i]
    src_mesh = gltf["meshes"][src_node["mesh"]]
    src_mat_i = src_mesh["primitives"][0]["material"]
    src_mat = gltf["materials"][src_mat_i]

    scene_nodes = gltf["scenes"][gltf.get("scene", 0)]["nodes"]
    if src_node_i not in scene_nodes:
        raise SystemExit("Highlight is not a scene root; the new nodes would inherit no transform")

    for node_name, mat_name, emissive in TONES:
        if node_name in node_names:
            continue
        mat = json.loads(json.dumps(src_mat))  # deep copy: same transmission/ior/pbr, new emissive
        mat["name"] = mat_name
        mat["emissiveFactor"] = emissive
        gltf["materials"].append(mat)

        # Same attributes, accessors and Draco bufferView as the original — geometry is shared, only
        # the material index differs.
        mesh = json.loads(json.dumps(src_mesh))
        mesh["name"] = f"{node_name}Quad"
        for prim in mesh["primitives"]:
            prim["material"] = len(gltf["materials"]) - 1
        gltf["meshes"].append(mesh)

        node = json.loads(json.dumps(src_node))
        node["name"] = node_name
        node["mesh"] = len(gltf["meshes"]) - 1
        gltf["nodes"].append(node)
        scene_nodes.append(len(gltf["nodes"]) - 1)
        print(f"added {node_name} (material {mat_name}, emissive {emissive})")

    before = GLB.stat().st_size
    write_glb(GLB, gltf, binary)
    print(f"{GLB.name}: {before} -> {GLB.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
