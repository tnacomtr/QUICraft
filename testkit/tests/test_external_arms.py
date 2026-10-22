# SPDX-License-Identifier: GPL-3.0-or-later
"""Hermetic contract tests for the external-arm runner and its merge tool (unit U2).

No Docker, Gradle, game, network or repository mutation: every runner test copies
testkit/run-external-arms.sh into a private /tmp/opencode tree and serves it a PATH that holds
only fresh python stubs (docker, git, flock) plus symlinks to ordinary read-only utilities. The
stubs record argv only; a real loopback listener makes the readiness probe succeed. A copied
fabric.mod.json is narrowed and restored in place, and a fake gradlew captures the narrowed list
at invocation time and writes the sample document the contract expects. The merge tests feed
documents to merge-arm-samples.py under `python3 -I`. The real runner, merge tool and
fabric.mod.json are sha256-checked before and after every test.
"""
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest

_TESTS = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(os.path.dirname(_TESTS))
RUNNER = os.path.join(_ROOT, "testkit/run-external-arms.sh")
MERGE = os.path.join(_ROOT, "testkit/merge-arm-samples.py")
REAL_MOD = os.path.join(_ROOT, "fabric/src/gametest/resources/fabric.mod.json")
DEADLINE = os.path.join(_ROOT, "testkit/lib/deadline.sh")
SOURCES = (RUNNER, MERGE, REAL_MOD)
TEMPBASE = "/tmp/opencode"
BASH = shutil.which("bash") or "/bin/bash"
COMMIT = "0123456789abcdef0123456789abcdef01234567"
ENTRY = "rs.sudoe.quicraft.fabric.gametest.ExternalArmGameTest"
ARM_SUFFIX = {"tcp": "tcp", "quic2": "quic-2", "quic3": "quic-3"}
SAFE_TOOLS = ("mktemp", "cp", "cmp", "mkdir", "mv", "rm", "sleep", "tail", "dirname", "cat", "setsid", "timeout", "env")

STUB = r"""
import hashlib, json, os, sys

state = os.environ["FAKE_STATE"]
name = os.path.basename(sys.argv[0])
args = sys.argv[1:]


def read(rel, default=""):
    path = os.path.join(state, rel)
    if not os.path.isfile(path):
        return default
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def append(rel, line):
    with open(os.path.join(state, rel), "a", encoding="utf-8") as handle:
        handle.write(line + "\n")


append(name + ".log", "|".join([sys.argv[0]] + args))

if name == "git":
    if "rev-parse" in args:
        sys.stdout.write(read("commit.txt", "0123456789abcdef0123456789abcdef01234567\n"))
    elif "status" in args and os.path.isfile(os.path.join(state, "dirty")):
        sys.stdout.write(" M fabric/src/gametest/resources/fabric.mod.json\n")
    sys.exit(0)

if name == "flock":
    sys.exit(0)

if name == "status-ping.py":
    if os.path.isfile(os.path.join(state, "no-advertisement")):
        sys.stdout.write('{"description": "A Minecraft Server"}\n')
    else:
        sys.stdout.write('{"quicraft:quic": {"v": 1, "alpn": "quicraft/3", "port": 25565}}\n')
    sys.exit(0)

if name == "netem-attach.sh":
    sys.stdout.write("netem stub " + " ".join(args) + "\n")
    sys.exit(0)

if name == "docker":
    environment = {key: os.environ.get(key, "") for key in
                   ("FABRIC_ONLINE_MODE", "REAL_FABRIC_ONLINE_MODE",
                     "REAL_FABRIC_MULTISTREAM", "REAL_FABRIC_PORT", "QUICRAFT_JAVA_OPTS")}
    append("docker-env.jsonl", json.dumps(environment, sort_keys=True))
    if "logs" in args:
        print("fake /3 send trace phase=disconnect")
    sys.exit(0)

if name == "gradlew":
    properties = {}
    for argument in args:
        if argument.startswith("-P") and "=" in argument:
            key, value = argument[2:].split("=", 1)
            properties[key] = value
    mod = os.environ.get("QUICRAFT_ARM_MOD_JSON") or os.environ.get("FAKE_DEFAULT_MOD", "")
    if mod and os.path.isfile(mod):
        with open(mod, "rb") as handle:
            data = handle.read()
        document = json.loads(data.decode("utf-8"))
        append("mod-captures.jsonl", json.dumps({
            "path": mod,
            "sha256": hashlib.sha256(data).hexdigest(),
            "entrypoints": document["entrypoints"]["fabric-client-gametest"],
            "client": ":fabric-26x:runClientGameTest" in args,
        }))
    if ":fabric-26x:runClientGameTest" in args:
        mode = read("client-mode.txt", "ok\n").strip()
        if mode == "noout":
            sys.exit(0)
        out = properties.get("quicraft.armOut", "")
        if mode == "badjson":
            if out:
                with open(out, "w", encoding="utf-8") as handle:
                    handle.write("{not json")
            sys.exit(0)
        arm = properties.get("quicraft.arm", "tcp")
        netem = properties.get("quicraft.armNetem", "clean")
        document = {
            "clientType": "fabric",
            "arm": arm,
            "netemProfile": netem,
            "profile": netem + "-" + {"tcp": "tcp", "quic2": "quic-2",
                                      "quic3": "quic-3"}[arm],
            "server": properties.get("quicraft.armServer", ""),
            "runs": [{
                "run": int(properties.get("quicraft.armRun", "0")),
                "ok": mode != "failrow",
                "error": "observer saw TCP" if mode == "failrow" else None,
            }],
        }
        if out:
            with open(out, "w", encoding="utf-8") as handle:
                json.dump(document, handle)
        if mode == "exit1":
            sys.exit(1)
    sys.exit(0)

sys.exit(64)
"""


def sha256(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


class Fixture(unittest.TestCase):
    """Private sandbox with a copied runner, fake PATH and a real loopback listener."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(dir=TEMPBASE, prefix="quicraft-arms-")
        self.addCleanup(shutil.rmtree, self.tmp)
        self.before = {path: sha256(path) for path in SOURCES}
        self.addCleanup(self.assert_sources_unchanged)
        self.testkit = os.path.join(self.tmp, "testkit")
        os.makedirs(os.path.join(self.testkit, "docker"))
        self.runner = os.path.join(self.testkit, "run-external-arms.sh")
        shutil.copyfile(RUNNER, self.runner)
        os.chmod(self.runner, 0o755)
        for name in ("compose.yaml", "compose.real-fabric.yaml"):
            open(os.path.join(self.testkit, "docker", name), "w").close()
        for relative in ("state", "fakebin", "tmp", "out"):
            os.makedirs(os.path.join(self.tmp, relative))

        self.state = os.path.join(self.tmp, "state")
        self.fakebin = os.path.join(self.tmp, "fakebin")
        stub = os.path.join(self.fakebin, "stub")
        with open(stub, "w", encoding="utf-8") as handle:
            handle.write("#!" + sys.executable + "\n" + STUB)
        os.chmod(stub, 0o700)
        for name in ("docker", "git", "flock"):
            os.symlink(stub, os.path.join(self.fakebin, name))
        os.symlink(sys.executable, os.path.join(self.fakebin, "python3"))
        # The runner only requires xvfb-run to exist; this stub runs the command it is given.
        xvfb = os.path.join(self.fakebin, "xvfb-run")
        with open(xvfb, "w", encoding="utf-8") as handle:
            handle.write('#!/bin/sh\nwhile [ $# -gt 0 ]; do case "$1" in -a) shift;; -s) shift 2;; *) break;; esac; done\nexec "$@"\n')
        os.chmod(xvfb, 0o700)
        for name in SAFE_TOOLS:
            target = shutil.which(name)
            if target is None:
                self.skipTest("host is missing " + name)
            os.symlink(target, os.path.join(self.fakebin, name))
        os.symlink(stub, os.path.join(self.tmp, "gradlew"))
        for name in ("status-ping.py", "netem-attach.sh"):
            os.symlink(stub, os.path.join(self.testkit, name))
        # The runner sources lib/deadline.sh (its budgets), so the copied tree needs the real one.
        os.makedirs(os.path.join(self.testkit, "lib"))
        shutil.copyfile(DEADLINE, os.path.join(self.testkit, "lib", "deadline.sh"))

        self.mod = os.path.join(self.tmp, "mod.json")
        shutil.copyfile(REAL_MOD, self.mod)
        self.mod_sha = sha256(self.mod)
        self.default_mod = os.path.join(
            self.tmp, "fabric/src/gametest/resources/fabric.mod.json")
        os.makedirs(os.path.dirname(self.default_mod))
        shutil.copyfile(REAL_MOD, self.default_mod)
        self.default_sha = sha256(self.default_mod)

        self.env = {
            "PATH": self.fakebin,
            "HOME": self.tmp,
            "TMPDIR": os.path.join(self.tmp, "tmp"),
            "FAKE_STATE": self.state,
            "FAKE_DEFAULT_MOD": self.default_mod,
            "QUICRAFT_ARM_MOD_JSON": self.mod,
            "DISPLAY": ":99",
        }
        # The server advertises its internal port, so the runner accepts only 25565.
        self.port = "25565"
        self.out_dir = os.path.join(self.tmp, "out")
        self.addCleanup(self.assert_mod_restored)

    def assert_sources_unchanged(self):
        self.assertEqual({path: sha256(path) for path in SOURCES}, self.before)

    def assert_mod_restored(self):
        self.assertEqual(sha256(self.mod), self.mod_sha, "copied mod.json changed")
        self.assertEqual(sha256(self.default_mod), self.default_sha, "default mod.json changed")

    def write_state(self, name, text):
        with open(os.path.join(self.state, name), "w", encoding="utf-8") as handle:
            handle.write(text)

    def run_runner(self, argv, env=None):
        merged = dict(self.env)
        for key, value in (env or {}).items():
            if value is None:
                merged.pop(key, None)
            else:
                merged[key] = value
        return subprocess.run([BASH, self.runner, *argv], cwd=self.tmp, env=merged,
                              capture_output=True, text=True, timeout=90)

    def calls(self, name):
        path = os.path.join(self.state, name + ".log")
        if not os.path.isfile(path):
            return []
        result = []
        with open(path, encoding="utf-8") as handle:
            for line in handle.read().splitlines():
                provenance, _, rest = line.partition("|")
                self.assertTrue(
                    provenance.startswith(self.fakebin + os.sep)
                    or provenance == os.path.join(self.tmp, "gradlew"),
                    "argv did not come from a stub: " + line)
                result.append(rest.split("|"))
        return result

    def docker_environments(self):
        path = os.path.join(self.state, "docker-env.jsonl")
        if not os.path.isfile(path):
            return []
        with open(path, encoding="utf-8") as handle:
            return [json.loads(line) for line in handle.read().splitlines()]

    def captures(self):
        path = os.path.join(self.state, "mod-captures.jsonl")
        if not os.path.isfile(path):
            return []
        with open(path, encoding="utf-8") as handle:
            return [json.loads(line) for line in handle.read().splitlines()]

    def assert_no_command_ran(self):
        self.assertEqual(self.calls("docker"), [])
        self.assertEqual(self.calls("gradlew"), [])

    def active_out(self, arm="tcp", run=0, netem="clean"):
        return os.path.join(os.path.abspath(self.out_dir),
                            "%s-%s-run%s.json" % (arm, netem, run))


class RunnerArgumentsTest(Fixture):
    """Every argument refusal happens before Docker, Gradle or the git tree is touched."""

    def test_arguments_rejected_before_any_command(self):
        rows = (
            ("no_arguments", [], "missing required --arm"),
            ("bad_arm", ["--arm", "quic4", "--run", "0", "--out-dir", "out"],
             "--arm must be tcp, quic2 or quic3"),
            ("missing_run", ["--arm", "tcp", "--out-dir", "out"],
             "missing required --run"),
            ("negative_run", ["--arm", "tcp", "--run", "-1", "--out-dir", "out"],
             "--run must be a non-negative integer"),
            ("padded_run", ["--arm", "tcp", "--run", "007", "--out-dir", "out"],
             "--run must be a non-negative integer"),
            ("missing_out_dir", ["--arm", "tcp", "--run", "0"],
             "missing required --out-dir"),
            ("bad_port_zero", ["--arm", "tcp", "--run", "0", "--out-dir", "out",
                               "--port", "0"], "--port must be an integer in 1..65535"),
            ("bad_port_high", ["--arm", "tcp", "--run", "0", "--out-dir", "out",
                               "--port", "65536"], "--port must be an integer in 1..65535"),
            ("bad_play_seconds", ["--arm", "tcp", "--run", "0", "--out-dir", "out",
                                  "--play-seconds", "0"],
             "--play-seconds must be a positive integer"),
            ("unknown_netem", ["--arm", "tcp", "--run", "0", "--out-dir", "out",
                               "--netem", "lossy"],
             "--netem must be clean, loss, delay, delayloss, reorder, jitter or slow"),
            ("wrong_port", ["--arm", "tcp", "--run", "0", "--out-dir", "out",
                            "--port", "25575"],
             "--port must be 25565"),
            ("missing_value", ["--arm"], "missing value for --arm"),
            ("unknown_argument", ["--arm", "tcp", "--run", "0", "--out-dir", "out",
                                  "--bogus"], "unknown argument '--bogus'"),
            ("hop_and_move", ["--arm", "tcp", "--run", "0", "--out-dir", "out",
                              "--hop", "--move"],
             "--hop and --move are mutually exclusive"),
        )
        for name, argv, fragment in rows:
            with self.subTest(name=name):
                result = self.run_runner(argv)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn(fragment, result.stderr)
                self.assert_no_command_ran()
                self.assertEqual(sha256(self.mod), self.mod_sha)

    def test_dirty_tree_refused_before_docker(self):
        self.write_state("dirty", "1\n")
        result = self.run_runner(["--arm", "tcp", "--run", "0", "--out-dir", "out"])
        self.assertEqual(result.returncode, 1)
        self.assertIn("dirty git tree", result.stderr)
        self.assert_no_command_ran()
        self.assertEqual(sha256(self.mod), self.mod_sha)

    def test_existing_out_file_refused_before_docker(self):
        out = os.path.join(self.out_dir, "tcp-clean-run0.json")
        with open(out, "w", encoding="utf-8") as handle:
            handle.write("keep me\n")
        result = self.run_runner(["--arm", "tcp", "--run", "0", "--out-dir", "out"])
        self.assertEqual(result.returncode, 1)
        self.assertIn("refusing to overwrite existing", result.stderr)
        with open(out, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "keep me\n")
        self.assert_no_command_ran()

    def test_out_dir_that_is_a_file_refused(self):
        path = os.path.join(self.tmp, "not-a-dir")
        open(path, "w").close()
        result = self.run_runner(["--arm", "tcp", "--run", "0", "--out-dir", path])
        self.assertEqual(result.returncode, 1)
        self.assertIn("not a directory", result.stderr)
        self.assert_no_command_ran()


class RunnerRunTest(Fixture):
    """One full fake invocation: narrowing, compose contract, gradle properties, teardown."""

    def up_call(self):
        for args in self.calls("docker"):
            if "up" in args:
                return args
        self.fail("no docker compose up call")

    def down_calls(self):
        return [args for args in self.calls("docker") if "down" in args]

    def project_of(self, args):
        return args[args.index("-p") + 1]

    def assert_compose_files(self, args):
        files = [args[index + 1] for index, value in enumerate(args) if value == "-f"]
        self.assertEqual([os.path.basename(path) for path in files],
                         ["compose.yaml", "compose.real-fabric.yaml"])

    def test_clean_run_narrows_restores_and_records_contract(self):
        result = self.run_runner(["--arm", "tcp", "--run", "0", "--out-dir", self.out_dir,
                                  "--port", self.port])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OK: tcp run 0 wrote", result.stdout)
        self.assertFalse(any("stop" in args or "logs" in args for args in self.calls("docker")),
                         "baseline runs do not add diagnostic stop/log commands")

        out = self.active_out()
        self.assertTrue(os.path.isfile(out))
        with open(out, encoding="utf-8") as handle:
            document = json.load(handle)
        self.assertEqual(document["arm"], "tcp")
        self.assertTrue(document["runs"][0]["ok"])

        up = self.up_call()
        project = self.project_of(up)
        self.assertRegex(project, r"^quicraft-arm-tcp-0-[0-9a-f]{12}$")
        self.assert_compose_files(up)
        self.assertEqual(up[up.index("up"):], ["up", "-d", "--build", "fabric", "mocksession"])
        self.assertNotIn("velocity", up)
        self.assertNotIn("paper", up)

        environment = self.docker_environments()[0]
        self.assertEqual(environment["FABRIC_ONLINE_MODE"], "false")
        self.assertEqual(environment["REAL_FABRIC_ONLINE_MODE"], "false")
        self.assertEqual(environment["REAL_FABRIC_MULTISTREAM"], "true")  # tcp arm; quic2 runs false
        self.assertEqual(environment["REAL_FABRIC_PORT"], self.port)

        downs = self.down_calls()
        self.assertEqual(len(downs), 1)
        self.assertEqual(self.project_of(downs[0]), project)
        self.assertEqual(downs[0][downs[0].index("down"):],
                         ["down", "-v", "--remove-orphans"])

        captures = self.captures()
        self.assertEqual(len(captures), 3)
        for capture in captures:
            self.assertEqual(capture["path"], self.mod)
            self.assertEqual(capture["entrypoints"], [ENTRY])
            self.assertNotEqual(capture["sha256"], self.mod_sha)
        self.assertTrue(captures[-1]["client"])
        self.assertEqual(sha256(self.mod), self.mod_sha)

        gradle = self.calls("gradlew")
        self.assertEqual(len(gradle), 3)
        self.assertIn(":fabric-26x:testkitMod", gradle[0])
        self.assertIn(":fabric-26x:processGametestResources", gradle[1])
        self.assertIn("--rerun-tasks", gradle[1])
        client = gradle[2]
        self.assertIn("--no-daemon", client)
        self.assertIn(":fabric-26x:runClientGameTest", client)
        properties = {argument[2:].split("=", 1)[0]: argument.split("=", 1)[1]
                      for argument in client if argument.startswith("-P")}
        self.assertEqual(properties, {
            "quicraft.arm": "tcp",
            "quicraft.armServer": "127.0.0.1:" + self.port,
            "quicraft.armOut": out,
            "quicraft.armNetem": "clean",
            "quicraft.armRun": "0",
            "quicraft.armWarmup": "false",
            "quicraft.armCommit": COMMIT,
            "quicraft.armPlaySeconds": "20",
            "quicraft.armMove": "false",
            "quicraft.armMoveSeconds": "60",
            "quicraft.armHop": "false",
            "quicraft.armVariant": "plain",
            "quicraft.armStallDiag": "false",
        })
        self.assertEqual([name for name in os.listdir(os.path.join(self.tmp, "tmp"))
                          if name.startswith("quicraft-arm-")], [])

    def test_server_trace_environment_and_logs_survive_owned_teardown(self):
        log = os.path.join(self.out_dir, "quic3-clean-run0.server.log")
        result = self.run_runner(["--arm", "quic3", "--run", "0", "--out-dir", self.out_dir],
                                 {"QUICRAFT_JAVA_OPTS": "-Dquicraft.v3.trace=true"})
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls("docker")
        stop = next(i for i, args in enumerate(calls) if "stop" in args)
        logs = next(i for i, args in enumerate(calls) if "logs" in args)
        down = next(i for i, args in enumerate(calls) if "down" in args)
        self.assertLess(stop, logs)
        self.assertLess(logs, down)
        project = self.project_of(self.up_call())
        for index in (stop, logs, down):
            self.assertEqual(self.project_of(calls[index]), project)
        self.assertEqual(calls[stop][calls[stop].index("stop"):], ["stop", "fabric"])
        self.assertTrue(all(env["QUICRAFT_JAVA_OPTS"] == "-Dquicraft.v3.trace=true"
                            for env in self.docker_environments()))
        with open(log, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "fake /3 send trace phase=disconnect\n")

    def test_warmup_and_overrides_reach_gradle(self):
        result = self.run_runner(["--arm", "quic3", "--run", "7", "--warmup",
                                  "--play-seconds", "3", "--out-dir", self.out_dir,
                                  "--port", self.port])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(os.path.isfile(self.active_out(arm="quic3", run=7)))
        client = self.calls("gradlew")[-1]
        self.assertIn("-Pquicraft.arm=quic3", client)
        self.assertIn("-Pquicraft.armRun=7", client)
        self.assertIn("-Pquicraft.armWarmup=true", client)
        self.assertIn("-Pquicraft.armPlaySeconds=3", client)
        self.assertIn("-Pquicraft.armNetem=clean", client)
        self.assertRegex(self.project_of(self.up_call()), r"^quicraft-arm-quic3-7-[0-9a-f]{12}$")

    def test_hop_run_reaches_gradle_with_its_own_stem_and_no_move(self):
        result = self.run_runner(["--arm", "tcp", "--run", "3", "--hop",
                                  "--out-dir", self.out_dir, "--port", self.port])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(os.path.isfile(os.path.join(self.out_dir, "tcp-clean-hop-run3.json")))
        client = self.calls("gradlew")[-1]
        self.assertIn("-Pquicraft.armHop=true", client)
        self.assertIn("-Pquicraft.armMove=false", client)
        self.assertTrue(any(arg.endswith("tcp-clean-hop-run3.json") and arg.startswith("-Pquicraft.armOut=")
                            for arg in client), client)

    def test_failed_row_fails_and_still_tears_down(self):
        self.write_state("client-mode.txt", "failrow\n")
        result = self.run_runner(["--arm", "quic2", "--run", "1", "--out-dir", self.out_dir,
                                  "--port", self.port])
        self.assertEqual(result.returncode, 1)
        self.assertIn("observer saw TCP", result.stderr)
        self.assertIn("is not a successful quic2 sample", result.stderr)
        self.assertTrue(os.path.isfile(self.active_out(arm="quic2", run=1)))
        self.assertEqual(len(self.down_calls()), 1)
        self.assertEqual(sha256(self.mod), self.mod_sha)

    def test_missing_out_file_fails_and_keeps_log(self):
        self.write_state("client-mode.txt", "noout\n")
        result = self.run_runner(["--arm", "tcp", "--run", "2", "--out-dir", self.out_dir,
                                  "--port", self.port])
        self.assertEqual(result.returncode, 1)
        self.assertIn("wrote no sample", result.stderr)
        self.assertEqual(len(self.down_calls()), 1)
        # The client log is evidence next to the sample, kept on every outcome.
        self.assertTrue(os.path.isfile(os.path.join(self.out_dir, "tcp-clean-run2.client.log")))

    def test_invalid_json_out_file_fails(self):
        self.write_state("client-mode.txt", "badjson\n")
        result = self.run_runner(["--arm", "tcp", "--run", "3", "--out-dir", self.out_dir,
                                  "--port", self.port])
        self.assertEqual(result.returncode, 1)
        self.assertIn("not readable JSON", result.stderr)
        self.assertEqual(len(self.down_calls()), 1)

    def test_nonzero_client_is_accepted_when_the_row_is_ok(self):
        # A nonzero client that still wrote an ok row is the known shutdown hang: the row is the
        # evidence gate, and the acceptance says so instead of silently passing.
        self.write_state("client-mode.txt", "exit1\n")
        result = self.run_runner(["--arm", "tcp", "--run", "4", "--out-dir", self.out_dir,
                                  "--port", self.port])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("shutdown hang", result.stderr)
        self.assertTrue(os.path.isfile(self.active_out(run=4)))

    def test_default_mod_path_is_used_without_override(self):
        result = self.run_runner(["--arm", "tcp", "--run", "5", "--out-dir", self.out_dir,
                                  "--port", self.port],
                                 env={"QUICRAFT_ARM_MOD_JSON": None})
        self.assertEqual(result.returncode, 0, result.stderr)
        for capture in self.captures():
            self.assertEqual(capture["path"], self.default_mod)
            self.assertEqual(capture["entrypoints"], [ENTRY])
        self.assertEqual(sha256(self.default_mod), self.default_sha)


class MergeTest(unittest.TestCase):
    """merge-arm-samples.py under `python3 -I`: envelope, ordering, refusals, atomic write."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(dir=TEMPBASE, prefix="quicraft-arms-merge-")
        self.addCleanup(shutil.rmtree, self.tmp)
        self.before = {path: sha256(path) for path in SOURCES}
        self.addCleanup(self.assert_sources_unchanged)

    def assert_sources_unchanged(self):
        self.assertEqual({path: sha256(path) for path in SOURCES}, self.before)

    def sample(self, arm="quic3", run=0, commit=COMMIT, netem="clean",
               server="127.0.0.1:25575", ok=True, extra=None):
        document = {
            "clientType": "fabric",
            "arm": arm,
            "netemProfile": netem,
            "profile": netem + "-" + ARM_SUFFIX[arm],
            "server": server,
            "runs": [{"run": run, "ok": ok, "error": None if ok else "not ok",
                      "buildCommit": commit}],
        }
        document.update(extra or {})
        return document

    def write(self, name, document):
        path = os.path.join(self.tmp, name)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(document, handle)
        return path

    def merge(self, out, inputs):
        return subprocess.run([sys.executable, "-I", MERGE, "--out", out, *inputs],
                              capture_output=True, text=True, timeout=60)

    def merged(self):
        return os.path.join(self.tmp, "merged.json")

    def leftover_temps(self):
        return [name for name in os.listdir(self.tmp) if name.startswith(".merge-arm-samples.")]

    def test_happy_path_sorts_rows_and_preserves_the_envelope(self):
        first = self.write("run1.json", self.sample(run=1, extra={"note": "keep"}))
        second = self.write("run0.json", self.sample(run=0))
        out = self.merged()
        result = self.merge(out, [first, second])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("merged 2 run(s) from 2 document(s)", result.stdout)
        with open(out, encoding="utf-8") as handle:
            document = json.load(handle)
        self.assertEqual([row["run"] for row in document["runs"]], [0, 1])
        self.assertEqual(document["note"], "keep")
        self.assertEqual(document["arm"], "quic3")
        self.assertEqual(document["profile"], "clean-quic-3")
        self.assertEqual(document["server"], "127.0.0.1:25575")
        self.assertEqual(self.leftover_temps(), [])

    def test_mixed_envelope_refused(self):
        rows = (
            ("arm", self.sample(arm="tcp", run=0), self.sample(arm="quic2", run=1)),
            ("netem", self.sample(netem="clean", run=0),
             self.sample(netem="loss", run=1)),
            ("server", self.sample(run=0, server="127.0.0.1:25575"),
             self.sample(run=1, server="127.0.0.1:25576")),
        )
        for name, left, right in rows:
            with self.subTest(name=name):
                out = os.path.join(self.tmp, "mixed-%s.json" % name)
                a = self.write(name + "-0.json", left)
                b = self.write(name + "-1.json", right)
                result = self.merge(out, [a, b])
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn("does not share", result.stderr)
                self.assertFalse(os.path.exists(out))

    def test_duplicate_run_index_refused(self):
        a = self.write("dup-a.json", self.sample(run=3))
        b = self.write("dup-b.json", self.sample(run=3))
        result = self.merge(self.merged(), [a, b])
        self.assertEqual(result.returncode, 1)
        self.assertIn("duplicate run index 3", result.stderr)
        self.assertFalse(os.path.exists(self.merged()))

    def test_mixed_commit_refused(self):
        a = self.write("commit-a.json", self.sample(run=0, commit="a" * 40))
        b = self.write("commit-b.json", self.sample(run=1, commit="b" * 40))
        result = self.merge(self.merged(), [a, b])
        self.assertEqual(result.returncode, 1)
        self.assertIn("mixed build commit", result.stderr)
        self.assertFalse(os.path.exists(self.merged()))

    def test_overwrite_refused_and_original_kept(self):
        source = self.write("one.json", self.sample(run=0))
        out = self.merged()
        with open(out, "w", encoding="utf-8") as handle:
            handle.write("sentinel\n")
        result = self.merge(out, [source])
        self.assertEqual(result.returncode, 1)
        self.assertIn("refusing to overwrite existing", result.stderr)
        with open(out, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "sentinel\n")
        self.assertEqual(self.leftover_temps(), [])

    def test_invalid_inputs_refused_without_writing(self):
        invalid = os.path.join(self.tmp, "invalid.json")
        with open(invalid, "w", encoding="utf-8") as handle:
            handle.write("{not json")
        rows = (
            ("invalid_json", [invalid], "is not valid JSON"),
            ("missing_file", [os.path.join(self.tmp, "absent.json")], "cannot read"),
            ("not_an_object", [self.write("array.json", [1, 2])], "is not a JSON object"),
            ("empty_runs", [self.write("empty.json",
                                       dict(self.sample(), runs=[]))], "has no runs array"),
            ("bad_run", [self.write("bad-run.json",
                                    dict(self.sample(), runs=[{"run": -1,
                                                               "buildCommit": COMMIT}]))],
             "run must be a non-negative integer"),
            ("missing_commit", [self.write("no-commit.json",
                                           dict(self.sample(), runs=[{"run": 0}]))],
             "buildCommit must be a nonempty string"),
        )
        for name, inputs, fragment in rows:
            with self.subTest(name=name):
                out = os.path.join(self.tmp, "bad-%s.json" % name)
                result = self.merge(out, inputs)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn(fragment, result.stderr)
                self.assertFalse(os.path.exists(out))
                self.assertEqual(self.leftover_temps(), [])

    def test_missing_out_argument_is_a_usage_error(self):
        result = subprocess.run([sys.executable, "-I", MERGE], capture_output=True, text=True,
                                timeout=60)
        self.assertEqual(result.returncode, 2)
        self.assertIn("--out", result.stderr)


if __name__ == "__main__":
    unittest.main()
