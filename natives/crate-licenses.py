# SPDX-License-Identifier: GPL-3.0-or-later
"""Licence inventory for the Rust crates in QUICraft's patched quiche native.

Checks every crate the native links against CLAUDE.md's licence table, copies each linked
crate's licence files to --dest (Netty's pom then packs them into META-INF/license/), and writes
a deterministic INVENTORY.txt there plus a Markdown report.

For "A OR B" the first option on the table is elected (Apache-2.0, then MIT, then
BSD-2-Clause). An "AND" needs every part on the table. Exit status 1 if a linked crate has a
licence off the table: the build must stop and the user decide (CLAUDE.md "Licensing").
Build-time-only crates (build scripts, proc macros, bindgen) are reported, not failed.

Python 3.6 compatible (AlmaLinux 8's platform-python).
"""
import argparse
import json
import os
import re
import shutil
import sys

# CLAUDE.md "Licensing" table, as it applies to native code we ship.
ALLOWED = ["Apache-2.0", "MIT", "BSD-2-Clause"]
LICENSE_FILE = re.compile(r"^(licen[cs]e|copying|notice|unlicense)", re.IGNORECASE)


def parse(expr):
    """SPDX expression -> list of alternatives, each a list of licence ids (DNF)."""
    tokens = re.findall(r"\(|\)|[^\s()]+", expr.replace("/", " OR "))
    pos = [0]

    def peek():
        return tokens[pos[0]] if pos[0] < len(tokens) else None

    def take():
        pos[0] += 1
        return tokens[pos[0] - 1]

    def factor():
        if peek() == "(":
            take()
            alts = disjunction()
            if take() != ")":
                raise ValueError("unbalanced: " + expr)
            return alts
        ident = take()
        if peek() == "WITH":
            take()
            ident = ident + " WITH " + take()
        return [[ident]]

    def conjunction():
        alts = factor()
        while peek() == "AND":
            take()
            right = factor()
            alts = [a + b for a in alts for b in right]
        return alts

    def disjunction():
        alts = conjunction()
        while peek() == "OR":
            take()
            alts = alts + conjunction()
        return alts

    result = disjunction()
    if peek() is not None:
        raise ValueError("trailing tokens: " + expr)
    return result


def elect(expr):
    """Returns the elected alternative (list of ids) or None if no alternative is on the table."""
    if not expr:
        return None
    ok = [alt for alt in parse(expr) if all(lic in ALLOWED for lic in alt)]
    if not ok:
        return None
    return min(ok, key=lambda alt: (max(ALLOWED.index(lic) for lic in alt), len(alt)))


def crate_key(line):
    parts = line.split()
    return parts[0], parts[1].lstrip("v")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--metadata", required=True)
    ap.add_argument("--linked", required=True)
    ap.add_argument("--all", required=True)
    ap.add_argument("--workspace-license", required=True)
    ap.add_argument("--dest", required=True)
    ap.add_argument("--report", required=True)
    args = ap.parse_args()

    with open(args.metadata) as f:
        packages = {(p["name"], p["version"]): p for p in json.load(f)["packages"]}

    def read(path):
        with open(path) as f:
            return sorted({crate_key(line) for line in f if line.strip()})

    linked = read(args.linked)
    build_only = [c for c in read(args.all) if c not in set(linked)]

    if os.path.isdir(args.dest):
        shutil.rmtree(args.dest)
    os.makedirs(args.dest)

    problems = []
    inventory = []
    rows = []
    for kind, crates in (("linked", linked), ("build-only", build_only)):
        for name, version in crates:
            pkg = packages[(name, version)]
            expr = pkg.get("license") or ""
            elected = elect(expr)
            verdict = " AND ".join(elected) if elected else "NOT ON THE TABLE"
            rows.append((kind, name, version, expr or "(none; license-file)", verdict))
            if kind != "linked":
                continue
            if elected is None:
                problems.append("%s %s: %s" % (name, version, expr or pkg.get("license_file")))
            crate_dir = os.path.dirname(pkg["manifest_path"])
            files = sorted(f for f in os.listdir(crate_dir)
                           if LICENSE_FILE.match(f) and os.path.isfile(os.path.join(crate_dir, f)))
            if pkg.get("license_file"):
                files = sorted(set(files) | {pkg["license_file"]})
            target = os.path.join(args.dest, "%s-%s" % (name, version))
            os.makedirs(target)
            if files:
                for f in files:
                    shutil.copyfile(os.path.join(crate_dir, f), os.path.join(target, os.path.basename(f)))
            elif pkg.get("source") is None:
                # quiche workspace member without its own file: the repository's COPYING applies.
                shutil.copyfile(args.workspace_license, os.path.join(target, "COPYING"))
            else:
                problems.append("%s %s: no licence file in the crate" % (name, version))
            inventory.append("%s %s | %s | elected: %s" % (name, version, expr, verdict))

    with open(os.path.join(args.dest, "INVENTORY.txt"), "w") as f:
        f.write("Rust crates statically linked into QUICraft's netty-codec-native-quic build\n")
        f.write("(quiche features: ffi qlog custom-client-dcid; all targets).\n")
        f.write("Each crate's licence files are in the directory of the same name.\n")
        f.write("For dual-licensed crates, QUICraft uses the crate under the elected licence.\n\n")
        f.write("crate version | licence expression | elected\n")
        for line in inventory:
            f.write(line + "\n")

    with open(args.report, "w") as f:
        f.write("| Kind | Crate | Version | Licence | Elected |\n| --- | --- | --- | --- | --- |\n")
        for row in rows:
            f.write("| %s | %s | %s | %s | %s |\n" % row)
    flagged = [r for r in rows if r[0] == "build-only" and r[4] == "NOT ON THE TABLE"]
    print("crates: %d linked, %d build-only" % (len(linked), len(build_only)))
    for r in flagged:
        print("NOTE build-only crate off the table (not shipped): %s %s: %s" % (r[1], r[2], r[3]))
    if problems:
        print("STOP: linked crates with a licence off CLAUDE.md's table:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
