# Spec draft for docs/protocol.md 13.x: the ACK lane (unit p5-acklane, 2026-10-21)

Status: DRAFT text for a later docs unit. Not applied to `docs/protocol.md`; the shipped 775 table is unchanged.
Design source: `.agents/plans/2026-10-21-ack-lane-and-control-narrowing.md` (revision 2). Section numbers below are
proposals; the unit that applies this renumbers them. 13.19's ACK_JOIN/BATCH_JOIN stay as they are until that unit
deletes them.

## 13.2 Shard count (replaces "1..6")

The offered and selected shard count N is 1..5. A preamble whose range has a maximum above 5, a minimum below 1 or
a minimum above the maximum is a parser error (close 0x1). The server's configured maximum is capped at 5. An
operator profile value of 6 (the earlier cap) is read as 5; larger values stay invalid.

## 13.3 Streams (replaces "N+2")

A session has N+3 logical streams: CONTROL (0), REALTIME (1), CHUNK shards (2..N+1) and the ACK stream (N+2).

- QUIC ids: CONTROL 0, REALTIME 4, shard k (k = 0..N-1) 1+4k, ACK 1+4N. The ACK stream is the last server-opened
  bidirectional stream, so the server opens N+1 streams; with N=5 that is six, the client's `initial_max_streams_bidi`
  for the server (6) is unchanged.
- The server opens the ACK stream after the shards and writes the auxiliary preamble `u8(2) || u8(N+2)`.
- Reverse direction (client to server): nothing but the same 1-byte guard credit as reverse CHUNK (13.13). The first
  byte read on it closes 0x11. The reverse guard reservation of 13.12 grows from 2N+1 to 2(N+1)+1 bytes.
- The ACK index N+2 is never a CHUNK index. Every per-stream structure (sequence arrays, receive slots, dependency
  validators, schedulers) is sized N+3, and loops over CHUNK shards stop at N+1.
- A second stream with the ACK index, a stream id beyond 1+4N, or a preamble naming another index closes 0x11.

## 13.7 Routing schema additions

- Lane `ack`: the ACK stream. Legal only for clientbound PLAY packets 4 (block_changed_ack) and 11
  (chunk_batch_finished), with dependency `trailing`, no position extractor and `epoch_change` false.
- Dependency `trailing`: legal only on lane `ack`.
- Dependency `ack`: legal only on lane `chunk` (with the usual single-position extractor).
- Digest: a table that uses these values has a new digest; both peers must ship the same table (a mismatch falls back
  to TCP, as today).

## 13.9 Dependencies

Let `lastAck` be the sequence of the latest record the sender assigned on the ACK stream, or 0.

- `trailing`: dependencies are the componentwise maximum of everything assigned on every stream (the GLOBAL
  footprint, ACK stream included). The record advances no frontier: not G, not C, not V. It sets `lastAck`.
- `global` and `view` keep taking the maximum over every stream; the ACK stream is now one of them, so login,
  respawn, configuration and batch_start wait for earlier ACKs.
- `ordinary`, `control`, REALTIME and plain `lane` records have no dependency on the ACK stream.
- `ack` (CHUNK only): the usual CHUNK dependencies plus `lastAck` on the ACK stream (when non-zero).

### First install exemption

For packet 45 (level_chunk_with_light) routed with `ack`, the sender keeps per session:

- the set of HELD chunk positions: add on a 45, remove on a 37 (forget_level_chunk), both carry the position;
- a ring of the last 256 forgotten positions.

A 45 omits the `ack` dependency only when its position is neither held nor in the ring. Reason: a first install
cannot meet a pending block prediction (the client cannot predict in an unloaded chunk); a replacement or a
re-install after a forget can, because a forget does not clear the client's prediction map. Packets 6
(block_entity_data) and 37 always carry `ack`.

- Held-set cap 4096. Above the cap the sender stops tracking and every 45 carries `ack` until the next epoch change.
- A 45 or 37 sent inside a bundle is not tracked; from then on every 45 carries `ack` until the next epoch change.
- On every epoch change (a route with `epoch_change` true) both the held set and the ring are cleared: the client
  drops all chunks there.
- A sender that cannot tell uses `ack`.

## 13.15 Bundles

A bundle that contains packet 4 or 11 is placed whole on CONTROL as a global fence (13.15's strongest-member rule
treats an ACK member like a CHUNK member). A bundle is never split across lanes. The ACK stream carries only
single records.

## 13.10 / 13.12 Receiver rules for the ACK stream

- A record on the ACK stream must be kind GAME, a single record (no bundle, END or join), and its route must be lane
  `ack` (ids 4 and 11). Any other id or kind, a route of another lane, or an `ack` route on any other stream closes
  0x11.
- Record cap: 64 bytes on the wire (length prefix included) and 64 bytes inflated. Above either cap closes 0x11 (not
  0x12), before a body is read where the length is known.
- A self-dependency, or any byte beyond the 1-byte guard on the reverse direction, closes 0x11.
- Resources: a protected ACK receive slot of `max(64, prefix staging)` bytes and one record, outside the shard pool
  and counted in the connection's receive reservation; partial records are bounded by that slot; the blocked-head
  deadline of 13.12 applies as for shards.
- An unseen cross-stream sequence is waited for, never rejected. Cycle rejection (13.10) is unchanged.
- Epoch drain (13.18) counts records as applied (D), not queued (Q), on every stream including the ACK stream.

## 13.20 Sender priority

The ACK stream is scheduled in the CONTROL class: it takes turns with stream 0 when both are ready, ages like a
CONTROL head, is never subject to the chunk skip counters or the REALTIME burst limit, and is never on REALTIME.
Its records are a few bytes, so it never waits behind chunk grant waits; it waits only for the records it depends on
being written.

## Why the ordering is safe (summary for the review)

- ACK before an earlier block update is the dangerous order (stale state written). `trailing` forbids exactly that.
- Block updates after an ACK may overtake it: the client only refreshes its stored server state while a prediction
  is open, and the ACK then writes the newest stored state, the same final state as TCP.
- 6, 37 and non-first 45 must not overtake a pending ACK (data lost, or stale state written over the new chunk), so
  they carry `ack`.
- Packet 72 (relative teleport), 46, 7, 36 and 57 stay CONTROL/global.
