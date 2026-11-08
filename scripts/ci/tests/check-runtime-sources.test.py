#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""check-runtime-sources.sh's --image native-comparison fixture checks (11
Runtime exception obligations and checks).

Runs the shipped check-runtime-sources.sh and canonical-jar-hash.py in a
throwaway fixture repo — the script resolves desktopApp/runtime.lock relative
to its own location, so the real code paths run against fixture images. These
are the re-signed Mach-O cases proven on the release candidate, carried over
from the desktop image gates' suite to a minimal runtime-only fixture (a
jpackage .app's runtime tree plus the pinned JDK counterpart):

  * a jpackage .app keeps the runtime home at Contents/runtime/Contents/Home:
    release/legal resolve under the home while the native-file comparison
    keeps the Contents/runtime root, whose rel paths pair with the pinned
    tree's Contents/Home entries;
  * jpackage re-signs ad-hoc over Adoptium's signatures, so image natives
    differ byte-wise from the pinned files; the gate binds each Mach-O's
    canonical content (canonical-jar-hash.py --content: signature slots
    removed, __LINKEDIT extents rebound) — `codesign --remove-signature`
    leaves a signature-size-dependent __LINKEDIT.vmsize residue that can
    never match across mixed-size signatures;
  * the fixtures carry real LC_CODE_SIGNATURE slots; where the host has
    clang and the real codesign, a dylib compiled in the test runs the same
    pass/fail pair under real signatures.
"""

import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
CI = REPO / "scripts/ci"

JAVA_VERSION = "25.0.4.1"  # fixture runtime.lock mirrors the real one's shape


def release_file(modules="java.base java.desktop") -> bytes:
    return (
        f'JAVA_VERSION="{JAVA_VERSION}"\nMODULES="{" ".join(sorted(modules.split()))}"\n'
    ).encode()


def smoke_line(root: Path) -> str:
    lock = (root / "desktopApp/runtime.lock").read_text(encoding="utf-8")
    prop = lambda k: next(
        line.split("=", 1)[1] for line in reversed(lock.splitlines())
        if line.startswith(k + "=")
    )
    return (
        'SMOKE {"javaVendor":"%s","javaVendorVersion":"%s","javaRuntimeVersion":"%s"}\n'
        % (prop("vendor"), prop("vendorVersion"), prop("javaRuntimeVersion"))
    )


# Mach-O magic (little-endian MH_MAGIC_64) so the native-file comparison path
# runs image_compare_file; identical bytes pass the cmp -s leg without codesign.
MACHO = bytes.fromhex("cffaedfe") + b"\x00" * 60


def thin_macho(sig=b"SIGNATURE", code=b"CODE", linkedit_extra=b"", dataoff=0x1800,
               total=None, page=0x1000):
    """A minimal 64-bit little-endian Mach-O: one __LINKEDIT segment and one
    LC_CODE_SIGNATURE; re-signing keeps the blob's offset and extends it to EOF."""
    hdr, seg, sig_cmd = 32, 72, 16
    sizeofcmds = seg + sig_cmd
    linkedit_off = page
    if total is None:
        total = dataoff + len(sig)
    fsize = total - linkedit_off
    vmsize = -(-fsize // page) * page
    b = bytearray(total)
    struct.pack_into("<IiiIIII", b, 0, 0xFEEDFACF, 0x01000007, 3, 2, 2, sizeofcmds, 0)
    struct.pack_into("<I", b, 28, 0)
    b[hdr:hdr + seg] = struct.pack(
        "<II16sQQQQIIII", 0x19, seg, b"__LINKEDIT".ljust(16, b"\0"),
        0, vmsize, linkedit_off, fsize, 5, 1, 0, 0)
    b[hdr + seg:hdr + sizeofcmds] = struct.pack("<IIII", 0x1D, sig_cmd, dataoff, len(sig))
    b[hdr + sizeofcmds:hdr + sizeofcmds + len(code)] = code
    if linkedit_extra:
        b[linkedit_off:linkedit_off + len(linkedit_extra)] = linkedit_extra
    b[dataoff:dataoff + len(sig)] = sig
    return bytes(b)


class Fixture:
    """A fixture repo: the real scripts and lockfile under root/."""

    def __init__(self, root: Path):
        self.root = root
        (root / "scripts/ci").mkdir(parents=True)
        for script in ("check-runtime-sources.sh", "canonical-jar-hash.py"):
            shutil.copy2(CI / script, root / "scripts/ci" / script)
        (root / "desktopApp").mkdir(parents=True)
        shutil.copy2(REPO / "desktopApp/runtime.lock", root / "desktopApp/runtime.lock")

    def write(self, rel: str, data: bytes):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def run(self, *args) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", *map(str, args)],
            cwd=self.root, capture_output=True, text=True, timeout=120,
        )


class RuntimeSourcesGateTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.fx = Fixture(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    # ----- fixture builders -------------------------------------------------

    def mac_app(self, root_dir="imgs/Neutrodyne.app"):
        """A jpackage .app: runtime home at Contents/runtime/Contents/Home."""
        fx = self.fx
        app = root_dir
        fx.write(f"{app}/Contents/MacOS/Neutrodyne", b"MACHO-LAUNCHER")
        fx.write(f"{app}/Contents/runtime/Contents/Home/release", release_file())
        fx.write(f"{app}/Contents/runtime/Contents/Home/legal/LICENSE", b"GPL-2.0\n")
        fx.write(f"{app}/Contents/runtime/Contents/Home/lib/jrt-fs.jar", b"JRT")
        fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", MACHO
        )
        return app

    def mac_jdk(self, rel="jdks/macos-arm64/jdk"):
        """The pinned Temurin tree pairing the image's Contents/runtime subtree:
        every image runtime file must have a counterpart here."""
        fx = self.fx
        for rel_path, data in (
            ("release", release_file()),
            ("legal/LICENSE", b"GPL-2.0\n"),
            ("lib/jrt-fs.jar", b"JRT"),
            ("lib/server/libjvm.dylib", MACHO),
        ):
            fx.write(f"{rel}/Contents/Home/{rel_path}", data)
        return rel

    # ----- re-signed Mach-O natives under check-runtime-sources ---------------

    def runtime_sources(self, app, jdk):
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        return self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt",
        )

    def test_macos_resigned_native_passes(self):
        """The jpackage ad-hoc re-sign makes image natives differ byte-wise
        from the pinned Temurin files; their canonical content must compare
        equal — here with signature blobs of different sizes, the residue
        case `codesign --remove-signature` cannot equalize."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"JVM-CODE", sig=b"ADHOC-1"),
        )
        self.fx.write(
            f"{jdk}/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"JVM-CODE", sig=b"ADOPTIUM" * 800),
        )
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_macos_resigned_code_tamper_fails(self):
        """A code difference is not a signature difference: the normalized
        contents still differ and the gate reports it (fail closed)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"EVIL-CODE", sig=b"ADHOC-1"),
        )
        self.fx.write(
            f"{jdk}/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"JVM-CODE", sig=b"ADOPTIUM" * 800),
        )
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)

    # Real-toolchain variants: the same pass/fail pair over a dylib compiled
    # and signed in the test — not the shipped Neutrodyne artifact.

    def clang_dylib(self, tag: str, ret: int) -> Path:
        """Compile a one-function dylib with the host clang (a fixture binary
        built here — not the shipped Neutrodyne artifact)."""
        work = Path(self._tmp.name) / "cc" / tag
        work.mkdir(parents=True, exist_ok=True)
        src, lib = work / "probe.c", work / "probe.dylib"
        src.write_text(f"int probe(void) {{ return {ret}; }}\n")
        subprocess.run(
            ["clang", "-dynamiclib", "-o", str(lib), str(src)],
            check=True, capture_output=True, text=True, timeout=60,
        )
        return lib

    def adhoc_sign(self, src: Path, tag: str, ident: str, *opts) -> bytes:
        """Re-sign a copy of src with the real codesign under a distinct ad-hoc
        identity/options and return the signed bytes."""
        dst = src.with_name(f"{src.stem}-{tag}.dylib")
        shutil.copy2(src, dst)
        subprocess.run(
            ["codesign", "-f", "-s", "-", "--identifier", ident, *opts, str(dst)],
            check=True, capture_output=True, text=True, timeout=60,
        )
        return dst.read_bytes()

    @unittest.skipUnless(
        sys.platform == "darwin" and shutil.which("clang") and shutil.which("codesign"),
        "needs the macOS host toolchain (clang + codesign)",
    )
    def test_macos_real_signed_native_passes(self):
        """Real-toolchain variant of the re-signed pass: one dylib under two
        different ad-hoc identities has identical canonical content."""
        lib = self.clang_dylib("ok", 42)
        img = self.adhoc_sign(lib, "img", "ch.lkmc.fixture.img", "--options", "runtime")
        ref = self.adhoc_sign(lib, "ref", "ch.lkmc.fixture.ref")
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", img
        )
        self.fx.write(f"{jdk}/Contents/Home/lib/server/libjvm.dylib", ref)
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    @unittest.skipUnless(
        sys.platform == "darwin" and shutil.which("clang") and shutil.which("codesign"),
        "needs the macOS host toolchain (clang + codesign)",
    )
    def test_macos_real_code_tamper_fails(self):
        """Real-toolchain variant of the tamper fail: a different function body
        under the same signing treatment stays a normalized violation."""
        lib = self.clang_dylib("ok", 42)
        evil = self.clang_dylib("evil", 43)
        img = self.adhoc_sign(evil, "img", "ch.lkmc.fixture.img", "--options", "runtime")
        ref = self.adhoc_sign(lib, "ref", "ch.lkmc.fixture.ref")
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", img
        )
        self.fx.write(f"{jdk}/Contents/Home/lib/server/libjvm.dylib", ref)
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)


if __name__ == "__main__":
    unittest.main()
