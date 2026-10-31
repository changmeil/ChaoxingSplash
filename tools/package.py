"""把 classes.dex 与 META-INF/xposed/* 塞进 aapt2 产出的 APK。"""
import os
import sys
import zipfile

src, dex, meta_dir, out = sys.argv[1:5]


def add(zout, name, data, compress=True):
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    info.external_attr = 0o644 << 16
    zout.writestr(info, data)


def must_store(name):
    """这些条目一旦被压缩，targetSdk>=30 的设备会直接拒绝安装（-124）。

    Android 11+ 强制要求 resources.arsc 以 STORED 存放并 4 字节对齐：
    Failed parse: Targeting R+ (version 30 and above) requires the
    resources.arsc of installed APKs to be stored uncompressed...
    native 库同理（extractNativeLibs=false 时必须页对齐且不压缩）。
    """
    return name == 'resources.arsc' or name.endswith('.so')


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename in ('classes.dex',) or item.filename.startswith('META-INF/xposed/'):
            continue
        # 保留 aapt2 原本的压缩策略，别自作主张重压
        store = must_store(item.filename) or item.compress_type == zipfile.ZIP_STORED
        add(zout, item.filename, zin.read(item.filename), compress=not store)
    with open(dex, 'rb') as f:
        add(zout, 'classes.dex', f.read())
    for name in sorted(os.listdir(meta_dir)):
        path = os.path.join(meta_dir, name)
        with open(path, 'rb') as f:
            add(zout, 'META-INF/xposed/' + name, f.read(), compress=False)

print('packaged ->', out)
