#!/usr/bin/env python3
"""Fail if the given JUnit XML reports show no executed tests, or any skipped test.

Guards against Testcontainers tests silently skipping (disabledWithoutDocker) and the job staying green.
Usage: assert-tests-ran.py <reports-dir> <expected-class-substring> [min-tests]
"""
import glob
import sys
import xml.etree.ElementTree as ET

reports_dir, expected = sys.argv[1], sys.argv[2]
min_tests = int(sys.argv[3]) if len(sys.argv) > 3 else 1
tests = skipped = 0
seen = False
for f in glob.glob(f"{reports_dir}/TEST-*.xml"):
    root = ET.parse(f).getroot()
    if expected in (root.get("name") or ""):
        seen = True
    tests += int(root.get("tests", 0))
    skipped += int(root.get("skipped", 0))
print(f"{reports_dir}: tests={tests} skipped={skipped} saw_{expected}={seen}")
if not seen or tests < min_tests or skipped:
    sys.exit(f"Expected {expected} to run >= {min_tests} tests with 0 skipped")
