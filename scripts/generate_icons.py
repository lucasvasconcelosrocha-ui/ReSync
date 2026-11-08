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

        # Clean up any legacy or duplicate monochrome files
        for extra in ['ic_launcher_foreground.png', 'ic_launcher_monochrome.png', 'ic_launcher_monochrome.webp']:
            p = os.path.join(out_dir, extra)
            if os.path.exists(p):
                os.remove(p)

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

        # Clean up any legacy or duplicate round/png files
        for extra in ['ic_launcher.png', 'ic_launcher_round.png', 'ic_launcher_round.webp']:
            p = os.path.join(out_dir, extra)
            if os.path.exists(p):
                os.remove(p)

    print("✅ [generateAppIcons] Ícones adaptativos e legados gerados com sucesso em formato WebP (q=85) sem duplicações!")

if __name__ == '__main__':
    generate_icons()
