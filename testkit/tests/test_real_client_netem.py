# SPDX-License-Identifier: GPL-3.0-or-later
"""Fake-command contract simulation for the two new benchmark scripts (unit contract, not a
runtime proof of packet shaping). Each run copies the two new task scripts plus the existing
testkit/netem/profiles.sh into its own private /tmp/opencode temp dir, rebasing ONLY the
authorized absolute literals (/.dockerenv, /opt/testkit/netem-profiles.sh, /proc/self/mountinfo,
/proc/self/ns/net, /srv, /usr/local/bin/fabric-entrypoint.sh) to private fixture paths; the
rebase is proven literal-only by un-rebasing and comparing to the source. A HERMETIC PATH with
fresh python3 stubs (ip/tc/readlink/mkdir/fabric-entrypoint) serves every invocation, logs argv
only, returns fixture text/state and can never reach a real binary; provenance is checked from
each stub's own $0 log prefix. Sources are sha256-checked before/after every test. No Docker,
game, network, kernel, tc or ip execution; no YAML dependency; a real compose parse stays with
the orchestrator."""
import hashlib, os, re, shutil, subprocess, sys, tempfile, unittest

_TESTS = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(os.path.dirname(_TESTS))
SRC_NETEM = os.path.join(_ROOT, "testkit/netem/server-netem.sh")
SRC_ENTRY = os.path.join(_ROOT, "testkit/docker/fabric/real-client-entrypoint.sh")
SRC_PROFILES = os.path.join(_ROOT, "testkit/netem/profiles.sh")
SRC_COMPOSE = os.path.join(_ROOT, "testkit/docker/compose.real-fabric.yaml")
SOURCES = (SRC_NETEM, SRC_ENTRY, SRC_PROFILES, SRC_COMPOSE)
TEMPBASE, SH, PORT, IFB = "/tmp/opencode", "/bin/sh", "25575", "qcbench0"
MUTATING = re.compile(r"^(?:link (?:set|add)|(?:qdisc|filter) add)\b")
DESTRUCTIVE = re.compile(r"\b(?:rm|rmdir|del|delete|flush|remove|replace|change|sudo"
                         r"|modprobe|rmmod|lsmod|iptables|nft)\b")
FALLBACK_BIN = re.compile(r"/s?bin/(?:ip|tc)\b")
_MOUNTINFO_CLEAN = "18 2 0:18 / / ro,relatime - proc proc rw\n"

FAKE_BODY = r"""
import os, sys
ST, NAME, A = os.environ["FAKE_STATE"], os.path.basename(sys.argv[0]), sys.argv[1:]
def rd(n, d=""):
    p = os.path.join(ST, n)
    return open(p).read() if os.path.isfile(p) else d
def unexpected():
    open(os.path.join(ST, "unexpected.txt"), "a").write(NAME + "|" + " ".join(A) + "\n")
    sys.stdout.write(NAME + ": FAKE-UNEXPECTED: " + " ".join(A) + "\n")
    sys.exit(64)
with open(os.path.join(ST, "log_" + NAME + ".txt"), "a") as f:
    f.write(sys.argv[0] + "|" + " ".join(A) + "\n")
FAIL, DEAD = rd("fail_at").strip(), rd("dead_dev").strip()
if NAME == "readlink":
    if rd("readlink_fail"):
        sys.exit(1)
    sys.stdout.write(rd("netns.txt", "net:[4026531993]\n"))
    sys.exit(0)
if NAME == "mkdir":
    root = os.environ["FAKE_ROOT"]
    for t in [x for x in A if not x.startswith("-")]:
        ap = os.path.abspath(t)
        if ap != root and not ap.startswith(root + os.sep):
            unexpected()
        os.makedirs(ap, exist_ok=True)
    sys.exit(0)
if NAME == "fabric-entrypoint":
    with open(os.path.join(ST, "log_entry.txt"), "a") as f:
        f.write("argv " + " ".join(A) + "\n")
        for k in ("JAVA_OPTS", "FABRIC_ONLINE_MODE", "REAL_FABRIC_MULTISTREAM"):
            f.write(k + "=" + (os.environ.get(k) or "<unset>") + "\n")
    sys.exit(0)
if NAME == "ip":
    if A[:4] == ["-4", "route", "show", "default"]:
        sys.stdout.write(rd("routes.txt", ""))
        sys.exit(0)
    if A[:3] == ["-d", "link", "show"] or A[:2] == ["-d", "link"]:
        sys.stdout.write(rd("link_dev.txt", "2: eth0: <UP> mtu 1500 state UP\n"))
        sys.exit(0)
    if A[:2] == ["link", "show"] and "qcbench0" in A:
        if not os.path.isfile(os.path.join(ST, "ifb_exists")):
            sys.exit(1)
        sys.stdout.write(rd("link_ifb.txt", "5: qcbench0: <UP> mtu 1500\n"))
        sys.exit(0)
    if A[:2] == ["link", "set"]:
        sys.exit(0)
    if A[:2] == ["link", "add"] and "qcbench0" in A:
        if FAIL == "ifb":
            sys.stderr.write("ip: RTNETLINK answers: Operation not permitted\n")
            sys.exit(1)
        open(os.path.join(ST, "ifb_exists"), "w").close()
        sys.exit(0)
    unexpected()
if NAME == "tc":
    if A[:3] == ["qdisc", "show", "dev"]:
        if DEAD and A[3] == DEAD:
            sys.stderr.write("tc: No such device\n")
            sys.exit(1)
        sys.stdout.write(rd("qdisc_dev.txt", "qdisc noqueue state UP mode DEFAULT group default qlen 1000\n"))
        sys.exit(0)
    if A[:4] == ["-s", "qdisc", "show", "dev"]:
        if FAIL == "statdump":
            sys.stderr.write("tc: cannot dump\n")
            sys.exit(1)
        text = (rd("qdisc_ifb.txt", "qdisc netem 30: state UNKNOWN\n") if A[4] == "qcbench0"
            else rd("qdisc_dev_dump.txt", "qdisc prio 1: root state UNKNOWN\n"))
        sys.stdout.write(text)
        sys.exit(0)
    if A[:3] == ["-s", "filter", "show"] or A[:2] == ["filter", "show"]:
        sys.stdout.write(rd("filters_ffff.txt", "") if "ffff:" in A else rd("filters_1.txt", ""))
        sys.exit(0)
    if A[:2] == ["qdisc", "add"]:
        if FAIL == "root" and "prio" in A and "bands" in A:
            sys.exit(1)
        if FAIL == "ingress" and "ingress" in A:
            sys.exit(1)
        sys.exit(0)
    if A[:2] == ["filter", "add"]:
        if FAIL == "ingressfilter" and "ffff:" in A:
            sys.exit(1)
        sys.exit(0)
    unexpected()
sys.exit(0)
"""


def _sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def _read(path):
    return open(path).read()


class ToolFixture(unittest.TestCase):
    """Private hermetic sandbox; all fixture artifacts stay inside one temp dir."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(dir=TEMPBASE, prefix="quicraft-realclient-")
        self.addCleanup(shutil.rmtree, self.tmp)
        self.before = {p: _sha256(p) for p in SOURCES}
        self.addCleanup(self.assert_sources_unchanged)
        self.fake, self.state = self.makedir("fakes"), self.makedir("state")
        self.srv_path, self._srv_n = self.makedir("srv"), 0
        self.netem = self.entry = None
        self.stubbin = os.path.join(self.fake, "fakebin")
        open(self.stubbin, "w").write("#!" + sys.executable + "\n" + FAKE_BODY)
        os.chmod(self.stubbin, 0o700)
        self.default_state = {
            "state/netns.txt": "net:[4026531993]\n",
            "state/routes.txt": "default via 10.0.0.1 dev eth0\n",
            "state/qdisc_dev.txt": "qdisc noqueue state UP mode DEFAULT group default qlen 1000\n",
            "state/link_dev.txt": "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n"}
        for rel, text in (("ns/net", "net:[4026531993]\n"),
                          ("pkg/.dockerenv", "docker-env-marker\n"),
                          ("ns/mountinfo", _MOUNTINFO_CLEAN)) + tuple(
                          self.default_state.items()):
            self.write(rel, text)

    def makedir(self, rel):
        path = os.path.join(self.tmp, rel)
        os.makedirs(path, exist_ok=True)
        return path

    def write(self, rel, text):
        path = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, "w").write(text)
        return path

    ALL_TOOLS = ("ip", "tc", "readlink", "mkdir", "fabric-entrypoint")

    def run_sh(self, script, args=(), env=None, tools=("ip", "tc", "readlink")):
        # PATH holds only the fake dir; each run exposes exactly the requested stubs and
        # removes leftovers from earlier runs, so a missing-tool case is really missing.
        for name in self.ALL_TOOLS:
            link = os.path.join(self.fake, name)
            exists = os.path.lexists(link)
            if name in tools and not exists:
                os.symlink(self.stubbin, link)
            elif name not in tools and exists:
                os.remove(link)
        base = {"PATH": self.fake, "FAKE_STATE": self.state, "FAKE_ROOT": self.tmp,
                "QUICRAFT_OWNED_NETNS": "1"}
        base.update(env or {})
        return subprocess.run([SH, script, *args], env=base, capture_output=True,
                              text=True, cwd=self.tmp, timeout=60)

    def clear_logs(self):
        for name in os.listdir(self.state):
            if name.startswith("log_") or name in ("fail_at", "ifb_exists", "unexpected.txt",
                                                   "dead_dev", "readlink_fail"):
                os.remove(os.path.join(self.state, name))

    def rebase_copy(self, src, name, profiles_path=None):
        repl = {"/.dockerenv": os.path.join(self.tmp, "pkg/.dockerenv"),
                "/opt/testkit/netem-profiles.sh": profiles_path or os.path.join(
                    self.tmp, "pkg/netem-profiles.sh"),
                "/proc/self/mountinfo": os.path.join(self.tmp, "ns/mountinfo"),
                "/proc/self/ns/net": os.path.join(self.tmp, "ns/net"),
                "/srv": self.srv_path,
                "/usr/local/bin/fabric-entrypoint.sh": os.path.join(self.fake, "fabric-entrypoint")}
        text = _read(src)
        for literal, path in repl.items():
            text = text.replace(literal, path)
        dst = os.path.join(self.tmp, name)
        open(dst, "w").write(text)
        os.chmod(dst, 0o700)
        self.assertNotRegex(text, FALLBACK_BIN, "absolute ip/tc in the copy of %s" % name)
        self.last_rebase = repl
        return dst

    def assert_rebase_only_swaps_literals(self, dst, src):
        original = _read(dst)
        text = original
        for literal, path in self.last_rebase.items():
            self.assertEqual(_read(src).count(literal), original.count(path),
                             "%s replaced %s partially" % (dst, literal))
            text = text.replace(path, literal)
        self.assertEqual(_read(src), text, "rebase changed more than the authorized literals")

    def callargs(self, name):
        path = os.path.join(self.state, "log_%s.txt" % name)
        out = []
        for line in ([l for l in _read(path).splitlines() if l] if os.path.isfile(path) else []):
            provenance, _, joined = line.partition("|")
            self.assertTrue(provenance.startswith(self.fake + os.sep),
                            "argv did not come from the fake dir: %s" % line)
            out.append(joined)
        return out

    def assert_stub_contract(self):
        stray = os.path.join(self.state, "unexpected.txt")
        stray_text = _read(stray).strip() if os.path.isfile(stray) else ""
        self.assertFalse(stray_text, "stub hit unrecognized argv: " + stray_text)
        for name in ("readlink", "ip", "tc", "mkdir", "fabric-entrypoint"):
            self.assertFalse(any(DESTRUCTIVE.search(a) for a in self.callargs(name)), name)

    def assert_no_mutation(self):
        for name in ("ip", "tc"):
            self.assertFalse(any(MUTATING.match(a) for a in self.callargs(name)), name)

    def assert_sources_unchanged(self):
        self.assertEqual({p: _sha256(p) for p in SOURCES}, self.before)

    def stage_netem(self, profile_mode="copy"):
        profiles = os.path.join(self.tmp, "pkg/netem-profiles.sh")
        self.netem = self.rebase_copy(SRC_NETEM, "server-netem.sh", profiles_path=profiles)
        if profile_mode == "copy":
            shutil.copyfile(SRC_PROFILES, profiles)
            self.assertEqual(_read(SRC_PROFILES), _read(profiles), "profiles copy altered")
        elif profile_mode == "symlink":
            if os.path.lexists(profiles):
                os.remove(profiles)
            os.symlink(SRC_PROFILES, profiles)
        elif os.path.lexists(profiles):
            os.remove(profiles)
        return self.netem


class NetemTest(ToolFixture):
    """Fake-command contract of testkit/netem/server-netem.sh over the stub-only PATH."""

    def case(self, argv, *, tools=("ip", "tc", "readlink"), env=None, state=None,
             profile_mode="copy"):
        self.stage_netem(profile_mode)
        self.clear_logs()
        for rel, text in self.default_state.items():
            self.write(rel, text)
        for rel, text in (state or {}).items():
            self.write("state/" + rel, text)
        res = self.run_sh(self.netem, list(argv), env=env, tools=tools)
        self.assert_stub_contract()
        return res

    def assert_refused(self, res, frag):
        self.assertEqual(res.returncode, 2)
        self.assertIn(frag, res.stderr)
        self.assert_no_mutation()

    def test_refusals_happen_before_any_mutating_command(self):
        usage = "usage: server-netem.sh"
        rows = (
            ("arity_zero", [], usage, {}, True),
            ("arity_two", ["apply", "clean"], usage, {}, True),
            ("arity_four", ["apply", "clean", PORT, "extra"], usage, {}, True),
            ("bad_mode", ["applyx", "clean", PORT], usage, {}, True),
            ("port_0", ["apply", "clean", "0"], usage, {}, True),
            ("port_negative", ["apply", "clean", "-1"], usage, {}, True),
            ("port_65536", ["apply", "clean", "65536"], usage, {}, True),
            ("port_leading_zero", ["apply", "clean", "080"], usage, {}, True),
            ("port_non_digit", ["apply", "clean", "abc"], usage, {}, True),
            ("port_50_digits_no_length_guard", ["apply", "clean", "1" + "0" * 49], usage, {}, True),
            ("unknown_profile", ["apply", "bogus", PORT], "unknown PROFILE 'bogus'", {}, True),
            ("missing_default_route", ["apply", "delay", PORT],
             "no IPv4 default route in this namespace", {"state": {"routes.txt": ""}}, False),
            ("multiple_default_routes", ["apply", "delay", PORT],
             "more than one IPv4 default route; refusing to guess the device",
             {"state": {"routes.txt": "default via 10.0.0.1 dev eth0\ndefault via 10.0.0.2 dev eth1\n"}},
             False),
            ("unknown_default_dev", ["apply", "delay", PORT], "cannot inspect qdiscs on eth9",
             {"state": {"routes.txt": "default via 10.0.0.1 dev eth9\n", "dead_dev": "eth9\n"}},
             False),
            ("stale_non_noqueue_root", ["apply", "delay", PORT], "root qdisc on eth0 is not fresh noqueue",
             {"state": {"qdisc_dev.txt": "qdisc pfifo_fast 0: bands 3\n"}}, False),
            ("stale_ingress_qdisc", ["apply", "delay", PORT],
             "existing ingress/clsact qdisc on eth0; refusing stale state",
             {"state": {"qdisc_dev.txt": "qdisc ingress ffff: filter\n"}}, False),
            ("stale_existing_ifb", ["apply", "delay", PORT],
             "existing qcbench0 interface; refusing stale state",
             {"state": {"ifb_exists": ""}}, False),
        )
        for name, argv, frag, kw, nocommand in rows:
            with self.subTest(name=name):
                res = self.case(argv, **kw)
                self.assertEqual(res.returncode, 2)
                self.assertIn(frag, res.stderr)
                self.assert_no_mutation()
                if nocommand:
                    self.assertEqual(self.callargs("ip") + self.callargs("tc")
                                     + self.callargs("readlink"), [])

    def test_guards_refuse_without_dockerenv_owned_netns_tools_or_profiles(self):
        argv = ["apply", "clean", PORT]
        dockenv = os.path.join(self.tmp, "pkg/.dockerenv")
        os.remove(dockenv)
        with self.subTest(name="missing_dockerenv"):
            self.assert_refused(self.case(argv),
                                "refusing to run outside the testkit helper container")
        self.write("pkg/.dockerenv", "docker-env-marker\n")
        with self.subTest(name="owned_netns_not_one"):
            self.assert_refused(self.case(argv, env={"QUICRAFT_OWNED_NETNS": ""}),
                                "QUICRAFT_OWNED_NETNS=1 is required")
        with self.subTest(name="ip_missing"):
            self.assert_refused(self.case(argv, tools=("readlink", "tc")),
                                "ip (iproute2) is not installed")
        with self.subTest(name="tc_missing"):
            self.assert_refused(self.case(argv, tools=("readlink", "ip")),
                                "tc (iproute2) is not installed")
        with self.subTest(name="profiles_missing"):
            self.assert_refused(self.case(argv, profile_mode="missing"), "is missing")
        with self.subTest(name="profiles_symlink"):
            self.assert_refused(self.case(argv, profile_mode="symlink"),
                                "must not be a symlink")

    def test_clean_apply_shapes_nothing_and_reads_only(self):
        res = self.case(["apply", "clean", PORT])
        self.assertEqual(res.returncode, 0)
        self.assertIn("profile: clean", res.stdout)
        self.assertIn("internal_port: " + PORT, res.stdout)
        self.assertIn("clean: namespace left unshaped; no qdisc, filter, IFB or offload change applied",
                      res.stdout)
        self.assertEqual(self.callargs("ip"), ["-4 route show default",
                                               "link show dev qcbench0",
                                               "-d link show dev eth0"])
        self.assertEqual(self.callargs("tc"), ["qdisc show dev eth0", "-s qdisc show dev eth0"])
        self.assertEqual(self.callargs("readlink"), [os.path.join(self.tmp, "ns/net")])

    def test_impaired_apply_builds_exact_root_netem_and_four_filters(self):
        res = self.case(["apply", "delay", PORT])
        self.assertEqual(res.returncode, 0)
        self.assertIn("configured 'delay' (delay 75ms) for IPv4 TCP/UDP port 25575 on eth0 egress and qcbench0 ingress",
                      res.stdout)
        self.assertEqual(self.callargs("tc"), [
            "qdisc show dev eth0",
            "qdisc add dev eth0 root handle 1: prio bands 3 priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0",
            "qdisc add dev eth0 parent 1:3 handle 30: netem delay 75ms limit 10000",
            "filter add dev eth0 parent 1: protocol ip prio 1 u32 match ip protocol 6 0xff match ip sport 25575 0xffff flowid 1:3",
            "filter add dev eth0 parent 1: protocol ip prio 1 u32 match ip protocol 17 0xff match ip sport 25575 0xffff flowid 1:3",
            "qdisc add dev qcbench0 root netem delay 75ms limit 10000",
            "qdisc add dev eth0 handle ffff: ingress",
            "filter add dev eth0 parent ffff: protocol ip prio 1 u32 match ip protocol 6 0xff match ip dport 25575 0xffff action mirred egress redirect dev qcbench0",
            "filter add dev eth0 parent ffff: protocol ip prio 1 u32 match ip protocol 17 0xff match ip dport 25575 0xffff action mirred egress redirect dev qcbench0",
            "-s qdisc show dev eth0",
            "-s qdisc show dev qcbench0"])
        self.assertEqual(self.callargs("ip"), [
            "-4 route show default", "link show dev qcbench0",
            "link set dev eth0 gso_max_segs 1", "link add qcbench0 type ifb",
            "link set dev qcbench0 up"])

    def test_same_profile_args_in_both_directions(self):
        for profile, want in (("delay", "delay 75ms"), ("delayloss", "delay 75ms loss 2%"),
                              ("reorder", "delay 10ms reorder 25% 50%")):
            with self.subTest(profile=profile):
                self.assertEqual(self.case(["apply", profile, PORT]).returncode, 0)
                halves = [a.split(" netem ", 1)[1] for a in self.callargs("tc") if " netem " in a]
                self.assertEqual(halves, [want + " limit 10000"] * 2)

    def test_apply_failure_after_mutation_warns_unusable(self):
        rows = (("ifb_creation", {"fail_at": "ifb\n"}, "could not create qcbench0",
                 ("ip", "link add qcbench0 type ifb")),
                ("ingress_filter", {"fail_at": "ingressfilter\n"},
                 "could not add the IPv4 TCP destination-port ingress filter",
                 ("tc", "handle ffff: ingress")))
        for name, state, frag, evidence in rows:
            with self.subTest(name=name):
                res = self.case(["apply", "delay", PORT], state=state)
                self.assert_unusable(res)
                self.assertIn(frag, res.stderr)
                self.assertIn(evidence[1], " ".join(self.callargs(evidence[0])))

    def test_apply_failure_at_final_stat_dump_warns_unusable(self):
        res = self.case(["apply", "delay", PORT], state={"fail_at": "statdump\n"})
        self.assert_unusable(res)
        self.assertIn("-s qdisc show dev eth0", " ".join(self.callargs("tc")))

    def assert_unusable(self, res):
        self.assertNotEqual(res.returncode, 0)
        self.assertNotIn("configured '", res.stdout)
        self.assertIn("ERROR after mutation:", res.stderr)
        self.assertIn("partially configured and unusable", res.stderr)
        self.assertIn("no automatic cleanup is performed", res.stderr)

    def test_status_is_read_only_and_reports_impaired_legs(self):
        res = self.case(["status", "clean", PORT])
        self.assertEqual(res.returncode, 0)
        self.assertEqual(self.callargs("tc"), ["-s qdisc show dev eth0"])
        self.assertEqual(self.callargs("ip"), ["-4 route show default", "-d link"])
        self.assertIn("clean: no netem", res.stdout)
        res = self.case(["status", "delay", PORT], state={"ifb_exists": ""})
        self.assertEqual(res.returncode, 0)
        self.assertEqual(self.callargs("tc"), [
            "-s qdisc show dev eth0", "-s qdisc show dev qcbench0",
            "-s filter show dev eth0 parent 1:", "-s filter show dev eth0 parent ffff:"])
        self.assertEqual(self.callargs("ip"), ["-4 route show default", "-d link",
                                               "link show dev qcbench0"])
        res = self.case(["status", "delay", PORT])
        self.assertEqual(res.returncode, 1)
        self.assertIn("impaired profile 'delay' but qcbench0 is missing", res.stderr)
        self.assertEqual(self.callargs("tc"), ["-s qdisc show dev eth0"])

    def test_rebasing_swaps_only_the_authorized_absolute_literals(self):
        self.case(["apply", "clean", PORT])
        self.assert_rebase_only_swaps_literals(self.netem, SRC_NETEM)
        self.entry = self.rebase_copy(SRC_ENTRY, "real-client-entrypoint.sh")
        self.assert_rebase_only_swaps_literals(self.entry, SRC_ENTRY)
# @ENDC@
class EntryPointTest(ToolFixture):
    """Fake-command contract of testkit/docker/fabric/real-client-entrypoint.sh."""

    def run_entry(self, args=(), *, env=None, mountinfo=_MOUNTINFO_CLEAN, srv_dir=None):
        if srv_dir is None:
            self._srv_n += 1
            srv_dir = "srv%d" % self._srv_n
        self.srv_path = os.path.join(self.tmp, srv_dir)
        os.makedirs(self.srv_path, exist_ok=True)
        self.write("ns/mountinfo", mountinfo.replace("{SRV}", self.srv_path))
        self.entry = self.rebase_copy(SRC_ENTRY, "real-client-entrypoint.sh")
        self.config = os.path.join(self.srv_path, "config/quicraft/server.properties")
        self.clear_logs()
        return self.run_sh(self.entry, list(args), env=env, tools=("mkdir", "fabric-entrypoint"))

    def stub_lines(self):
        return _read(os.path.join(self.state, "log_entry.txt")).splitlines()

    def test_boolean_combos_drive_flag_config_mode_and_stub_exec(self):
        preset = {"FABRIC_ONLINE_MODE": "true", "REAL_FABRIC_MULTISTREAM": "true",
                  "JAVA_OPTS": "custom -Xmx1g"}
        for online, multi in (("true", "true"), ("true", "false"),
                              ("false", "true"), ("false", "false")):
            with self.subTest(online=online, multi=multi):
                env = dict(preset, FABRIC_ONLINE_MODE=online, REAL_FABRIC_MULTISTREAM=multi)
                res = self.run_entry(("nogui", "bench", "m o v e"), env=env)
                self.assertEqual(res.returncode, 0)
                wants = ("-Dquicraft.multistream.offlineTest=true custom -Xmx1g"
                         if online == "false" and multi == "true" else "custom -Xmx1g")
                self.assertEqual(self.stub_lines(), [
                    "argv nogui bench m o v e", "JAVA_OPTS=" + wants,
                    "FABRIC_ONLINE_MODE=" + online, "REAL_FABRIC_MULTISTREAM=" + multi])
                self.assertEqual(self.callargs("fabric-entrypoint"), ["nogui bench m o v e"])
                with open(self.config, "rb") as f:
                    self.assertEqual(f.read(), ("multistream=%s\n" % multi).encode())
                self.assertEqual(os.stat(self.config).st_mode & 0o777, 0o600)
                self.assertFalse(re.search(r"http|Authorization|token", "\n".join(self.stub_lines())))
                tail = ("offline mode with an explicit multistream test profile"
                        if wants.startswith("-D") else "online=%s multistream=%s" % (online, multi))
                self.assertIn(tail, res.stderr)

    def test_unset_or_invalid_booleans_refused(self):
        rows = (("online_unset", {}, "FABRIC_ONLINE_MODE"),
                ("multi_unset", {"FABRIC_ONLINE_MODE": "true"}, "REAL_FABRIC_MULTISTREAM"),
                ("online_invalid", {"FABRIC_ONLINE_MODE": "maybe",
                                    "REAL_FABRIC_MULTISTREAM": "true"},
                 "FABRIC_ONLINE_MODE must be true or false, got 'maybe'"),
                ("multi_invalid", {"FABRIC_ONLINE_MODE": "false",
                                   "REAL_FABRIC_MULTISTREAM": "perhaps"},
                 "REAL_FABRIC_MULTISTREAM must be true or false, got 'perhaps'"))
        for name, env, frag in rows:
            with self.subTest(name=name):
                res = self.run_entry(env=env)
                self.assertNotEqual(res.returncode, 0)
                self.assertIn(frag, res.stderr)
                self.assertFalse(os.path.exists(self.config))
                self.assertEqual(self.callargs("fabric-entrypoint"), [])

    def test_caller_supplied_offline_test_override_refused_from_env_and_argv(self):
        base = {"FABRIC_ONLINE_MODE": "true", "REAL_FABRIC_MULTISTREAM": "true"}
        prop = "-Dquicraft.multistream.offlineTest=true"
        rows = (("java_opts", dict(base, JAVA_OPTS=prop), ()),
                ("java_tool_options", dict(base, JAVA_TOOL_OPTIONS=prop), ()),
                ("jdk_java_options", dict(base, JDK_JAVA_OPTIONS=prop), ()),
                ("_java_options", dict(base, _JAVA_OPTIONS=prop), ()),
                ("argv", dict(base), ("nogui", prop)))
        for name, env, args in rows:
            with self.subTest(name=name):
                res = self.run_entry(args, env=env)
                self.assertNotEqual(res.returncode, 0)
                self.assertIn("refusing caller-supplied -Dquicraft.multistream.offlineTest",
                              res.stderr)
                self.assertFalse(os.path.exists(self.config))
                self.assertEqual(self.callargs("fabric-entrypoint"), [])

    def test_preexisting_config_refused_and_preserved_byte_for_byte(self):
        env = {"FABRIC_ONLINE_MODE": "true", "REAL_FABRIC_MULTISTREAM": "true"}
        self.run_entry(env=env, srv_dir="keep")
        open(self.config, "wb").write(b"Pre-Existing=keep\n")
        res = self.run_entry(env=env, srv_dir="keep")
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("refusing pre-existing", res.stderr)
        self.assertEqual(open(self.config, "rb").read(), b"Pre-Existing=keep\n")
        self.assertEqual(self.callargs("fabric-entrypoint"), [])

    def test_mounts_on_or_beneath_srv_are_refused(self):
        flags = {"FABRIC_ONLINE_MODE": "true", "REAL_FABRIC_MULTISTREAM": "true"}
        for name in ("bare_srv", "config", "config_quicraft"):
            form = "{SRV}" if name == "bare_srv" else "{SRV}/config" \
                if name == "config" else "{SRV}/config/quicraft"
            bad = _MOUNTINFO_CLEAN + "14 13 0:19 / %s ro,relatime - tmpfs tmpfs rw\n" % form
            with self.subTest(name=name):
                res = self.run_entry(env=flags, mountinfo=bad)
                self.assertNotEqual(res.returncode, 0)
                self.assertIn("refusing mount at", res.stderr)
                self.assertFalse(os.path.exists(self.config))
                self.assertEqual(self.callargs("fabric-entrypoint"), [])


class ComposeOverlayTest(unittest.TestCase):
    """Static text contract of compose.real-fabric.yaml; a real compose parse belongs to the
    orchestrator, so there is no YAML dependency and no Docker run here."""

    def test_loopback_only_ports_envs_entrypoint_and_ro_volume(self):
        with self.subTest(name="ports"):
            for line in ('      - "127.0.0.1:${REAL_FABRIC_PORT:-25575}:25565/tcp"',
                         '      - "127.0.0.1:${REAL_FABRIC_PORT:-25575}:25565/udp"',
                         '      - "127.0.0.1:${REAL_FABRIC_MOCK_PORT:-18080}:8080/tcp"'):
                self.assertIn(line, _read(SRC_COMPOSE))
            self.assertEqual(_read(SRC_COMPOSE).count("127.0.0.1:"), 3)
        with self.subTest(name="service_overrides"):
            text = _read(SRC_COMPOSE)
            for want in ("  mocksession:", "  fabric:",
                         '    entrypoint: ["/bin/sh", "/usr/local/bin/real-client-fabric-entrypoint.sh"]',
                         "      FABRIC_ONLINE_MODE: ${REAL_FABRIC_ONLINE_MODE:-false}",
                         "      REAL_FABRIC_MULTISTREAM: ${REAL_FABRIC_MULTISTREAM:-false}",
                         "      QUICRAFT_PLUGIN: ${QUICRAFT_PLUGIN:-true}",
                         "      - ./fabric/real-client-entrypoint.sh:/usr/local/bin/"
                         "real-client-fabric-entrypoint.sh:ro"):
                self.assertIn(want, text)
        with self.subTest(name="no_new_surface"):
            for banned in ("0.0.0.0", "network_mode:", "privileged:", "cap_add:", "image:",
                           "build:", "container_name:", "  velocity:", "  paper:"):
                self.assertNotIn(banned, _read(SRC_COMPOSE))


if __name__ == "__main__":
    unittest.main()
