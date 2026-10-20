# SPDX-License-Identifier: GPL-3.0-or-later
"""Hermetic contract tests for testkit/netem-attach.sh.

A fake `docker` is placed FIRST on PATH and records every argv as one JSON line; it returns
canned inspect/ps/create/start/rm/run output from a JSON state file. No Docker daemon, no
network namespace, no tc/ip and no game process is touched. The script under test and
server-netem.sh are sha256-checked before and after every test to prove the launcher never
edits them.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

_TESTS = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(os.path.dirname(_TESTS))
SCRIPT = os.path.join(_ROOT, "testkit/netem-attach.sh")
SERVER_NETEM = os.path.join(_ROOT, "testkit/netem/server-netem.sh")
TEMP = "/tmp/opencode"
PROJECT = "quicraft-arm-u3-smoke"
IMG = "quicraft-testkit-bench"
HELPER = "/usr/local/bin/server-netem.sh"
MOUNT = os.path.join(os.path.realpath(_ROOT), "testkit/netem/server-netem.sh") + ":" + HELPER + ":ro"
CID = "a" * 64
CID_OTHER = "b" * 64
NAME_RE = re.compile(r"^quicraft-netem-[0-9a-f]+$")

PS = ["ps", "--quiet", "--no-trunc",
      "--filter", "label=com.docker.compose.project=" + PROJECT,
      "--filter", "label=com.docker.compose.service=fabric",
      "--filter", "status=running"]
TARGET_FORMAT = ('{{.Id}}|{{.State.Running}}|{{index .Config.Labels "com.docker.compose.project"}}|'
                 '{{index .Config.Labels "com.docker.compose.service"}}|{{.HostConfig.NetworkMode}}|'
                 '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}')
PROBE_FORMAT = "{{.Id}}|{{.HostConfig.NetworkMode}}|{{.Name}}|{{.Config.Image}}"
INSPECT_TARGET = ["inspect", "--type", "container", "--format", TARGET_FORMAT, CID]

FAKE_DOCKER = r'''
import json, os, sys
state = json.load(open(os.environ["FAKE_DOCKER_STATE"]))
with open(os.environ["FAKE_DOCKER_LOG"], "a") as log:
    log.write(json.dumps(sys.argv[1:]) + "\n")
a = sys.argv[1:]
if not a:
    key = "unknown"
elif a[0] == "inspect":
    key = "inspect_probe" if a[-1].startswith("quicraft-netem-") else "inspect_target"
else:
    key = {"ps": "ps", "create": "create", "start": "start", "rm": "rm", "run": "run"}.get(a[0])
r = state.get(key)
if r is None:
    sys.stdout.write("")
    sys.stderr.write("fake docker: unexpected call %r\n" % (a,))
    sys.exit(99)
out = r.get("out", "")
if a:
    out = out.replace("{name}", a[-1].lstrip("/"))
sys.stdout.write(out)
sys.stderr.write(r.get("err", ""))
sys.exit(r.get("rc", 0))
'''


def _sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


class NetemAttachTest(unittest.TestCase):
    def setUp(self):
        os.makedirs(TEMP, exist_ok=True)
        self.tmp = tempfile.mkdtemp(dir=TEMP, prefix="netem-attach-test-")
        self.addCleanup(shutil.rmtree, self.tmp)
        self.before = {p: _sha256(p) for p in (SCRIPT, SERVER_NETEM)}
        self.addCleanup(self._assert_sources_unchanged)
        self.fakebin = os.path.join(self.tmp, "bin")
        os.makedirs(self.fakebin)
        self.state_path = os.path.join(self.tmp, "state.json")
        self.log_path = os.path.join(self.tmp, "calls.log")
        fake = os.path.join(self.fakebin, "docker")
        with open(fake, "w") as f:
            f.write("#!" + sys.executable + "\n" + FAKE_DOCKER)
        os.chmod(fake, 0o755)
        self.env = dict(os.environ)
        self.env["PATH"] = self.fakebin + os.pathsep + self.env.get("PATH", "")
        self.env["FAKE_DOCKER_STATE"] = self.state_path
        self.env["FAKE_DOCKER_LOG"] = self.log_path
        self.addCleanup(self._assert_container_network_only)
        self.default_state()

    def default_state(self, **overrides):
        state = {
            "ps": {"rc": 0, "out": CID + "\n"},
            "inspect_target": {"rc": 0, "out": "%s|true|%s|fabric|%s_front|172.18.0.2 "
                                        % (CID, PROJECT, PROJECT)},
            "create": {"rc": 0, "out": "probe-id\n"},
            "start": {"rc": 0, "out": "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever\n"
                                      "2: eth0    inet 172.18.0.2/16 brd 172.18.255.255 scope global eth0\\"
                                      "       valid_lft forever\n"},
            "inspect_probe": {"rc": 0, "out": "probe-id|container:" + CID + "|/{name}|" + IMG + "\n"},
            "rm": {"rc": 0, "out": ""},
            "run": {"rc": 0, "out": "configured 'loss' (loss 2%) for IPv4 TCP/UDP port 25565 "
                                    "on eth0 egress and qcbench0 ingress\n"},
        }
        state.update(overrides)
        with open(self.state_path, "w") as f:
            json.dump(state, f)
        return state

    def run_script(self, *args):
        if os.path.exists(self.log_path):
            os.remove(self.log_path)
        return subprocess.run(["/bin/sh", SCRIPT] + list(args), env=self.env,
                              capture_output=True, text=True, timeout=60)

    def calls(self):
        if not os.path.exists(self.log_path):
            return []
        with open(self.log_path) as f:
            return [json.loads(line) for line in f if line.strip()]

    def probe_name(self):
        creates = [c for c in self.calls() if c[0] == "create"]
        self.assertEqual(len(creates), 1, "expected exactly one docker create")
        return creates[0][2]

    def run_call(self):
        runs = [c for c in self.calls() if c[0] == "run"]
        self.assertEqual(len(runs), 1, "expected exactly one helper docker run")
        return runs[0]

    def assert_refused(self, res, frag):
        self.assertEqual(res.returncode, 2, "stderr: %s\nstdout: %s" % (res.stderr, res.stdout))
        self.assertIn(frag, res.stderr)
        self.assertFalse([c for c in self.calls() if c[0] == "run"],
                         "refusal must happen before the privileged helper runs")

    def assert_probe_removed(self, expected_calls):
        name = expected_calls[2][2]
        self.assertTrue(NAME_RE.match(name), "unexpected helper name %r" % name)
        for call in expected_calls:
            self.assertFalse(call[0] in ("kill", "stop", "rmi", "system", "exec"),
                             "unexpected docker subcommand %r" % (call,))
        removals = [c for c in expected_calls if c[0] == "rm"]
        self.assertTrue(removals, "probe container was not removed")
        for call in removals:
            self.assertEqual(call, ["rm", "--force", name])
        return name

    def _assert_sources_unchanged(self):
        self.assertEqual({p: _sha256(p) for p in (SCRIPT, SERVER_NETEM)}, self.before,
                         "a test or the launcher modified a source file")

    def _assert_container_network_only(self):
        for call in self.calls():
            if "--network" in call:
                self.assertEqual(call[call.index("--network") + 1].split(":", 1)[0], "container",
                                 "docker call used networking other than container:<id>: %r" % (call,))
            self.assertNotIn("--privileged", call)
            self.assertNotIn("host", [v for v in call if v in ("host",)])

    def expected_apply_calls(self, name, profile="loss", port="25565", mode="apply"):
        return [
            PS,
            INSPECT_TARGET,
            ["create", "--name", name, "--network", "container:" + CID,
             "--entrypoint", "ip", IMG, "-4", "-o", "addr", "show"],
            ["start", "--attach", name],
            ["inspect", "--type", "container", "--format", PROBE_FORMAT, name],
            ["rm", "--force", name],
            ["run", "--rm", "--network", "container:" + CID, "--cap-add", "NET_ADMIN",
             "-e", "QUICRAFT_OWNED_NETNS=1", "-v", MOUNT, "--entrypoint", "/bin/sh", IMG,
             HELPER, mode, profile, port],
        ]

    def test_apply_builds_exact_argv_and_removes_only_its_probe(self):
        self.assertTrue(os.access(SCRIPT, os.X_OK), "launcher is not executable")
        res = self.run_script("apply", "--project", PROJECT, "--profile", "loss")
        self.assertEqual(res.returncode, 0, res.stderr)
        calls = self.calls()
        self.assertEqual(len(calls), 7, calls)
        name = self.assert_probe_removed(calls)
        self.assertEqual(calls, self.expected_apply_calls(name))
        create = calls[2]
        self.assertNotIn("--rm", create, "the probe must exist to be inspected")
        self.assertNotIn("--cap-add", create, "the probe must stay non-privileged")
        self.assertNotIn("--privileged", create)
        helper = calls[-1]
        self.assertEqual(helper.count("--cap-add"), 1)
        self.assertEqual(helper[helper.index("--cap-add") + 1], "NET_ADMIN")
        self.assertIn("project=%s target=%s profile=loss port=25565" % (PROJECT, CID),
                      res.stdout)
        self.assertIn("configured 'loss'", res.stdout)

    def test_status_defaults_to_delay_and_accepts_an_explicit_profile(self):
        res = self.run_script("status", "--project", PROJECT, "--port", "25575")
        self.assertEqual(res.returncode, 0, res.stderr)
        calls = self.calls()
        name = self.assert_probe_removed(calls)
        self.assertEqual(calls, self.expected_apply_calls(name, "delay", "25575", "status"))
        self.assertEqual(calls[-1][-3:], ["status", "delay", "25575"])

        res = self.run_script("status", "--project", PROJECT, "--profile", "clean")
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(self.run_call()[-3:], ["status", "clean", "25565"])

    def test_project_name_gate_refuses_before_any_docker_call(self):
        bad = ["quicraft-arm-", "quicraft-arm-A", "quicraft-arm-a_b", "quicraft-arm-a.b",
               "quicraft-arm-a/b", "quicraft-arm-a b", "Quicraft-arm-a", "quicraft-arm-a\nb",
               "other-arm-a"]
        for project in bad:
            with self.subTest(project=project):
                res = self.run_script("apply", "--project", project, "--profile", "loss")
                self.assert_refused(res, "project must match ^quicraft-arm-[a-z0-9-]+$")
                self.assertEqual(self.calls(), [])
        with self.subTest(project="missing"):
            res = self.run_script("apply", "--profile", "loss")
            self.assert_refused(res, "usage:")
            self.assertEqual(self.calls(), [])

    def test_requires_exactly_one_running_fabric_container(self):
        rows = (
            ("none", {"ps": {"rc": 0, "out": ""}}, "no running fabric container", [PS]),
            ("two", {"ps": {"rc": 0, "out": CID + "\n" + CID_OTHER + "\n"}},
             "expected exactly one running fabric container", [PS]),
            ("short_id", {"ps": {"rc": 0, "out": "abc\n"}},
             "did not return a full 64-hex container id", [PS]),
            ("non_hex", {"ps": {"rc": 0, "out": "z" * 64 + "\n"}},
             "non-hex container id", [PS]),
            ("ps_failed", {"ps": {"rc": 1, "out": "", "err": "daemon down\n"}},
             "docker ps failed", [PS]),
        )
        for name, state, frag, expected in rows:
            with self.subTest(name=name):
                self.default_state(**state)
                res = self.run_script("apply", "--project", PROJECT, "--profile", "loss")
                self.assert_refused(res, frag)
                self.assertEqual(self.calls(), expected)

    def test_target_inspect_guards(self):
        rows = (
            ("not_running", {"rc": 0, "out": "%s|false|%s|fabric|%s_front|172.18.0.2 "
                                          % (CID, PROJECT, PROJECT)}, "is not running"),
            ("wrong_id", {"rc": 0, "out": "%s|true|%s|fabric|%s_front|172.18.0.2 "
                                       % (CID_OTHER, PROJECT, PROJECT)}, "target id mismatch"),
            ("wrong_project", {"rc": 0, "out": "%s|true|other|fabric|%s_front|172.18.0.2 "
                                            % (CID, PROJECT)}, "project label is 'other'"),
            ("wrong_service", {"rc": 0, "out": "%s|true|%s|velocity|%s_front|172.18.0.2 "
                                            % (CID, PROJECT, PROJECT)}, "service label is 'velocity'"),
            ("host_network", {"rc": 0, "out": "%s|true|%s|fabric|host|172.18.0.2 "
                                           % (CID, PROJECT)}, "network mode 'host'"),
            ("joined_network", {"rc": 0, "out": "%s|true|%s|fabric|container:%s|172.18.0.2 "
                                             % (CID, PROJECT, CID_OTHER)},
             "is not a private bridge network"),
            ("no_ipv4", {"rc": 0, "out": "%s|true|%s|fabric|%s_front| " % (CID, PROJECT, PROJECT)},
             "no IPv4 address"),
            ("bad_ipv4", {"rc": 0, "out": "%s|true|%s|fabric|%s_front|fe80::1 "
                                       % (CID, PROJECT, PROJECT)}, "non-IPv4 address"),
            ("inspect_failed", {"rc": 1, "out": "", "err": "No such object\n"},
             "docker inspect failed"),
            ("malformed", {"rc": 0, "out": "not enough fields\n"}, "unexpected docker inspect output"),
        )
        for name, inspect_state, frag in rows:
            with self.subTest(name=name):
                self.default_state(inspect_target=inspect_state)
                res = self.run_script("apply", "--project", PROJECT, "--profile", "loss")
                self.assert_refused(res, frag)
                self.assertEqual(self.calls(), [PS, INSPECT_TARGET])

    def test_probe_network_address_and_identity_guards(self):
        rows = (
            ("netmode", {"inspect_probe": {"rc": 0, "out": "probe-id|container:%s|/{name}|%s\n"
                                                             % (CID_OTHER, IMG)}},
             "probe container network mode is", True),
            ("host_netmode", {"inspect_probe": {"rc": 0, "out": "probe-id|host|/{name}|%s\n" % IMG}},
             "probe container network mode is", True),
            ("name", {"inspect_probe": {"rc": 0, "out": "probe-id|container:%s|/other|%s\n"
                                                       % (CID, IMG)}}, "probe container name is", True),
            ("image", {"inspect_probe": {"rc": 0, "out": "probe-id|container:%s|/{name}|other\n" % CID}},
             "probe container image is", True),
            ("inspect_failed", {"inspect_probe": {"rc": 1, "out": "", "err": "gone\n"}},
             "cannot inspect probe container", True),
            ("different_ip", {"start": {"rc": 0, "out": "2: eth0    inet 172.18.0.9/16 scope global eth0\\\n"}},
             "IPv4 mismatch", True),
            ("extra_ip", {"start": {"rc": 0, "out": "2: eth0    inet 172.18.0.2/16 scope global eth0\\\n"
                                                    "3: eth1    inet 172.18.0.3/16 scope global eth1\\\n"}},
             "IPv4 mismatch", True),
            ("loopback_only", {"start": {"rc": 0, "out": "1: lo    inet 127.0.0.1/8 scope host lo\\\n"}},
             "no non-loopback IPv4 address", True),
            ("start_failed", {"start": {"rc": 1, "out": "", "err": "exit 127\n"}},
             "probe container", True),
        )
        for name, state, frag, remove in rows:
            with self.subTest(name=name):
                self.default_state(**state)
                res = self.run_script("apply", "--project", PROJECT, "--profile", "loss")
                self.assert_refused(res, frag)
                if remove:
                    self.assert_probe_removed(self.calls())
                self.assertEqual(self.calls()[:2], [PS, INSPECT_TARGET])

    def test_failed_probe_create_is_never_removed(self):
        for name, state in (("create_failed", {"create": {"rc": 1, "out": "", "err": "name in use\n"}}),
                            ("create_missing_image", {"create": {"rc": 125, "out": "", "err": "no image\n"}})):
            with self.subTest(name=name):
                self.default_state(**state)
                res = self.run_script("apply", "--project", PROJECT, "--profile", "loss")
                self.assert_refused(res, "cannot create probe container")
                calls = self.calls()
                self.assertEqual(len(calls), 3, calls)
                self.assertEqual(calls[:2], [PS, INSPECT_TARGET])
                self.assertEqual(calls[2][0], "create")
                self.assertEqual([c for c in calls if c[0] == "rm"], [],
                                 "a container whose creation failed must never be removed")

    def test_probe_removal_failure_aborts_before_apply(self):
        self.default_state(rm={"rc": 1, "out": "", "err": "cannot remove\n"})
        res = self.run_script("apply", "--project", PROJECT, "--profile", "loss")
        self.assert_refused(res, "could not remove the helper container")
        name = self.probe_name()
        self.assertGreaterEqual(len([c for c in self.calls() if c[0] == "rm"]), 1)
        for call in [c for c in self.calls() if c[0] == "rm"]:
            self.assertEqual(call, ["rm", "--force", name])

    def test_helper_failure_propagates_its_exit_code(self):
        self.default_state(run={"rc": 1, "out": "", "err": "server-netem.sh: ERROR after mutation\n"})
        res = self.run_script("apply", "--project", PROJECT, "--profile", "delay")
        self.assertEqual(res.returncode, 1, res.stderr)
        self.assertIn("server-netem.sh: ERROR after mutation", res.stderr)
        self.assertIn("netem-attach.sh: ERROR: server-netem.sh exited 1", res.stderr)
        self.assertEqual(self.run_call()[-3:], ["apply", "delay", "25565"])

    def test_bad_arguments_are_rejected_before_any_docker_call(self):
        rows = (
            ([], "usage:"),
            (["bogus", "--project", PROJECT], "usage:"),
            (["apply"], "usage:"),
            (["apply", "--project", PROJECT], "usage:"),
            (["apply", "--project", PROJECT, "--profile"], "usage:"),
            (["apply", "--project", PROJECT, "--profile", "bogus"], "unknown profile 'bogus'"),
            (["apply", "--project", PROJECT, "--profile", "loss", "extra"], "usage:"),
            (["apply", "--project", PROJECT, "--profile", "loss", "--port", "0"], "usage:"),
            (["apply", "--project", PROJECT, "--profile", "loss", "--port", "65536"], "usage:"),
            (["apply", "--project", PROJECT, "--profile", "loss", "--port", "1x"], "usage:"),
            (["apply", "--project", PROJECT, "--profile", "loss", "--port", "0123"], "usage:"),
            (["status", "--project", PROJECT, "--profile", "nope"], "unknown profile 'nope'"),
            (["apply", "--project", PROJECT, "--profile", "loss", "--bogus", "x"], "usage:"),
        )
        for args, frag in rows:
            with self.subTest(args=args):
                res = self.run_script(*args)
                self.assert_refused(res, frag)
                self.assertEqual(self.calls(), [])

    def test_docker_is_only_reached_after_validation(self):
        res = self.run_script("apply", "--project", PROJECT, "--profile", "jitter",
                              "--port", "25565")
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(self.run_call()[-3:], ["apply", "jitter", "25565"])
        self.assertEqual([c[0] for c in self.calls()],
                         ["ps", "inspect", "create", "start", "inspect", "rm", "run"])


if __name__ == "__main__":
    unittest.main()
