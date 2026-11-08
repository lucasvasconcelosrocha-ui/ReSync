#!/usr/bin/env python3
import os
import sys
import subprocess
from PIL import Image, ImageDraw

def generate_icons():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_path = os.path.join(base_dir, 'app/src/main/res/drawable-nodpi/logo_resync.webp')
    if not os.path.exists(src_path):
        src_path = os.path.join(base_dir, 'design/cropped_transparent_symbol.png')
    
    if not os.path.exists(src_path):
        print(f"Error: source image not found at {src_path}", file=sys.stderr)
        sys.exit(1)

    src_img = Image.open(src_path).convert('RGBA')
    bg_color = (11, 31, 58, 255) # #0B1F3A

    # Remove any stale unadorned duplicate icons in drawable/
    stale_duplicates = [
        os.path.join(base_dir, 'app/src/main/res/drawable/ic_launcher_foreground.png'),
        os.path.join(base_dir, 'app/src/main/res/drawable/ic_launcher_monochrome.png'),
        os.path.join(base_dir, 'app/src/main/res/drawable/ic_launcher_foreground.webp'),
        os.path.join(base_dir, 'app/src/main/res/drawable/ic_launcher_monochrome.webp'),
    ]
    for stale in stale_duplicates:
        if os.path.exists(stale):
            os.remove(stale)

    # 1. Adaptive foreground and monochrome (canvas 108dp, logo ~58% width, safe zone 66%)
    adaptive_densities = {
        'drawable-mdpi': 108,
        'drawable-hdpi': 162,
        'drawable-xhdpi': 216,
        'drawable-xxhdpi': 324,
        'drawable-xxxhdpi': 432
    }

    for folder, size in adaptive_densities.items():
        out_dir = os.path.join(base_dir, 'app/src/main/res', folder)
        os.makedirs(out_dir, exist_ok=True)

        target_w = int(round(size * 58 / 108))
        target_h = int(round(src_img.height * (target_w / src_img.width)))
        resized_logo = src_img.resize((target_w, target_h), Image.Resampling.LANCZOS)

        # Foreground
        fg_canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        offset_x = (size - target_w) // 2
        offset_y = (size - target_h) // 2
        fg_canvas.paste(resized_logo, (offset_x, offset_y), resized_logo)
        fg_webp = os.path.join(out_dir, 'ic_launcher_foreground.webp')
        fg_canvas.save(fg_webp, 'WEBP', quality=85)
        # Clean up legacy PNG if present
        legacy_png = os.path.join(out_dir, 'ic_launcher_foreground.png')
        if os.path.exists(legacy_png):
            os.remove(legacy_png)

        # Monochrome (pure white with original alpha)
        mono_canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        _, _, _, a = fg_canvas.split()
        white = Image.new('L', (size, size), 255)
        mono_img = Image.merge('RGBA', (white, white, white, a))
        mono_webp = os.path.join(out_dir, 'ic_launcher_monochrome.webp')
        mono_img.save(mono_webp, 'WEBP', quality=85)
        legacy_mono_png = os.path.join(out_dir, 'ic_launcher_monochrome.png')
        if os.path.exists(legacy_mono_png):
            os.remove(legacy_mono_png)

    # 2. Legacy icons (mipmap-*) with solid #0B1F3A background
    legacy_densities = {
        'mipmap-mdpi': 48,
        'mipmap-hdpi': 72,
        'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144,
        'mipmap-xxxhdpi': 192
    }

    for folder, size in legacy_densities.items():
        out_dir = os.path.join(base_dir, 'app/src/main/res', folder)
        os.makedirs(out_dir, exist_ok=True)

        target_w = int(round(size * 0.62))
        target_h = int(round(src_img.height * (target_w / src_img.width)))
        resized_logo = src_img.resize((target_w, target_h), Image.Resampling.LANCZOS)
        offset_x = (size - target_w) // 2
        offset_y = (size - target_h) // 2

        # Square / squircle icon
        canvas_sq = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        mask_sq = Image.new('L', (size, size), 0)
        draw_sq = ImageDraw.Draw(mask_sq)
        radius = max(2, int(size * 0.20))
        draw_sq.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
        bg_sq = Image.new('RGBA', (size, size), bg_color)
        canvas_sq.paste(bg_sq, (0, 0), mask_sq)
        canvas_sq.paste(resized_logo, (offset_x, offset_y), resized_logo)
        sq_webp = os.path.join(out_dir, 'ic_launcher.webp')
        canvas_sq.save(sq_webp, 'WEBP', quality=85)
        legacy_sq_png = os.path.join(out_dir, 'ic_launcher.png')
        if os.path.exists(legacy_sq_png):
            os.remove(legacy_sq_png)

        # Round icon
        canvas_rd = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        mask_rd = Image.new('L', (size, size), 0)
        draw_rd = ImageDraw.Draw(mask_rd)
        draw_rd.ellipse((0, 0, size - 1, size - 1), fill=255)
        bg_rd = Image.new('RGBA', (size, size), bg_color)
        canvas_rd.paste(bg_rd, (0, 0), mask_rd)
        canvas_rd.paste(resized_logo, (offset_x, offset_y), resized_logo)
        rd_webp = os.path.join(out_dir, 'ic_launcher_round.webp')
        canvas_rd.save(rd_webp, 'WEBP', quality=85)
        legacy_rd_png = os.path.join(out_dir, 'ic_launcher_round.png')
        if os.path.exists(legacy_rd_png):
            os.remove(legacy_rd_png)

    print("✅ [generateAppIcons] Ícones adaptativos e legados gerados com sucesso em formato WebP (q=85) sem duplicações!")

if __name__ == '__main__':
    generate_icons()
