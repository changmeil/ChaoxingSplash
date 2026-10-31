"""生成开屏效果预览图：原图 / 旧版拉伸 / 新版居中留白 三栏对比。"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'splash.jpg')
OUT = os.path.join(ROOT, 'docs')

W, H = 1080, 2400  # 典型 20:9 屏幕


def margin_color(img):
    """与 SplashModule.marginColorOf 一致的逻辑。"""
    w, h = img.size
    inset = min(max(2, min(w, h) // 40), min(w, h) // 4)
    l, r, t, b = inset, w - 1 - inset, inset, h - 1 - inset
    cs = [img.getpixel((l, t)), img.getpixel((r, t)), img.getpixel((l, b)), img.getpixel((r, b)),
          img.getpixel((w // 2, t)), img.getpixel((w // 2, b)),
          img.getpixel((l, h // 2)), img.getpixel((r, h // 2))]
    rr = sum(c[0] for c in cs) // len(cs)
    gg = sum(c[1] for c in cs) // len(cs)
    bb = sum(c[2] for c in cs) // len(cs)
    for c in cs:
        if abs(c[0] - rr) > 24 or abs(c[1] - gg) > 24 or abs(c[2] - bb) > 24:
            return (255, 255, 255)
    return (rr, gg, bb)


def main():
    os.makedirs(OUT, exist_ok=True)
    im = Image.open(SRC).convert('RGB')
    print('source', im.size)

    bg = margin_color(im)
    print('margin color', bg)

    new = Image.new('RGB', (W, H), bg)
    s = min(W / im.width, H / im.height)
    dw, dh = int(im.width * s), int(im.height * s)
    new.paste(im.resize((dw, dh), Image.LANCZOS), ((W - dw) // 2, (H - dh) // 2))
    new.save(os.path.join(OUT, 'preview_fit_center.png'))
    print('fit-center', dw, 'x', dh, '-> top/bottom margin', (H - dh) // 2,
          ', left/right margin', (W - dw) // 2)

    old = im.resize((W, H), Image.LANCZOS)  # 旧版 FILL：直接拉伸铺满

    scaled_src = im.resize((W, int(im.height * (W / im.width))), Image.LANCZOS)
    gap, label_h = 40, 56
    canvas = Image.new('RGB', (W * 3 + gap * 4, H + label_h + gap * 2), (24, 26, 30))
    draw = ImageDraw.Draw(canvas)
    items = [
        (scaled_src, 'source 1408x2516'),
        (old, 'v1.0.0  FILL (stretched)'),
        (new, 'v1.1.0  fit-center (new)'),
    ]
    for i, (img, text) in enumerate(items):
        x = gap + i * (W + gap) + (W - img.width) // 2
        y = label_h + gap + (H - img.height) // 2
        canvas.paste(img, (x, y))
        draw.text((gap + i * (W + gap) + 12, gap + 16), text, fill=(236, 236, 236))

    scale = 1800 / canvas.width
    canvas = canvas.resize((int(canvas.width * scale), int(canvas.height * scale)), Image.LANCZOS)
    canvas.save(os.path.join(OUT, 'preview_compare.png'))
    print('saved compare', canvas.size)


if __name__ == '__main__':
    main()
