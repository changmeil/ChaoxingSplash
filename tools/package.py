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


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename in ('classes.dex',) or item.filename.startswith('META-INF/xposed/'):
            continue
        add(zout, item.filename, zin.read(item.filename))
    with open(dex, 'rb') as f:
        add(zout, 'classes.dex', f.read())
    for name in sorted(os.listdir(meta_dir)):
        path = os.path.join(meta_dir, name)
        with open(path, 'rb') as f:
            add(zout, 'META-INF/xposed/' + name, f.read(), compress=False)

print('packaged ->', out)
