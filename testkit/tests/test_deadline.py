# SPDX-License-Identifier: GPL-3.0-or-later
"""Contract tests for testkit/lib/deadline.sh: nested kill graces and process-group reaping.

Hermetic: only bash, setsid, timeout and sleep; every child is short and killed by the helper
under test. Run: python3 -I -m unittest testkit/tests/test_deadline.py
"""
import os
import shutil
import subprocess
import tempfile
import time
import unittest

_TESTS = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(os.path.dirname(_TESTS))
LIB = os.path.join(_ROOT, "testkit/lib/deadline.sh")
BASH = shutil.which("bash") or "/bin/bash"


def run(script, env=None, timeout=60):
    e = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    e.update(env or {})
    return subprocess.run([BASH, "-c", script], env=e, capture_output=True, text=True, timeout=timeout)


class DeadlineTest(unittest.TestCase):
    def test_outer_grace_must_exceed_inner_grace(self):
        for inner, outer in (("20", "20"), ("30", "20")):
            r = run(f". {LIB}", {"DEADLINE_INNER_GRACE": inner, "DEADLINE_OUTER_GRACE": outer})
            self.assertEqual(r.returncode, 2, (inner, outer, r.stderr))
            self.assertIn("strictly greater", r.stderr)

    def test_non_numeric_graces_are_refused(self):
        r = run(f". {LIB}", {"DEADLINE_INNER_GRACE": "x", "DEADLINE_OUTER_GRACE": "60"})
        self.assertEqual(r.returncode, 2, r.stderr)

    def test_defaults_are_accepted_and_budgets_scale_with_the_window(self):
        r = run(f". {LIB}; client_run_budget 60; client_run_budget 600; matrix_run_budget 60")
        self.assertEqual(r.returncode, 0, r.stderr)
        a, b, c = (int(x) for x in r.stdout.split())
        self.assertEqual(b - a, 540)
        self.assertGreater(c, a)

    def test_a_term_ignoring_descendant_is_killed_by_the_inner_group_kill(self):
        with tempfile.TemporaryDirectory() as d:
            pidfile = os.path.join(d, "pid")
            script = os.path.join(d, "child.sh")
            with open(script, "w") as f:
                f.write("trap '' TERM\nsleep 300 &\necho $! > " + pidfile + "\nwait\n")
            start = time.monotonic()
            r = run(f". {LIB}; bounded 1 {BASH} {script}; echo status=$?",
                    {"DEADLINE_INNER_GRACE": "2", "DEADLINE_OUTER_GRACE": "6"}, timeout=40)
            took = time.monotonic() - start
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("status=", r.stdout)
            self.assertLess(took, 20, "the group kill must not wait for the descendant")
            with open(pidfile) as f:
                pid = int(f.read().strip())
            time.sleep(0.5)
            self.assertFalse(os.path.exists(f"/proc/{pid}") and open(f"/proc/{pid}/stat").read().split()[2] != "Z",
                             "the TERM-ignoring descendant survived the group kill")


if __name__ == "__main__":
    unittest.main()
