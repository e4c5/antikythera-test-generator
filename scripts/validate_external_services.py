#!/usr/bin/env python3
"""
Run Antikythera unit-test generation from a generator.yml, then execute Maven tests
one service class at a time and write a TSV report (pass/fail per configured service).

Usage:
  export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto
  ./scripts/validate_external_services.py /path/to/generator.yml

Optional environment:
  ANTIKYTHERA_GENERATOR_HOME   Path to antikythera-test-generator module (default: parent of scripts/)
  ANTIKYTHERA_VALIDATION_ROOT  Maven project root to run tests in (overrides auto-detect from base_path)
  ANTIKYTHERA_SKIP_GENERATE    If set to 1, skip mvn exec:java and only run tests
  ANTIKYTHERA_MAVEN            Maven launcher (default: mvn)

Example report (external-service-validation-report.tsv):
  service_fqn	test_class	status	generator_exit	mvn_exit
  com.foo.Bar	com.foo.BarAKTest	PASS	0	0
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def _parse_list_block(text: str, key: str) -> list[str]:
    """Minimal parser for `key:\\n  - item` lists (no PyYAML required)."""
    lines = text.splitlines()
    out: list[str] = []
    in_block = False
    key_header = re.compile(rf"^{re.escape(key)}\s*:\s*$")
    for line in lines:
        ls = line.strip()
        if key_header.match(ls):
            in_block = True
            continue
        if in_block:
            if ls and not ls.startswith("#") and not ls.startswith("-") and ":" in ls and not ls.startswith("-"):
                # Next top-level `key: value` or `key:` block — stop unless it's a comment-only line
                if re.match(r"^[A-Za-z_][\w.]*\s*:", ls):
                    break
            m = re.match(r"^\s*-\s+(\S+)", line)
            if m:
                out.append(m.group(1).strip().strip("\"'"))
    return out


def _parse_scalar(text: str, key: str) -> str | None:
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("#") or not s.startswith(key + ":"):
            continue
        val = line.split(":", 1)[1].strip()
        if val.startswith('"') and val.endswith('"'):
            val = val[1:-1]
        elif val.startswith("'") and val.endswith("'"):
            val = val[1:-1]
        return val
    return None


def _project_root_from_base_path(base_path: str) -> Path:
    p = Path(os.path.expandvars(base_path)).expanduser().resolve()
    parts = p.parts
    if len(parts) >= 3 and parts[-3:] == ("src", "main", "java"):
        return p.parent.parent.parent
    return p


def _service_to_test_fqn(service_fqn: str) -> str:
    if "." not in service_fqn:
        return f"{service_fqn}AKTest"
    pkg, _, simple = service_fqn.rpartition(".")
    return f"{pkg}.{simple}AKTest"


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate tests from generator.yml and report per-service mvn test results.")
    ap.add_argument("generator_yml", type=Path, help="Path to generator.yml (config-driven external module)")
    ap.add_argument(
        "-o",
        "--report",
        type=Path,
        default=Path("external-service-validation-report.tsv"),
        help="Output TSV path (default: ./external-service-validation-report.tsv)",
    )
    ap.add_argument(
        "--skip-generate",
        action="store_true",
        help="Skip mvn exec:java; only run per-class tests (expects generated tests already present)",
    )
    args = ap.parse_args()

    yml = args.generator_yml.expanduser().resolve()
    if not yml.is_file():
        print(f"error: not a file: {yml}", file=sys.stderr)
        return 2

    text = yml.read_text(encoding="utf-8", errors="replace")
    services = _parse_list_block(text, "services")
    if not services:
        print("error: no `services:` entries found in generator.yml", file=sys.stderr)
        return 2

    base_path = _parse_scalar(text, "base_path")
    if not base_path:
        print("error: no `base_path:` in generator.yml (needed to locate Maven project root)", file=sys.stderr)
        return 2

    script_dir = Path(__file__).resolve().parent
    gen_home = Path(os.environ.get("ANTIKYTHERA_GENERATOR_HOME", script_dir.parent)).resolve()
    validation_root = os.environ.get("ANTIKYTHERA_VALIDATION_ROOT")
    if validation_root:
        project_root = Path(os.path.expandvars(validation_root)).expanduser().resolve()
    else:
        project_root = _project_root_from_base_path(base_path)

    if not project_root.is_dir():
        print(f"error: project root not found: {project_root}", file=sys.stderr)
        return 2

    pom = project_root / "pom.xml"
    if not pom.is_file():
        print(
            f"error: no pom.xml at {project_root} — set ANTIKYTHERA_VALIDATION_ROOT to the Maven module that contains generated tests",
            file=sys.stderr,
        )
        return 2

    maven = os.environ.get("ANTIKYTHERA_MAVEN", "mvn")
    skip_gen = args.skip_generate or os.environ.get("ANTIKYTHERA_SKIP_GENERATE", "").strip() in ("1", "true", "yes")

    report_path = args.report.expanduser().resolve()
    java_home = os.environ.get("JAVA_HOME", "")
    env = os.environ.copy()

    rows: list[tuple[str, str, str, str, str]] = []

    gen_exit = ""
    if not skip_gen:
        cmd = [
            maven,
            "-q",
            f"-Dexec.args={yml}",
            "exec:java",
            "-Dexec.mainClass=sa.com.cloudsolutions.antikythera.generator.Antikythera",
        ]
        r = subprocess.run(cmd, cwd=gen_home, env=env)
        gen_exit = str(r.returncode)
        if r.returncode != 0:
            print(f"warning: generator exited with {r.returncode}; continuing with per-class tests anyway", file=sys.stderr)
    else:
        gen_exit = "skipped"

    for svc in services:
        test_fqn = _service_to_test_fqn(svc)
        cmd = [
            maven,
            "-q",
            "test",
            f"-Dtest={test_fqn}",
            f"-f{pom}",
        ]
        r = subprocess.run(cmd, cwd=project_root, env=env)
        status = "PASS" if r.returncode == 0 else "FAIL"
        rows.append((svc, test_fqn, status, gen_exit if gen_exit != "skipped" else "skipped", str(r.returncode)))

    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    lines_out = [
        "# antikythera external service validation",
        f"# generator_yml={yml}",
        f"# project_root={project_root}",
        f"# java_home={java_home or '(unset)'}",
        f"# timestamp_utc={ts}",
        "service_fqn\ttest_class\tstatus\tgenerator_exit\tmvn_exit",
    ]
    for row in rows:
        lines_out.append("\t".join(row))

    report_path.write_text("\n".join(lines_out) + "\n", encoding="utf-8")
    print(f"Wrote {report_path}")

    failed = sum(1 for r in rows if r[2] == "FAIL")
    print(f"Summary: {len(rows) - failed} PASS, {failed} FAIL (services listed in generator.yml)")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
