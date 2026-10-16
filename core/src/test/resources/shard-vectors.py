#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent reference vectors for protocol.md 13.18; run with python3 -I."""
MASK = 0xffffffff

def u32(x): return x & MASK
def rotl(x, n): return u32((x << n) | (x >> (32 - n)))
def shard(x, z, e, n):
    h = u32(u32(x) * 0x9e3779b1) ^ rotl(u32(u32(z) * 0x85ebca77), 16) ^ u32(e * 0xc2b2ae3d)
    h = u32(h ^ (h >> 16)); h = u32(h * 0x7feb352d)
    h = u32(h ^ (h >> 15)); h = u32(h * 0x846ca68b)
    h = u32(h ^ (h >> 16))
    return h % n

coords = [(0,0),(-1,-1),(-2147483648,2147483647),(2147483647,-2147483648)]
for x,z in coords:
    for e in (0,1,2):
        print(x,z,e,*[shard(x,z,e,n) for n in range(1,7)])

def varint(value):
    out = []
    while value > 0x7f:
        out.append((value & 0x7f) | 0x80)
        value >>= 7
    return out + [value]

def record(kind, sequence, epoch, deps, body):
    header = [kind] + varint(sequence) + varint(epoch) + varint(len(deps))
    for index, required in deps:
        header += [index] + varint(required)
    return bytes(varint(len(header) + len(body)) + header + body).hex()

print(record(0, 1, 0, [], [1, 0]))
print(record(1, 300, 2, [(1, 9)], []))
print(record(0, 128, 127, [(2, 16384)], [1, 0]))
