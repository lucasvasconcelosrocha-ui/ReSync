#!/usr/bin/env python3
import os
import sys
from PIL import Image, ImageDraw

def generate_icons():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_path = os.path.join(base_dir, 'app/src/main/res/drawable-nodpi/logo_resync.png')
    if not os.path.exists(src_path):
        src_path = os.path.join(base_dir, 'design/cropped_transparent_symbol.png')
    
    if not os.path.exists(src_path):
        print(f"Error: source image not found at {src_path}", file=sys.stderr)
        sys.exit(1)

    src_img = Image.open(src_path).convert('RGBA')
    bg_color = (11, 31, 58, 255) # #0B1F3A

    # 1. Adaptive foreground and monochrome (canvas 108dp, logo ~58% width, safe zone 66%)
    adaptive_densities = {
        'drawable-mdpi': 108,
        'drawable-hdpi': 162,
        'drawable-xhdpi': 216,
        'drawable-xxhdpi': 324,
        'drawable-xxxhdpi': 432,
        'drawable-nodpi': 432,
        'drawable': 432
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
        fg_canvas.save(os.path.join(out_dir, 'ic_launcher_foreground.png'))

        # Monochrome (pure white with original alpha)
        mono_canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        _, _, _, a = fg_canvas.split()
        white = Image.new('L', (size, size), 255)
        mono_img = Image.merge('RGBA', (white, white, white, a))
        mono_img.save(os.path.join(out_dir, 'ic_launcher_monochrome.png'))

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
        canvas_sq.save(os.path.join(out_dir, 'ic_launcher.png'))

        # Round icon
        canvas_rd = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        mask_rd = Image.new('L', (size, size), 0)
        draw_rd = ImageDraw.Draw(mask_rd)
        draw_rd.ellipse((0, 0, size - 1, size - 1), fill=255)
        bg_rd = Image.new('RGBA', (size, size), bg_color)
        canvas_rd.paste(bg_rd, (0, 0), mask_rd)
        canvas_rd.paste(resized_logo, (offset_x, offset_y), resized_logo)
        canvas_rd.save(os.path.join(out_dir, 'ic_launcher_round.png'))

    print("✅ [generateAppIcons] Ícones adaptativos e legados gerados com sucesso com fundo sólido #0B1F3A!")

if __name__ == '__main__':
    generate_icons()
