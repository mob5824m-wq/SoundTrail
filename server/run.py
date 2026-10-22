#!/usr/bin/env python3
"""Cross-platform launcher for the Soundtrail sync server (no Docker, any OS/CPU).

    python3 run.py              # create .venv if needed, start the server
    python3 run.py --setup      # only prepare the environment, do not start
    python3 run.py --test       # run the API test suite inside the environment

Managed backups use the standard library only, so they work even before (or without) a venv:

    python3 run.py backup       # write a verified snapshot now
    python3 run.py list         # snapshots, newest first
    python3 run.py verify       # check the newest snapshot
    python3 run.py restore NAME --yes   # replace the database (stop the server first)

Requires Python 3.10+ on the PATH (`py -3` on Windows). The server uses only the
Python standard library, so there are no packages to install.
"""
from __future__ import annotations

import os
import subprocess
import sys
import venv
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent
VENV_DIR = SERVER_DIR / ".venv"
MIN_PYTHON = (3, 10)


def venv_python() -> Path:
    if os.name == "nt":
        return VENV_DIR / "Scripts" / "python.exe"
    return VENV_DIR / "bin" / "python"


def run(command: list[str]) -> int:
    print("+ " + " ".join(command), flush=True)
    return subprocess.call(command, cwd=SERVER_DIR)


def ensure_environment() -> None:
    if sys.version_info < MIN_PYTHON:
        sys.exit(f"Soundtrail needs Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]}+; found {sys.version.split()[0]}.")

    if not venv_python().exists():
        print(f"Creating virtual environment in {VENV_DIR} ...", flush=True)
        venv.EnvBuilder(with_pip=False, clear=True).create(VENV_DIR)


def main(argv: list[str]) -> int:
    # The backup tool is stdlib-only and deliberately usable before the environment exists.
    if argv and argv[0] in {"backup", "list", "verify", "restore"}:
        command = [sys.executable, str(SERVER_DIR / "backup.py"), *argv]
        try:
            return subprocess.call(command, cwd=SERVER_DIR)
        except KeyboardInterrupt:
            return 0
    ensure_environment()
    if "--setup" in argv:
        print("Environment ready.", flush=True)
        return 0
    if "--test" in argv:
        return run([str(venv_python()), "-m", "unittest", "discover", "-s", str(SERVER_DIR), "-v"])

    env = os.environ.copy()
    env.setdefault("PYTHONUNBUFFERED", "1")
    try:
        return subprocess.call([str(venv_python()), str(SERVER_DIR / "app.py")], cwd=SERVER_DIR, env=env)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
