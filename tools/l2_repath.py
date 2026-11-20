#!/usr/bin/env python3
"""L2 Lethal: cambia la ruta fija /data/data/com.winlator/ por /data/data/com.l2lethal/ dentro de
todos los .tzst de assets (mismo largo -> los binarios ELF no se rompen). Se corre antes de compilar.
Uso: python3 tools/l2_repath.py app/src/main/assets"""
import glob, io, os, sys, tarfile
import zstandard

OLD = b"/data/data/com.winlator/"
NEW = b"/data/data/com.l2lethal/"
assert len(OLD) == len(NEW)

def repath(path):
    raw = zstandard.ZstdDecompressor().stream_reader(open(path, "rb")).read()
    if OLD not in raw:
        return 0
    src = tarfile.open(fileobj=io.BytesIO(raw), mode="r:")
    out = io.BytesIO()
    dst = tarfile.open(fileobj=out, mode="w:", format=src.format)
    changed = 0
    for m in src.getmembers():
        data = None
        if m.isfile():
            data = src.extractfile(m).read()
            if OLD in data:
                data = data.replace(OLD, NEW); changed += 1
        if (m.issym() or m.islnk()) and OLD.decode() in m.linkname:
            m.linkname = m.linkname.replace(OLD.decode(), NEW.decode()); changed += 1
        dst.addfile(m, io.BytesIO(data) if data is not None else None)
    dst.close()
    tar = out.getvalue()
    assert OLD not in tar
    with open(path, "wb") as f:
        f.write(zstandard.ZstdCompressor(level=19, threads=-1).compress(tar))
    return changed

if __name__ == "__main__":
    total = 0
    for p in sorted(glob.glob(os.path.join(sys.argv[1], "**", "*.tzst"), recursive=True)):
        n = repath(p)
        if n:
            print(f"{p}: {n} cambios"); total += n
    print("total", total)
