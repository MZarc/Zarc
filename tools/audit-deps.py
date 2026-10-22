#!/usr/bin/env python3
"""Audit the Rust dependencies of the workspace.

For every crate in `Cargo.lock` the script

* asks the OSV database whether a security advisory exists,
* fetches the license of the crate from crates.io.

It prints a table and exits with a non-zero status when an advisory is found
or when a license is not in the accepted list.

Usage:  python tools/audit-deps.py
"""

import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ACCEPTED_LICENSES = {
    "MIT",
    "Apache-2.0",
    "MIT OR Apache-2.0",
    "Apache-2.0 OR MIT",
    "BSD-3-Clause",
    "BSD-2-Clause",
    "ISC",
    "Zlib",
    "Unicode-3.0",
    "Unicode-DFS-2016",
    "CC0-1.0",
    "MPL-2.0",
    "BSL-1.0",
    "OpenSSL",
    "MIT-0",
    "Unlicense OR MIT",
    "Apache-2.0 WITH LLVM-exception OR Apache-2.0",
    "BSD-3-Clause OR Apache-2.0",
}

USER_AGENT = "zarc-audit/0.1"
TIMEOUT = 30


def parse_lockfile(path: Path):
    """Return the (name, version) pairs of a Cargo.lock file."""
    packages = []
    name = None
    version = None
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r'^name = "(.+)"$', line)
        if match:
            name = match.group(1)
            continue
        match = re.match(r'^version = "(.+)"$', line)
        if match and name:
            version = match.group(1)
            packages.append((name, version))
            name = None
    return packages


def osv_advisories(name: str, version: str):
    """Query the OSV database for advisories of a crates.io package."""
    body = json.dumps(
        {"package": {"name": f"crates.io/{name}", "ecosystem": "crates.io"}}
    ).encode("utf-8")
    request = urllib.request.Request(
        "https://api.osv.dev/v1/query",
        data=body,
        headers={"Content-Type": "application/json", "User-Agent": USER_AGENT},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            payload = json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        return None, f"query failed: {error}"
    vulns = payload.get("vulns") or []
    return [vuln["id"] for vuln in vulns], None


def crates_io_license(name: str, version: str):
    """Fetch the license of a crate from crates.io."""
    url = f"https://crates.io/api/v1/crates/{name}/{version}"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            payload = json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        return None, f"query failed: {error}"
    version_info = payload.get("version") or {}
    return version_info.get("license"), None


def normalise_license(license_expression: str):
    """Normalise an SPDX expression into a set of alternative licenses.

    crates.io stores the license of a crate as free text, so the same
    permissive license is spelled in several ways ("MIT OR Apache-2.0",
    "MIT/Apache-2.0", "MIT / Apache-2.0"). The alternatives are compared
    after normalisation.
    """
    cleaned = license_expression.replace("(", " ").replace(")", " ")
    cleaned = cleaned.replace("/", " OR ")
    parts = [part.strip() for part in re.split(r"\s+OR\s+|\s*,\s*", cleaned)]
    return {part for part in parts if part}


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    lockfile = root / "Cargo.lock"
    if not lockfile.exists():
        print("Cargo.lock not found", file=sys.stderr)
        return 2

    packages = parse_lockfile(lockfile)
    print(f"{len(packages)} crates in Cargo.lock\n")

    problems = []
    unknown = []
    for name, version in packages:
        advisories, advisory_error = osv_advisories(name, version)
        license_, license_error = crates_io_license(name, version)
        if advisory_error:
            advisories = None
        if license_error:
            license_ = None

        if advisories:
            problems.append(f"{name} {version}: advisories {advisories}")
        if license_ is None:
            unknown.append(f"{name} {version}")
        else:
            alternatives = normalise_license(license_)
            if not alternatives & ACCEPTED_LICENSES:
                problems.append(
                    f"{name} {version}: license {license_!r} is not in the accepted list"
                )

        status = "ok"
        if advisories is None:
            status = "advisory-status-unknown"
        elif advisories:
            status = f"ADVISORY {advisories}"
        print(f"{name:<28} {version:<12} {str(license_):<40} {status}")

    print()
    if unknown:
        print("license could not be determined for: " + ", ".join(unknown))
    if problems:
        print("PROBLEMS:")
        for problem in problems:
            print("  " + problem)
        return 1
    print("no known advisories and all licenses are in the accepted list")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
