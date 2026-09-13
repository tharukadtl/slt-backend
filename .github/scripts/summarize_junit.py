#!/usr/bin/env python3
"""Parses one or more JUnit XML reports into a markdown summary and appends it
to $GITHUB_STEP_SUMMARY. Works with any JUnit-XML producer (Maven Surefire,
jest-junit, pytest --junitxml, Cypress's bundled mocha-junit-reporter) since
they all share the same <testsuite tests= failures= errors= skipped=> shape,
with or without an outer <testsuites> wrapper.

Usage:
  summarize_junit.py <glob-pattern> [<glob-pattern> ...]
  summarize_junit.py [--group "Label=pkg.prefix1,pkg.prefix2"] ... <glob-pattern> [...]

--group splits the overall summary into additional labeled sections, one per
group, matching each <testsuite> to a group by its fully-qualified class name
(the suite's "name" attribute) starting with one of the group's entries.
Groups are reported in the order given, and a suite is claimed by the first
group it matches -- so a more specific entry (e.g. an exact class's FQCN,
with no trailing dot) must be listed in a group checked before a broader
package-prefix entry that would otherwise also match it. When --group is
used at all, any suite matching none of them is automatically collected into
a trailing "Other" section -- this is what keeps every category subtotal
summing back to the overall total at the top, including for any test class
added later in a package no --group entry yet covers, not just today's known
stragglers.
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def testsuites_in(root):
    if root.tag == "testsuites":
        return list(root.findall("testsuite"))
    if root.tag == "testsuite":
        return [root]
    return []


def suite_counts(suite):
    total = int(suite.get("tests", 0) or 0)
    failures = int(suite.get("failures", 0) or 0)
    errors = int(suite.get("errors", 0) or 0)
    skipped = int(suite.get("skipped", 0) or 0)
    failing_names = []
    for case in suite.findall("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            classname = case.get("classname", "")
            name = case.get("name", "")
            failing_names.append(f"{classname} › {name}" if classname else name)
    return total, failures, errors, skipped, failing_names


def summary_block(heading, total, failures, errors, skipped, failing_names):
    passed = max(0, total - failures - errors - skipped)
    failed = failures + errors
    lines = [heading, ""] if heading else []
    if total == 0:
        lines.append("_No tests found in this category._")
        lines.append("")
        return lines
    lines.append(
        f"**{total} total** — ✅ {passed} passed, ❌ {failed} failed, ⏭️ {skipped} skipped"
    )
    lines.append("")
    if failing_names:
        lines.append("Failing tests:")
        for n in failing_names:
            lines.append(f"- `{n}`")
        lines.append("")
    elif failed == 0:
        lines.append("_All tests passed._")
        lines.append("")
    return lines


def parse_groups(raw_groups):
    groups = []
    for raw in raw_groups:
        if "=" not in raw:
            sys.exit(f"--group must be in the form 'Label=pkg.prefix1,pkg.prefix2': {raw!r}")
        label, prefixes = raw.split("=", 1)
        prefix_list = [p.strip() for p in prefixes.split(",") if p.strip()]
        groups.append((label.strip(), prefix_list))
    return groups


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("patterns", nargs="+")
    parser.add_argument(
        "--group",
        action="append",
        default=[],
        help="Label=pkg.prefix1,pkg.prefix2 -- may be repeated",
    )
    args = parser.parse_args()
    groups = parse_groups(args.group)

    files = []
    for p in args.patterns:
        files.extend(sorted(glob.glob(p, recursive=True)))

    lines = ["## Test Summary", ""]

    if not files:
        lines.append(f"No JUnit XML reports found matching: {', '.join(args.patterns)}")
        write(lines)
        return

    overall_total = overall_failures = overall_errors = overall_skipped = 0
    overall_failing_names = []
    group_totals = [[0, 0, 0, 0, []] for _ in groups]
    other_total = [0, 0, 0, 0, []]

    for path in files:
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for suite in testsuites_in(tree.getroot()):
            total, failures, errors, skipped, failing_names = suite_counts(suite)
            overall_total += total
            overall_failures += failures
            overall_errors += errors
            overall_skipped += skipped
            overall_failing_names.extend(failing_names)

            suite_name = suite.get("name", "")
            for idx, (label, prefixes) in enumerate(groups):
                if any(suite_name.startswith(prefix) for prefix in prefixes):
                    g = group_totals[idx]
                    g[0] += total
                    g[1] += failures
                    g[2] += errors
                    g[3] += skipped
                    g[4].extend(failing_names)
                    break
            else:
                if groups:
                    other_total[0] += total
                    other_total[1] += failures
                    other_total[2] += errors
                    other_total[3] += skipped
                    other_total[4].extend(failing_names)

    lines.extend(
        summary_block(
            "",
            overall_total,
            overall_failures,
            overall_errors,
            overall_skipped,
            overall_failing_names,
        )
    )

    for (label, _prefixes), (total, failures, errors, skipped, failing_names) in zip(
        groups, group_totals
    ):
        lines.extend(
            summary_block(f"### {label}", total, failures, errors, skipped, failing_names)
        )

    if groups:
        total, failures, errors, skipped, failing_names = other_total
        lines.extend(
            summary_block("### Other", total, failures, errors, skipped, failing_names)
        )

    write(lines)


def write(lines):
    text = "\n".join(lines) + "\n"
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()
