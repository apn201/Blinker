"""Check that every native library in an APK is 16 KB page-size aligned.

Android 15+ warns that an app "isn't 16 KB compatible" when a packaged .so has a LOAD
segment aligned below 16 KB. CameraX 1.3.x shipped such libraries; this script is how the
fix (CameraX 1.6.x) was verified.

    python tools/check_16kb_alignment.py app/build/outputs/apk/debug/app-debug.apk

Exits non-zero if any 64-bit library is not aligned. No dependencies beyond Python 3.
"""
import struct
import sys
import zipfile

PT_LOAD = 1
REQUIRED = 0x4000  # 16 KB


def load_aligns(data):
    """Return (is64, [p_align of each LOAD segment]), or (None, []) if not an ELF."""
    if data[:4] != b"\x7fELF":
        return None, []
    is64 = data[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    else:
        e_phoff = struct.unpack_from("<I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x2A)[0]
        e_phnum = struct.unpack_from("<H", data, 0x2C)[0]
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from("<I", data, off)[0] != PT_LOAD:
            continue
        if is64:
            aligns.append(struct.unpack_from("<Q", data, off + 48)[0])
        else:
            aligns.append(struct.unpack_from("<I", data, off + 28)[0])
    return is64, aligns


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    bad = 0
    with zipfile.ZipFile(sys.argv[1]) as apk:
        libs = sorted(n for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so"))
        if not libs:
            print("no native libraries in APK")
        for name in libs:
            is64, aligns = load_aligns(apk.read(name))
            if is64 is None:
                print(f"  [???] {name}: not an ELF file")
                continue
            worst = min(aligns) if aligns else 0
            ok = worst >= REQUIRED
            abi = name.split("/")[1]
            if not ok and abi in ("arm64-v8a", "x86_64"):
                bad += 1
            print(f"  [{'OK ' if ok else 'BAD'}] {name} ({'64' if is64 else '32'}-bit) "
                  f"LOAD p_align=0x{worst:x} ({worst // 1024} KB)")
    print()
    if bad:
        print(f"FAIL: {bad} 64-bit librar{'y' if bad == 1 else 'ies'} not 16 KB aligned")
        return 1
    print("OK: all 64-bit libraries are 16 KB aligned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
