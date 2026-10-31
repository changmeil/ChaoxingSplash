"""生成模块图标（纯 Python，无第三方依赖）。"""
import os
import struct
import zlib


def write_png(path, w, h, pixels):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        row = pixels[y]
        for x in range(w):
            r, g, b, a = row[x]
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def lerp(a, b, t):
    return int(round(a + (b - a) * t))


def render(size, ss=4):
    S = size * ss
    c1 = (214, 69, 65)     # #D64541
    c2 = (133, 24, 18)     # #851812
    white = (255, 255, 255, 255)
    accent = (192, 57, 43, 255)
    accent2 = (224, 122, 95, 255)

    buf = [[(0, 0, 0, 0)] * S for _ in range(S)]

    # 圆角矩形背景（半径 22%）
    r = 0.22 * S
    r2 = r * r
    for y in range(S):
        for x in range(S):
            dx = dy = 0.0
            if x < r:
                dx = r - x
            elif x > S - r:
                dx = x - (S - r)
            if y < r:
                dy = r - y
            elif y > S - r:
                dy = y - (S - r)
            if dx * dx + dy * dy > r2:
                continue
            t = (x + y) / (2.0 * (S - 1))
            buf[y][x] = (lerp(c1[0], c2[0], t), lerp(c1[1], c2[1], t), lerp(c1[2], c2[2], t), 255)

    # 白色相框
    fx0, fx1 = int(0.21 * S), int(0.79 * S)
    fy0, fy1 = int(0.23 * S), int(0.77 * S)
    fr = max(1, int(0.045 * S))
    for y in range(fy0, fy1):
        for x in range(fx0, fx1):
            dx = dy = 0.0
            if x < fx0 + fr:
                dx = fx0 + fr - x
            elif x > fx1 - fr:
                dx = x - (fx1 - fr)
            if y < fy0 + fr:
                dy = fy0 + fr - y
            elif y > fy1 - fr:
                dy = y - (fy1 - fr)
            if dx * dx + dy * dy > fr * fr:
                continue
            buf[y][x] = white

    # 相框内部裁剪区
    ix0, ix1 = fx0 + int(0.012 * S), fx1 - int(0.012 * S)
    iy0, iy1 = fy0 + int(0.012 * S), fy1 - int(0.012 * S)

    def in_frame(x, y):
        return ix0 <= x < ix1 and iy0 <= y < iy1

    # 太阳
    cx, cy, cr = 0.635 * S, 0.375 * S, 0.052 * S
    for y in range(int(cy - cr), int(cy + cr) + 1):
        for x in range(int(cx - cr), int(cx + cr) + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= cr * cr and in_frame(x, y):
                buf[y][x] = accent

    def triangle(p1, p2, p3, color):
        xs = [p1[0], p2[0], p3[0]]
        ys = [p1[1], p2[1], p3[1]]
        for y in range(int(min(ys)), int(max(ys)) + 1):
            for x in range(int(min(xs)), int(max(xs)) + 1):
                d1 = (x - p2[0]) * (p1[1] - p2[1]) - (p1[0] - p2[0]) * (y - p2[1])
                d2 = (x - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (y - p3[1])
                d3 = (x - p1[0]) * (p3[1] - p1[1]) - (p3[0] - p1[0]) * (y - p1[1])
                neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
                pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
                if (neg and pos) or not in_frame(x, y):
                    continue
                buf[y][x] = color

    triangle((0.265 * S, 0.715 * S), (0.455 * S, 0.455 * S), (0.645 * S, 0.715 * S), accent2)
    triangle((0.500 * S, 0.715 * S), (0.615 * S, 0.545 * S), (0.730 * S, 0.715 * S), accent)

    # 下采样
    out = [[(0, 0, 0, 0)] * size for _ in range(size)]
    area = ss * ss
    for y in range(size):
        for x in range(size):
            r_ = g_ = b_ = a_ = 0
            for dy in range(ss):
                for dx in range(ss):
                    px = buf[y * ss + dy][x * ss + dx]
                    a = px[3]
                    r_ += px[0] * a
                    g_ += px[1] * a
                    b_ += px[2] * a
                    a_ += a
            if a_ == 0:
                out[y][x] = (0, 0, 0, 0)
            else:
                out[y][x] = (r_ // a_, g_ // a_, b_ // a_, a_ // area)
    return out


if __name__ == '__main__':
    root = os.path.dirname(os.path.abspath(__file__))
    base = os.path.dirname(root)
    targets = [
        (os.path.join(base, 'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png'), 192),
        (os.path.join(base, 'app/src/main/res/mipmap-xhdpi/ic_launcher.png'), 96),
    ]
    for path, size in targets:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        write_png(path, size, size, render(size))
        print('icon ->', path, size)
