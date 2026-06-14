import struct
import json

with open('./app/src/commonMain/composeResources/files/models/chess.glb', 'rb') as f:
    data = f.read()
    magic, version, length = struct.unpack('<III', data[:12])
    chunk_len, chunk_type = struct.unpack('<II', data[12:20])
    js = json.loads(data[20:20+chunk_len].decode('utf-8'))
    nodes = js.get('nodes')
    for idx, node in enumerate(nodes):
        if node.get('name') in ['king', 'queen', 'rook', 'bishop', 'knight', 'pawn']:
            print(f"Template node {node.get('name')} is at index {idx}")
