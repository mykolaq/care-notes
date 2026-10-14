import sqlite3
import sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:3])
if source.resolve() == destination.resolve():
    raise SystemExit('Backup destination must differ from source')
with sqlite3.connect(source) as src, sqlite3.connect(destination) as dst:
    src.backup(dst)
destination.chmod(0o600)
print('Backup created')
