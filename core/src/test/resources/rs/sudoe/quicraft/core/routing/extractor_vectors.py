# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent golden oracle. Run with python3 -I extractor_vectors.py.

Each line is identifier|complete packet hex|expected chunk pairs (or JOIN)|consumed bytes.
No game mappings or Java decoder logic are used. Every case is emitted twice, under the
synthetic primitive.<layout>.v1 identifier and under the reviewed production
mc775.<layout>.v1 identifier: both name the same layout math, and the vectors pin negative
coordinates, per-layout boundaries, prefix consumption and payload-carrying chunk lists.
Truncations and malformed inputs stay in the Java tests, which cut these vectors.
"""
import struct
import pathlib
import sys

lines = []


def emit(layout, layout_body, expected, trailing=b''):
    packet = b'\x01' + layout_body + trailing
    consumed = 1 + len(layout_body)
    line = '|' + packet.hex() + '|' + expected + '|' + str(consumed)
    lines.append('primitive.' + layout + '.v1' + line)
    lines.append('mc775.' + layout + '.v1' + line)


def coords(x, z):
    return str(x) + ',' + str(z)


def varint(value):
    value &= 0xffffffff
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def entry(x, z, payload):
    """One chunk list entry: packed ChunkPos (z high, x low), VarInt length, payload."""
    return struct.pack('>Q', (x & 0xffffffff) | (z & 0xffffffff) << 32) + varint(len(payload)) + payload


for x, z in [(0, 0), (-1, -17), (-2147483648, 2147483647)]:
    emit('chunk_xz', struct.pack('>ii', x, z), coords(x, z))
    emit('varint_xz', varint(x) + varint(z), coords(x, z))
for x, y, z in [(0, 0, 0), (-1, -64, -17), (-33554432, -2048, 33554431)]:
    packed = ((x % 2**26) * 2**38 + (z % 2**26) * 2**12 + y % 2**12)
    emit('block_position', struct.pack('>Q', packed), coords(x // 16, z // 16))
for x, y, z in [(0, 0, 0), (-1, -1, -17), (-2097152, -524288, 2097151)]:
    packed = ((x % 2**22) * 2**42 + (z % 2**22) * 2**20 + y % 2**20)
    emit('section_position', struct.pack('>Q', packed), coords(x, z))
emit('join', b'', 'JOIN')
# A join carries a non-empty body in the real protocol; only the id is layout, and any
# bytes after it are the rest of the packet (13.7).
emit('join', b'', 'JOIN', trailing=b'\x80\x08')
emit('join', b'', 'JOIN', trailing=b'\xc3\x28')
emit('chunk_list', b'\x00', '')
# One entry is a packed ChunkPos, a VarInt payload length and that many payload bytes.
emit('chunk_list', varint(1) + entry(0, 0, b''), '0,0')
emit('chunk_list', varint(2) + entry(-1, -17, b'\x01\x02') + entry(-2147483648, 2147483647, b''),
     '-1,-17;-2147483648,2147483647')
emit('chunk_list', varint(1) + entry(3, -4, b'\x2a'), '3,-4', trailing=b'\xde\xad\xbe\xef')
# Bytes after the layout are the rest of the real packet and are ignored (13.7).
emit('chunk_xz', struct.pack('>ii', -1, -17), '-1,-17', trailing=b'\x00\x01\x02')
emit('varint_xz', varint(-1) + varint(-17), '-1,-17', trailing=b'\xff\xff\xff\xff\x0f')
emit('section_position', struct.pack('>Q', 0), '0,0', trailing=b'\x00')

output = '\n'.join(lines) + '\n'
if '--check' in sys.argv:
    assert pathlib.Path(__file__).with_name('extractor-vectors.txt').read_text() == output
    print(len(lines), 'independent golden vectors match')
else:
    print(output, end='')
