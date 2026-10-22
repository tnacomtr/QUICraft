# SPDX-License-Identifier: GPL-3.0-or-later
"""Prints the vanilla server ops.json entry for an offline-mode player, as one JSON array.

The offline UUID is java.util.UUID.nameUUIDFromBytes("OfflinePlayer:<name>"): the MD5 of that
string with the version-3 and IETF-variant bits set. The external arm runner passes the result to
the testkit Fabric server as QUICRAFT_OPS_JSON for --hop runs, so the offline client can run /tp.

    python3 -I testkit/offline-ops.py Player0
"""
import hashlib
import json
import sys


def offline_uuid(name):
    raw = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    raw[6] = (raw[6] & 0x0F) | 0x30
    raw[8] = (raw[8] & 0x3F) | 0x80
    hexed = raw.hex()
    return "%s-%s-%s-%s-%s" % (hexed[0:8], hexed[8:12], hexed[12:16], hexed[16:20], hexed[20:32])


def main(argv):
    if len(argv) != 2 or not argv[1] or " " in argv[1]:
        sys.stderr.write("usage: offline-ops.py <player name>\n")
        return 2
    name = argv[1]
    entry = {"uuid": offline_uuid(name), "name": name, "level": 4, "bypassesPlayerLimit": False}
    sys.stdout.write(json.dumps([entry], separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
