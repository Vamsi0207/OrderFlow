#!/usr/bin/env python3
"""Explicit Phase 5 CLI; no end-to-end test runs on import or with no arguments."""
import sys
sys.dont_write_bytecode=True
from phase5 import cli
if __name__ == '__main__':
    raise SystemExit(cli())
