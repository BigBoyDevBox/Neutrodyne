#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The desktop image gates' fixture checks (11 Image scan rules, 09 Build-output
checks; review-341 findings I1–I3).

Runs the shipped check-desktop-image.sh, check-runtime-sources.sh and
make-runtime-sources-md.sh in a throwaway fixture repo — the scripts resolve the
lockfiles and the runtime-classpath manifest relative to their own location, so
the real code paths run against fixture images:

  * I1 — image JARs are members of the Licensee-checked classpath under their
    *mangled* image names (<base>-<md5>.jar, what the manifest records) and
    identical to the row's sha256. The documented out-of-manifest case is the
    skiko stub, accepted only while the manifest carries the same
    skiko-awt-runtime-<os>-<arch>-*.jar entry — no blanket allowlist.
  * I2 — a jpackage .app keeps the runtime home at
    Contents/runtime/Contents/Home, not at Contents/runtime; release/legal/
    MODULES resolve under the home while the native-file comparison keeps the
    Contents/runtime root (its rel paths pair with jdk/Contents/Home).
  * I3 — a .dmg payload is mounted read-only (hdiutil attach -nobrowse
    -readonly), scanned with the same image rules and always detached. The
    fixture never mounts a real DMG: a fake hdiutil copies a payload tree into
    the mountpoint and records attach/detach.
"""

import hashlib
import io
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[3]
CI = REPO / "scripts/ci"

JAVA_VERSION = "25.0.4.1"  # fixture runtime.lock mirrors the real one's shape


def unpadded_md5(data: bytes) -> str:
    """Compose's contentHash: the jar's MD5 rendered per byte with
    Integer.toHexString — no zero padding (DesktopPackaging.kt imageJarName)."""
    return "".join(format(b, "x") for b in hashlib.md5(data).digest())


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def mangled(stem: str, data: bytes) -> str:
    return f"{stem}-{unpadded_md5(data)}.jar"


class Fixture:
    """A fixture repo: the real scripts and lockfiles, a fixture manifest and
    fixture images under root/. env["PATH"] prepends fakebin."""

    def __init__(self, root: Path):
        self.root = root
        (root / "scripts/ci").mkdir(parents=True)
        (root / "scripts/desktop").mkdir(parents=True)
        for script in (
            "check-desktop-image.sh",
            "check-runtime-sources.sh",
            "make-runtime-sources-md.sh",
            "canonical-jar-hash.py",
        ):
            shutil.copy2(CI / script, root / "scripts/ci" / script)
        shutil.copy2(
            REPO / "scripts/desktop/smoke-start.sh",
            root / "scripts/desktop/smoke-start.sh",
        )
        for lock in (
            "desktopApp/runtime.lock",
            "desktopApp/wix.lock",
            "desktopApp/packaging/deb/control",
            "desktopApp/packaging/rpm/template.spec",
            "youtube/ytdlp-desktop/python-components.lock",
        ):
            (root / lock).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(REPO / lock, root / lock)
        (root / "desktopApp/build/desktop-packaging").mkdir(parents=True)
        (root / "fakebin").mkdir()
        self.env = {
            **{k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "GH_"))},
            "PATH": str(root / "fakebin") + os.pathsep + os.environ["PATH"],
        }
        self.manifest_rows = {}

    def canonical_hash(self, data: bytes) -> str:
        """The shipped checker's canonical identity (canonical-jar-hash.py)."""
        if not data.startswith(b"PK"):
            return sha256(data)  # non-zip fallback binds the raw bytes
        path = self.root / "tmp/canonical-input"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        r = subprocess.run(["python3", str(self.root / "scripts/ci/canonical-jar-hash.py"), str(path)],
                           capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            raise AssertionError(r.stderr)
        return r.stdout.strip()

    def manifest(self, stem: str, data: bytes, name=None) -> str:
        """Record a classpath row like writeDesktopRuntimeClasspath writes:
        '<canonical sha256>  <mangled image name>' — the fixture's image jars then
        use it."""
        image_name = name or mangled(stem, data)
        self.manifest_rows[image_name] = self.canonical_hash(data)
        return image_name

    def write_manifest(self):
        text = "".join(f"{h}  {n}\n" for n, h in sorted(self.manifest_rows.items()))
        (self.root / "desktopApp/build/desktop-packaging/runtime-classpath.txt").write_text(
            text, encoding="utf-8"
        )

    def write(self, rel: str, data: bytes):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def run(self, *args, env_extra=None) -> subprocess.CompletedProcess:
        env = dict(self.env)
        env.update(env_extra or {})
        return subprocess.run(
            ["bash", *map(str, args)],
            cwd=self.root, env=env, capture_output=True, text=True, timeout=120,
        )

    def image_check(self, *inputs, env_extra=None):
        return self.run(
            self.root / "scripts/ci/check-desktop-image.sh", *inputs,
            env_extra=env_extra,
        )


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


# --- canonical JAR identity fixtures -------------------------------------------

def zip_bytes(entries, compression=zipfile.ZIP_DEFLATED, extra=b""):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=compression) as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name)
            info.extra = extra
            z.writestr(info, data)
    return out.getvalue()


def duplicate_zip_bytes():
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("A.class", b"CODE-A")
        z.writestr("A.class", b"CODE-B")
    return out.getvalue()


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


class DesktopImageGatesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.fx = Fixture(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    # ----- fixture builders -------------------------------------------------

    def flat_image(self, name="Neutrodyne", extra_jars=(), manifest_skiko=True):
        """A Linux-style image: flat lib/runtime, bin/ launcher, lib/app JARs."""
        fx = self.fx
        img = f"imgs/{name}"
        fx.write(f"{img}/bin/Neutrodyne", b"#!/bin/sh\n")
        fx.write(f"{img}/lib/runtime/release", release_file())
        fx.write(f"{img}/lib/runtime/legal/LICENSE", b"GPL-2.0 Classpath exception\n")
        fx.write(f"{img}/lib/runtime/lib/jrt-fs.jar", b"JRT")
        for stem, data in (("core", b"CORE"), ("util", b"UTIL")):
            fx.write(f"{img}/lib/app/{fx.manifest(stem, data)}", data)
        if manifest_skiko:
            # The Licensee-checked classpath entry for the real skiko jar.
            fx.manifest(
                "skiko-awt-runtime-linux-x64-0.9.30",
                b"SKIKO-REAL",
                name=mangled("skiko-awt-runtime-linux-x64-0.9.30", b"SKIKO-REAL"),
            )
        return f"{img}"

    def skiko_stub(self, img, os_arch="linux-x64", version="0.9.30"):
        """Compose's stub jar: name carries the stub's own content hash."""
        data = b"SKIKO-STUB-" + os_arch.encode()
        name = f"skiko-awt-runtime-{os_arch}-{version}-{unpadded_md5(data)}.jar"
        self.fx.write(f"{img}/lib/app/{name}", data)
        return name

    def mac_app(self, root_dir="imgs/Neutrodyne.app", jars=(("app", b"APP"),)):
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
        for stem, data in jars:
            fx.write(f"{app}/Contents/app/{fx.manifest(stem, data)}", data)
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

    def fake(self, name: str, body: str):
        path = self.root / "fakebin" / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def fake_hdiutil(self):
        # attach requires -nobrowse and -readonly (the gate's read-only mount);
        # the 'volume' is the sibling <dmg>.payload/ tree, copied into the
        # mountpoint so no real DMG is ever mounted by the fixture.
        self.fake(
            "hdiutil",
            """
log="${FAKE_HDIUTIL_LOG:?}"
case "${1:-}" in
    attach)
        shift
        mnt="" dmg="" ro=0 nb=0
        while [ $# -gt 0 ]; do
            case "$1" in
                -readonly) ro=1; shift ;;
                -nobrowse) nb=1; shift ;;
                -mountpoint) mnt="$2"; shift 2 ;;
                -*) shift ;;
                *) dmg="$1"; shift ;;
            esac
        done
        [ "$ro" = 1 ] && [ "$nb" = 1 ] || { echo "attach without -readonly/-nobrowse" >&2; exit 1; }
        [ -n "${FAKE_HDIUTIL_FAIL_ATTACH:-}" ] && exit 1
        [ -n "$mnt" ] && [ -d "$dmg.payload" ] || exit 1
        cp -R "$dmg.payload/." "$mnt/"
        echo "attach $mnt" >>"$log"
        ;;
    detach)
        mnt="$2"
        echo "detach $mnt" >>"$log"
        [ -z "${FAKE_HDIUTIL_FAIL_DETACH:-}" ] || exit 1
        ;;
    *) exit 1 ;;
esac
""",
        )

    def fake_codesign_ok(self):
        self.fake("codesign", "exit 0\n")

    # ----- I1: JAR classpath membership --------------------------------------

    def test_flat_image_passes(self):
        """Mangled-name jars matching the manifest's name+sha256, the stamped
        skiko stub accepted via the manifest's same-os-arch skiko row."""
        img = self.flat_image()
        self.skiko_stub(img)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_jar_content_mismatch_fails(self):
        """The name is on the manifest but the bytes are not the row's sha256."""
        img = self.flat_image()
        self.fx.write(f"{img}/lib/app/{mangled('core', b'CORE')}", b"CORE-EVIL")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the Licensee-checked", r.stderr)

    def test_unlisted_stamped_jar_fails(self):
        img = self.flat_image()
        data = b"ROGUE"
        self.fx.write(f"{img}/lib/app/{mangled('rogue', data)}", data)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the Licensee-checked runtime classpath", r.stderr)

    def test_unstamped_jar_fails(self):
        """A jar whose -<suffix> is not its own md5 is not a manifest entry
        either (manifest rows are all content-stamped names)."""
        img = self.flat_image()
        self.fx.write(f"{img}/lib/app/core-deadbeef.jar", b"CORE")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the Licensee-checked runtime classpath", r.stderr)

    def test_skiko_stub_needs_the_manifest_skiko_entry(self):
        """No blanket allowlist: a stamped skiko stub fails when the manifest
        carries no skiko-awt-runtime-<os>-<arch>-*.jar row."""
        img = self.flat_image(manifest_skiko=False)
        self.skiko_stub(img)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko-awt-runtime-linux-x64", r.stderr)

    def test_skiko_stub_needs_the_same_os_arch(self):
        img = self.flat_image()  # manifest skiko row is linux-x64
        self.skiko_stub(img, os_arch="windows-x64")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko-awt-runtime-windows-x64", r.stderr)

    # ----- I2: the macOS runtime home is Contents/runtime/Contents/Home -------

    def test_macos_app_runtime_sources_image(self):
        """check-runtime-sources --image on a .app: release/legal under the
        nested home, native files still compared against jdk/Contents/Home."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        r = self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt",
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_macos_app_native_compare_still_binds(self):
        """Root path meaning preserved: an image file with no counterpart under
        jdk/Contents/Home still fails (the compare did not silently loosen)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/extra.dylib", MACHO
        )
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        r = self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt",
        )
        self.assertNotEqual(0, r.returncode)
        self.assertIn("no counterpart in the pinned Temurin archive", r.stderr)

    def test_macos_layout_in_image_scan(self):
        """check-desktop-image's runtime layout check resolves the nested home
        (a .app dir name pulls in codesign, so this image root uses the same
        Contents/ layout without the .app suffix)."""
        app = self.mac_app(root_dir="imgs/NestedApp")
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_make_runtime_sources_md_reads_the_nested_home(self):
        self.mac_app()
        r = self.fx.run(
            self.root / "scripts/ci/make-runtime-sources-md.sh",
            "--image", "imgs/Neutrodyne.app", "--out", "RUNTIME-SOURCES.md",
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        out = (self.root / "RUNTIME-SOURCES.md").read_text(encoding="utf-8")
        self.assertIn("java.base", out)

    def test_flat_runtime_still_works(self):
        """The flat lib/runtime root keeps resolving as before."""
        app = "imgs/FlatApp"
        self.fx.write(f"{app}/lib/runtime/release", release_file("java.base"))
        r = self.fx.run(
            self.root / "scripts/ci/make-runtime-sources-md.sh",
            "--image", app, "--out", "OUT.md",
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # ----- I3: DMG payload validation -----------------------------------------

    def dmg(self, payload_app=None, name="dist/neutrodyne-1.0.0-macos-arm64.dmg"):
        self.fx.write(name, b"DMG-BYTES")
        if payload_app is not None:
            # the fake mount's volume contents live in <dmg>.payload/
            src = self.root / payload_app
            dst = self.root / f"{name}.payload" / "Neutrodyne.app"
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(src, dst)
        return name

    def test_dmg_payload_gets_the_image_scan(self):
        app = self.mac_app()
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(dmg, env_extra={"FAKE_HDIUTIL_LOG": str(log)})
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        entries = log.read_text(encoding="utf-8").splitlines()
        self.assertTrue(any(e.startswith("attach ") for e in entries), entries)
        self.assertTrue(any(e.startswith("detach ") for e in entries), entries)

    def test_dmg_payload_violation_fails(self):
        """The mounted payload receives the identical required checks: a jar
        outside the Licensee-checked classpath fails inside the DMG too."""
        app = self.mac_app(jars=(("app", b"APP"),))
        rogue = b"ROGUE"
        self.fx.write(f"{app}/Contents/app/{mangled('rogue', rogue)}", rogue)
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(dmg, env_extra={"FAKE_HDIUTIL_LOG": str(log)})
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the Licensee-checked runtime classpath", r.stderr)
        self.assertIn("detach ", log.read_text(encoding="utf-8"))

    def test_dmg_detach_is_guaranteed_on_abort(self):
        """A hard exit mid-scan (here: a failing python3 inside the JAR entry
        scan) still detaches the mount through the EXIT trap."""
        app = self.mac_app()
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        self.fake("python3", "exit 1\n")
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(dmg, env_extra={"FAKE_HDIUTIL_LOG": str(log)})
        self.assertNotEqual(0, r.returncode)
        self.assertIn("detach ", log.read_text(encoding="utf-8"))

    def test_dmg_attach_failure_fails(self):
        app = self.mac_app()
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(
            dmg,
            env_extra={
                "FAKE_HDIUTIL_LOG": str(log),
                "FAKE_HDIUTIL_FAIL_ATTACH": "1",
            },
        )
        self.assertNotEqual(0, r.returncode)
        self.assertIn("attach", r.stderr)



    # ----- canonical JAR identity (packaging recompression / re-signing) ------

    def zjar(self, img, stem, data, mutate=None):
        """Record a manifest row for data, write mutate(data) (or data) under the
        row's mangled name, then run the image scan."""
        name = self.fx.manifest(stem, data)
        self.fx.write(f"{img}/lib/app/{name}", data if mutate is None else mutate(data))
        self.fx.write_manifest()
        return self.fx.image_check(img)

    def assertJarFails(self, data, mutated):
        r = self.zjar(self.flat_image(), "zcore", data, mutate=lambda _: mutated)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the Licensee-checked", r.stderr)

    def test_canonical_jar_identity(self):
        """Recompression/reordering is stable; content, entry-set, duplicate and
        malformed-name mutations fail; a non-zip is its raw sha256."""
        entries = [("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"), ("A.class", b"CODE-A")]
        r = self.zjar(self.flat_image(), "zcore", zip_bytes(entries),
                      mutate=lambda _: zip_bytes(list(reversed(entries)),
                                                 compression=zipfile.ZIP_STORED, extra=b"PAD"))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        simple = zip_bytes([("A.class", b"CODE-A")])
        self.assertJarFails(simple, zip_bytes([("A.class", b"CODE-B")]))
        self.assertJarFails(simple, zip_bytes([("A.class", b"CODE-A"), ("B.class", b"CODE-B")]))
        self.assertJarFails(simple, duplicate_zip_bytes())
        self.assertJarFails(simple, zip_bytes([("../evil.class", b"CODE")]))
        self.assertEqual(sha256(b"NOT-A-ZIP"), self.fx.canonical_hash(b"NOT-A-ZIP"))

    def test_macho_identity_rules(self):
        """Re-signing is stable; code and out-of-segment signature mutations fail."""
        data = zip_bytes([("lib.dylib", thin_macho())])
        r = self.zjar(self.flat_image(), "zcore", data,
                      mutate=lambda _: zip_bytes([("lib.dylib", thin_macho(sig=b"OTHER"))]))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertJarFails(data, zip_bytes([("lib.dylib", thin_macho(code=b"CODE-B"))]))
        self.assertJarFails(data, zip_bytes([("lib.dylib", thin_macho(sig=b"A" * 16, dataoff=0x400, total=0x2000))]))

    def test_skiko_stub_does_not_require_its_own_md5_stamp(self):
        """Membership is the manifest row's trusted name; the stub's md5 stamp is
        not recomputed (packaging changes the stub bytes)."""
        img = self.flat_image()
        self.fx.write(f"{img}/lib/app/skiko-awt-runtime-linux-x64-0.9.30-deadbeef.jar", b"SKIKO-STUB")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

if __name__ == "__main__":
    unittest.main()
