#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SanityProtocol
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Write the Docker mode's root filesystem reproducibly, with its provenance (csqtt P2 B13a; ci/build-rootfs.sh).

  ci/rootfs-pack.py <tree.tar> <out.tar.gz> <release time, ISO UTC> <alpine version> <alpine arch> <minirootfs url> <its sha256>

The tree (a plain tar from the builder) is rewritten member by member, never extracted: sorted by name, every owner root
(0:0, no names), every mtime the minirootfs release's, apk's own caches, lock and log left out; gzip carries no name and no time.
Beside it, <out>.provenance.json: the minirootfs, every package installed (from apk's database in the tree: name, version,
its index checksum), and the output's sha256 and size.
"""
import datetime
import gzip
import hashlib
import io
import json
import os
import sys
import tarfile

# (apk 3 logs its own run, with a time, in var/log/apk.log: the build's, not the root filesystem's)
SKIP = ("./lib/apk/db/lock", "./var/cache/apk/", "./etc/apk/cache/", "./var/log/apk.log")


def main(tree, out, released, alpine, arch, url, sha):
    mtime = int(datetime.datetime.strptime(released, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc).timestamp())
    src = tarfile.open(tree, "r:")
    members = sorted((m for m in src.getmembers() if m.name not in (".", "./") and not m.name.startswith(SKIP)),
                     key=lambda m: m.name)
    installed = b""
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w:", format=tarfile.GNU_FORMAT) as dst:
        for m in members:
            data = src.extractfile(m).read() if m.isreg() else None
            if m.name == "./lib/apk/db/installed":
                installed = data
            m.uid = m.gid = 0
            m.uname = m.gname = ""
            m.mtime = mtime
            m.pax_headers = {}
            dst.addfile(m, io.BytesIO(data) if data is not None else None)
    with open(out, "wb") as f:
        with gzip.GzipFile(filename="", mode="wb", fileobj=f, mtime=0, compresslevel=9) as z:
            z.write(raw.getvalue())
    blob = open(out, "rb").read()
    packages = []
    for block in installed.decode().split("\n\n"):
        fields = dict(line.split(":", 1) for line in block.splitlines() if len(line) > 2 and line[1] == ":")
        if "P" in fields:
            packages.append({"name": fields["P"], "version": fields.get("V", ""), "checksum": fields.get("C", "")})
    provenance = {
        "file": os.path.basename(out), "sha256": hashlib.sha256(blob).hexdigest(), "bytes": len(blob),
        "alpine": alpine, "arch": arch, "minirootfs": {"url": url, "sha256": sha},
        "packages": sorted(packages, key=lambda p: p["name"]),
    }
    with open(out[:-len(".tar.gz")] + ".provenance.json", "w") as f:
        json.dump(provenance, f, indent=2)
        f.write("\n")
    print(f"{provenance['file']} {provenance['sha256']} {provenance['bytes']}")


if __name__ == "__main__":
    if len(sys.argv) != 8:
        sys.exit(__doc__)
    main(*sys.argv[1:])
