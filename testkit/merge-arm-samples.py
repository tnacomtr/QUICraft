#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Merge per-run external-arm sample documents into one document.

`testkit/run-external-arms.sh` writes exactly one document per invocation, with one row in
`runs`. A benchmark sample for one arm and netem profile is the concatenation of those rows under
the same envelope (plan `.agents/plans/2026-10-20-external-arms.md`):

    {"clientType":"fabric","arm":"quic3","netemProfile":"clean","profile":"clean-quic-3",
     "server":"127.0.0.1:25575","runs":[<RunResult row>, ...]}

All inputs must agree on clientType, arm, netemProfile, profile, server and build commit; run
indexes must be unique. Rows are written in ascending run order. The output is written atomically
and an existing output file is never overwritten. Extra envelope fields are kept from the first
input.

For `quic3`, the merged file must then pass the testkit validator (see testkit/README.md):

    ./gradlew :testkit:installDist
    testkit/build/install/quicraft-testkit/bin/quicraft-testkit v3-samples --in <merged.json>

Usage:
    python3 -I testkit/merge-arm-samples.py --out MERGED.json RUN0.json RUN1.json ...
"""

import argparse
import json
import os
import sys
import tempfile

FABRIC = "fabric"
ARM_SUFFIX = {"tcp": "tcp", "quic2": "quic-2", "quic3": "quic-3"}
ENVELOPE_FIELDS = ("clientType", "arm", "netemProfile", "profile", "server")


class Refused(Exception):
    """A document set that must not be merged."""


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Merge per-run external-arm sample documents into one document.",
        epilog="The merged document is never overwritten and is written atomically.",
    )
    parser.add_argument("--out", required=True, metavar="FILE",
                        help="output JSON file; must not exist yet")
    parser.add_argument("inputs", nargs="+", metavar="RUN_JSON",
                        help="one per-run sample document, in any order")
    return parser.parse_args(argv)


def load_document(path):
    try:
        with open(path, encoding="utf-8") as handle:
            document = json.load(handle)
    except OSError as error:
        raise Refused(f"cannot read {path}: {error}")
    except ValueError as error:
        raise Refused(f"{path} is not valid JSON: {error}")
    if not isinstance(document, dict):
        raise Refused(f"{path} is not a JSON object")
    return document


def check_document(path, document):
    """Return (envelope key tuple, rows) after checking one per-run document."""
    for name in ENVELOPE_FIELDS:
        value = document.get(name)
        if not isinstance(value, str) or not value:
            raise Refused(f"{path} has no nonempty string field {name!r}")
    if document["clientType"] != FABRIC:
        raise Refused(f"{path} clientType is {document['clientType']!r}, expected {FABRIC!r}")
    arm = document["arm"]
    if arm not in ARM_SUFFIX:
        raise Refused(f"{path} has unknown arm {arm!r}")
    expected_profile = f"{document['netemProfile']}-{ARM_SUFFIX[arm]}"
    if document["profile"] != expected_profile:
        raise Refused(
            f"{path} profile is {document['profile']!r}, expected {expected_profile!r} for arm {arm!r}")
    rows = document.get("runs")
    if not isinstance(rows, list) or not rows:
        raise Refused(f"{path} has no runs array or it is empty")
    for index, row in enumerate(rows):
        where = f"{path} runs[{index}]"
        if not isinstance(row, dict):
            raise Refused(f"{where} is not a JSON object")
        run = row.get("run")
        if type(run) is not int or run < 0:
            raise Refused(f"{where}.run must be a non-negative integer")
        commit = row.get("buildCommit")
        if not isinstance(commit, str) or not commit:
            raise Refused(f"{where}.buildCommit must be a nonempty string")
    return tuple(document[name] for name in ENVELOPE_FIELDS), rows


def merge(paths):
    envelope = None
    first = None
    rows = []
    seen_runs = {}
    build_commit = None
    for path in paths:
        document = load_document(path)
        current_envelope, current_rows = check_document(path, document)
        if envelope is None:
            envelope = current_envelope
            first = document
        elif current_envelope != envelope:
            labels = ", ".join(ENVELOPE_FIELDS)
            raise Refused(
                f"{path} does not share the first document's {labels}: "
                f"{(current_envelope, envelope)!r}")
        for row in current_rows:
            run = row["run"]
            if run in seen_runs:
                raise Refused(
                    f"duplicate run index {run}: {path} and {seen_runs[run]}")
            seen_runs[run] = path
            commit = row["buildCommit"]
            if build_commit is None:
                build_commit = (commit, path)
            elif commit != build_commit[0]:
                raise Refused(
                    f"mixed build commit {commit!r}: {path} and {build_commit[1]}")
            rows.append(row)
    if envelope is None or first is None:
        raise Refused("no input documents")
    rows.sort(key=lambda row: row["run"])
    return first, rows


def write_atomic(path, document):
    """Create path with an atomic link from a same-directory temp file; never overwrite."""
    out_dir = os.path.dirname(os.path.abspath(path)) or "."
    if os.path.lexists(path):
        raise Refused(f"refusing to overwrite existing {path}")
    try:
        os.makedirs(out_dir, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(
            dir=out_dir, prefix=".merge-arm-samples.", suffix=".tmp")
    except OSError as error:
        raise Refused(f"cannot prepare {path}: {error}")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(document, handle, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError:
            raise Refused(f"refusing to overwrite existing {path}")
        except OSError as error:
            raise Refused(f"cannot create {path} atomically: {error}")
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def main(argv):
    args = parse_args(argv)
    try:
        first, rows = merge(args.inputs)
        # Same envelope as the first input (including any extra fields), one runs array.
        document = {name: value for name, value in first.items() if name != "runs"}
        document["runs"] = rows
        write_atomic(args.out, document)
    except Refused as error:
        print(f"merge-arm-samples: {error}", file=sys.stderr)
        return 1
    print(f"merged {len(rows)} run(s) from {len(args.inputs)} document(s) into {args.out}"
          f" (arm={document['arm']}, profile={document['profile']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
