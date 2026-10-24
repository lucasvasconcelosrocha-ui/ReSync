#!/usr/bin/env python3
import os
import sys
from PIL import Image

def generate_icons():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_path = os.path.join(base_dir, 'app/src/main/res/drawable-nodpi/logo_resync.png')
    if not os.path.exists(src_path):
        src_path = os.path.join(base_dir, 'design/cropped_transparent_symbol.png')
    
    if not os.path.exists(src_path):
        print(f"Error: source image not found at {src_path}", file=sys.stderr)
        sys.exit(1)

    src_img = Image.open(src_path).convert('RGBA')

    # 1. Adaptive foreground and monochrome (canvas 108dp, logo 55% width, centered, transparent)
    adaptive_densities = {
        'drawable-mdpi': 108,
        'drawable-hdpi': 162,
        'drawable-xhdpi': 216,
        'drawable-xxhdpi': 324,
        'drawable-xxxhdpi': 432,
        'drawable': 432
    }

    for folder, size in adaptive_densities.items():
        out_dir = os.path.join(base_dir, 'app/src/main/res', folder)
        os.makedirs(out_dir, exist_ok=True)

        target_w = int(round(size * 0.55))
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

    # 2. Legacy icons (mipmap-*) with 100% TRANSPARENT background (no background painted)
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

        target_w = int(round(size * 0.78))
        target_h = int(round(src_img.height * (target_w / src_img.width)))
        resized_logo = src_img.resize((target_w, target_h), Image.Resampling.LANCZOS)

        legacy_canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        offset_x = (size - target_w) // 2
        offset_y = (size - target_h) // 2
        legacy_canvas.paste(resized_logo, (offset_x, offset_y), resized_logo)

        legacy_canvas.save(os.path.join(out_dir, 'ic_launcher.png'))
        legacy_canvas.save(os.path.join(out_dir, 'ic_launcher_round.png'))

    print("✅ [generateAppIcons] Ícones gerados com sucesso com fundo 100% transparente!")

if __name__ == '__main__':
    generate_icons()
