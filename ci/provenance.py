#!/usr/bin/env python3
"""Write the server-core manifest the APK carries (assets/csqtt.server-provenance.json) and SHA256SUMS, in the same run that
built the cores (M-161: never typed by hand, never committed).

  ci/provenance.py <source tree> <cores dir> <source commit>

<cores dir> holds csqtt-linux-{amd64,arm64,armv7}; the manifest and SHA256SUMS are written next to them.

`version` is the server's own (rust-server/Cargo.toml) and `wireProtocolRevision` its WIRE_PROTOCOL_REVISION; the app reads
`version` (bundledServerVersion). `buildInputSha256` covers every server build input with line endings normalised, the
rustflags (`.cargo/`) and the toolchain file included, plus this build's own recipe (ci/Dockerfile.server, ci/build-server.sh)
— the inputs the 2.5.x manifest left out.
"""
import hashlib, json, os, re, sys

TARGETS = {"amd64": "x86_64-unknown-linux-musl", "arm64": "aarch64-unknown-linux-musl",
           "armv7": "armv7-unknown-linux-musleabihf"}
CI = os.path.dirname(os.path.abspath(__file__))


def norm(b):
    return b if b"\0" in b else b.replace(b"\r\n", b"\n")


def inputs(src):
    out = []
    for top in ("rust-server", "shared"):
        for d, dirs, files in os.walk(os.path.join(src, top)):
            dirs[:] = sorted(x for x in dirs if not (top == "rust-server" and d == os.path.join(src, top)
                                                       and x in ("target", "dist")))
            out += [os.path.join(d, f) for f in files]
    out.append(os.path.join(src, "app/src/main/assets/domains.txt"))   # include_str! in rust-server/web_panel.rs
    rows = sorted((os.path.relpath(p, src).replace(os.sep, "/"), p) for p in out)
    rows += [("ci/" + n, os.path.join(CI, n)) for n in ("Dockerfile.server", "build-server.sh")]
    return rows


def main(src, cores, commit):
    toml = open(os.path.join(src, "rust-server/Cargo.toml"), encoding="utf-8").read()
    version = re.search(r'(?ms)^\[package\].*?^version\s*=\s*"([^"]+)"', toml).group(1)
    wire = re.search(r'WIRE_PROTOCOL_REVISION\s*:\s*&str\s*=\s*"([^"]+)"',
                     open(os.path.join(src, "rust-server/main.rs"), encoding="utf-8").read()).group(1)
    listing = "".join("%s:%s\n" % (rel, hashlib.sha256(norm(open(p, "rb").read())).hexdigest()) for rel, p in inputs(src))
    docker = open(os.path.join(CI, "Dockerfile.server"), encoding="utf-8").read()
    arg = lambda n: re.search(r"^ARG %s=(\S+)" % n, docker, re.M).group(1)
    arts, sums = [], []
    for a, t in TARGETS.items():
        name = "csqtt-linux-" + a
        b = open(os.path.join(cores, name), "rb").read()
        h = hashlib.sha256(b).hexdigest()
        arts.append({"assetName": name, "target": t, "binarySize": len(b), "binarySha256": h.upper()})
        sums.append("%s  %s\n" % (h, name))
    manifest = {
        "schema": "sanityprotocol.csqtt-server-assets.v2",
        "package": "csqtt",
        "version": version,
        "wireProtocolRevision": wire,
        "source": "github.com/SanityProtocol/csqtt (a fork of amurcanov/csqtt)",
        "sourceCommit": commit,
        "buildInputSha256": hashlib.sha256(listing.encode()).hexdigest().upper(),
        "toolchain": {"image": re.search(r"^FROM (\S+)", docker, re.M).group(1), "rust": arg("RUST"), "zig": arg("ZIG"),
                      "cargoZigbuild": arg("CARGO_ZIGBUILD")},
        "artifacts": arts,
    }
    with open(os.path.join(cores, "csqtt.server-provenance.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2); f.write("\n")
    with open(os.path.join(cores, "SHA256SUMS"), "w", encoding="utf-8") as f:
        f.writelines(sums)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
