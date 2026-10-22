#!/usr/bin/env python3
"""Add the `# Errors` doc sections and `#[must_use]` attributes that clippy's
pedantic lints require.

The script is deliberately conservative:

* it only touches `pub fn` items,
* it never rewrites existing documentation,
* it is idempotent: running it twice changes nothing the second time.

Usage:  python tools/add-rust-doc-lints.py crates/zarc-core/src crates/zarc-jni/src
"""

import sys
from pathlib import Path

ERRORS_SECTION = (
    "# Errors\n"
    "#\n"
    "# Returns [`crate::Error`] when the operation cannot be completed; see\n"
    "# the error taxonomy in [`crate::error`] for the individual failure modes.\n"
)


def find_signature(lines, index):
    """Return the text of the function signature that starts at `index`.

    The signature ends at the first line that contains the opening brace of
    the body or a semicolon (a declaration without a body).
    """
    parts = []
    for cursor in range(index, min(index + 40, len(lines))):
        line = lines[cursor]
        parts.append(line)
        if "{" in line or line.rstrip().endswith(";"):
            break
    return "".join(parts)


def doc_block_start(lines, index):
    """The index of the first line of the doc comment above `index`, or None."""
    cursor = index - 1
    # Skip attributes.
    while cursor >= 0 and lines[cursor].lstrip().startswith("#["):
        cursor -= 1
    if cursor < 0 or not lines[cursor].lstrip().startswith("///"):
        return None
    while cursor >= 0 and lines[cursor].lstrip().startswith("///"):
        cursor -= 1
    return cursor + 1


def attributes_above(lines, index):
    """The attribute lines directly above `index`."""
    attributes = []
    cursor = index - 1
    while cursor >= 0 and lines[cursor].lstrip().startswith("#["):
        attributes.insert(0, lines[cursor])
        cursor -= 1
    return attributes


def process(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if "extern \"C\" {" in text:
        # A file with raw FFI declarations: the declarations are not part of
        # the safe API and must not receive attributes.
        return False
    lines = text.splitlines(keepends=True)
    changed = False

    index = 0
    while index < len(lines):
        line = lines[index]
        stripped = line.lstrip()
        if not stripped.startswith("pub fn "):
            index += 1
            continue

        signature = find_signature(lines, index)
        returns_result = "-> Result<" in signature or "-> Result " in signature
        returns_unit = signature.split("{")[0].rstrip().endswith("()")
        indent = line[: len(line) - len(stripped)]

        doc_start = doc_block_start(lines, index)
        doc_block = "".join(lines[doc_start:index]) if doc_start is not None else ""
        has_errors = "# Errors" in doc_block
        attributes = attributes_above(lines, index)
        has_must_use = any("must_use" in attribute for attribute in attributes)

        # 1. `#[must_use]` for functions that return a value.
        if not returns_unit and not returns_result and not has_must_use:
            lines.insert(index, f"{indent}#[must_use]\n")
            changed = True
            index += 1
            continue

        # 2. `# Errors` for functions that return a `Result`.
        if returns_result and not has_errors:
            block = "".join(
                f"{indent}/// {line}\n" if line else f"{indent}///\n"
                for line in ERRORS_SECTION.rstrip("\n").split("\n")
            )
            if doc_start is None:
                lines.insert(index, block)
            else:
                lines.insert(doc_start, block)
            changed = True
            index += block.count("\n")
            continue

        index += 1

    if changed:
        path.write_text("".join(lines), encoding="utf-8")
    return changed


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    touched = 0
    for root in sys.argv[1:]:
        for path in sorted(Path(root).rglob("*.rs")):
            if process(path):
                touched += 1
    print(f"{touched} file(s) updated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
