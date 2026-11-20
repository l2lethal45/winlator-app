#!/usr/bin/env python3
"""L2 Lethal: falla si algun .tzst del APK todavia trae /data/data/com.winlator/ (Box64 no arrancaria)."""
import sys, zipfile, zstandard
OLD = b"/data/data/com.winlator/"
z = zipfile.ZipFile(sys.argv[1]); bad = []; n = 0
for name in z.namelist():
    if name.startswith("assets/") and name.endswith(".tzst"):
        n += 1
        if OLD in zstandard.ZstdDecompressor().stream_reader(z.open(name)).read(): bad.append(name)
print(f"tzst revisados: {n}, con la ruta vieja: {bad}")
sys.exit(1 if bad or n == 0 else 0)
