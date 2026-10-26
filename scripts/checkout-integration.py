#!/usr/bin/env python3
"""Retired shared-infrastructure entry; explicit isolated Phase 5 CLI (default dry-run)."""
import runpy
import sys
from pathlib import Path

def main():
    path=Path(__file__).resolve().parent/'test-plans'
    sys.dont_write_bytecode=True
    sys.path.insert(0,str(path))
    runpy.run_path(str(path/'final-buy-now.py'),run_name='__main__')

if __name__=='__main__':main()
