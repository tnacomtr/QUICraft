#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Minecraft server-list status ping over TCP, for the updater end-to-end test.

usage: status-ping.py <host> <port> [timeout-seconds]

Sends the vanilla handshake (next state 1) and one status request, prints the JSON the server
answered and exits 0. Anything else (connect failure, timeout, EOF, a malformed or implausible
response, a response that is not a server-list status object) prints one line to stderr and exits 1.
Standard library only, Python 3, because every test runner has it and the updater end-to-end test
needs a status ping that is independent of the servers under test.
"""

import json
import socket
import struct
import sys

HANDSHAKE_ID = 0
STATUS_REQUEST_ID = 0
STATUS_STATE = 1
# Servers do not check the protocol version in the handshake; -1 means "unknown", which is what a
# client sends when it does not know the server's version.
UNKNOWN_PROTOCOL = -1
MAX_RESPONSE = 1 << 20
DEFAULT_TIMEOUT = 10.0


def write_varint(value):
    """Encode one Minecraft VarInt (32-bit two's complement) as bytes."""
    out = bytearray()
    remaining = value & 0xFFFFFFFF
    while True:
        byte = remaining & 0x7F
        remaining >>= 7
        if remaining:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_varint(stream):
    """Decode one VarInt from a binary stream."""
    value = 0
    for index in range(5):
        raw = stream.read(1)
        if not raw:
            raise ValueError("the connection closed while reading a VarInt")
        byte = raw[0]
        value |= (byte & 0x7F) << (7 * index)
        if not byte & 0x80:
            return value
    raise ValueError("a VarInt longer than five bytes")


def read_varint_at(data, offset):
    """Decode one VarInt from bytes; returns the value and the offset after it."""
    value = 0
    for index in range(5):
        if offset + index >= len(data):
            raise ValueError("a truncated VarInt in the status response")
        byte = data[offset + index]
        value |= (byte & 0x7F) << (7 * index)
        if not byte & 0x80:
            return value, offset + index + 1
    raise ValueError("an oversized VarInt in the status response")


def read_exact(stream, count):
    data = stream.read(count)
    if len(data) != count:
        raise ValueError("the connection closed while reading the response")
    return data


def frame(payload):
    """One length-prefixed packet."""
    return write_varint(len(payload)) + payload


def status(host, port, timeout):
    """Run one status ping and return the decoded JSON object."""
    host_bytes = host.encode("utf-8")
    handshake = (write_varint(HANDSHAKE_ID) + write_varint(UNKNOWN_PROTOCOL)
                 + write_varint(len(host_bytes)) + host_bytes
                 + struct.pack(">H", port) + write_varint(STATUS_STATE))
    request = frame(handshake) + frame(write_varint(STATUS_REQUEST_ID))
    with socket.create_connection((host, port), timeout) as connection:
        connection.settimeout(timeout)
        with connection.makefile("rb") as stream:
            connection.sendall(request)
            length = read_varint(stream)
            if length <= 0 or length > MAX_RESPONSE:
                raise ValueError("implausible status response length {}".format(length))
            payload = read_exact(stream, length)
            if payload[0] != STATUS_REQUEST_ID:
                raise ValueError("the status response has packet id {}".format(payload[0]))
            body = payload[1:]
            text_length, offset = read_varint_at(body, 0)
            if offset + text_length > len(body):
                raise ValueError("the status response string is truncated")
            return json.loads(body[offset:offset + text_length].decode("utf-8"))


def main(argv):
    if len(argv) < 3 or len(argv) > 4:
        print("usage: status-ping.py <host> <port> [timeout-seconds]", file=sys.stderr)
        return 2
    host = argv[1]
    try:
        port = int(argv[2])
        timeout = float(argv[3]) if len(argv) == 4 else DEFAULT_TIMEOUT
    except ValueError:
        print("status ping failed: the port and the timeout must be numbers", file=sys.stderr)
        return 2
    try:
        response = status(host, port, timeout)
    except (OSError, ValueError) as error:
        print("status ping failed: {}".format(error), file=sys.stderr)
        return 1
    if not isinstance(response, dict) or "version" not in response:
        print("status ping failed: the response is not a server-list status object", file=sys.stderr)
        return 1
    print(json.dumps(response, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
