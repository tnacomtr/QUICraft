# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent golden oracle. Run with python3 -I extractor_vectors.py.

Each line is identifier|complete packet hex|expected chunk pairs (or JOIN).
No game mappings or Java decoder logic are used.
"""
import struct
import pathlib
import sys

lines = []


def emit(layout, body, expected):
    lines.append('primitive.' + layout + '.v1|' + (b'\x01' + body).hex() + '|' + expected)


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
emit('chunk_list', b'\x00', '')
emit('chunk_list', b'\x02' + struct.pack('>iiii', -1, -17, -2147483648, 2147483647),
     '-1,-17;-2147483648,2147483647')

output = '\n'.join(lines) + '\n'
if '--check' in sys.argv:
    assert pathlib.Path(__file__).with_name('extractor-vectors.txt').read_text() == output
    print('15 independent golden vectors match')
else:
    print(output, end='')
