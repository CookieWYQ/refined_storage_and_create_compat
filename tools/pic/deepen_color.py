#!/usr/bin/env python3
"""
图像颜色加深工具 – 调整亮度、对比度、饱和度，自动保留透明通道。
支持交互式（无参数运行）和命令行两种模式。
"""

import argparse
import sys
from PIL import Image, ImageEnhance

def deepen_image(input_path, output_path, brightness=1.0, contrast=1.0, saturation=1.0):
    """
    调整图像的亮度、对比度和饱和度。
    - brightness: 亮度因子（<1 变暗，>1 变亮）
    - contrast:   对比度因子（<1 降低，>1 增加）
    - saturation: 饱和度因子（<1 变灰，>1 鲜艳）
    若三因子均为 1.0，则自动应用“加深”预设（亮度0.7，饱和度1.5，对比度1.0）。
    """
    img = Image.open(input_path).convert("RGBA")
    r, g, b, a = img.split()
    rgb_img = Image.merge("RGB", (r, g, b))

    # 默认加深（如果未指定任何调整）
    if brightness == 1.0 and contrast == 1.0 and saturation == 1.0:
        brightness = 0.7
        saturation = 1.5
        print("未指定参数，使用默认加深：亮度0.7，饱和度1.5")

    # 亮度
    if brightness != 1.0:
        enhancer = ImageEnhance.Brightness(rgb_img)
        rgb_img = enhancer.enhance(brightness)
        print(f"亮度调整：{brightness:.2f}")

    # 对比度
    if contrast != 1.0:
        enhancer = ImageEnhance.Contrast(rgb_img)
        rgb_img = enhancer.enhance(contrast)
        print(f"对比度调整：{contrast:.2f}")

    # 饱和度
    if saturation != 1.0:
        enhancer = ImageEnhance.Color(rgb_img)
        rgb_img = enhancer.enhance(saturation)
        print(f"饱和度调整：{saturation:.2f}")

    # 重新合并透明通道
    r, g, b = rgb_img.split()
    out_img = Image.merge("RGBA", (r, g, b, a))
    out_img.save(output_path)
    print(f"处理完成，已保存至：{output_path}")

def main():
    # 判断运行模式
    if len(sys.argv) >= 2:
        # 命令行模式
        parser = argparse.ArgumentParser(
            description="调整图像颜色（亮度/对比度/饱和度），不指定参数时自动执行“加深”（变暗+鲜艳）。"
        )
        parser.add_argument("input", help="输入图片路径")
        parser.add_argument("output", help="输出图片路径（建议 .png 保留透明度）")
        parser.add_argument("--brightness", type=float, default=1.0,
                            help="亮度因子（<1 变暗，>1 变亮），默认 1.0")
        parser.add_argument("--contrast", type=float, default=1.0,
                            help="对比度因子（<1 降低，>1 增加），默认 1.0")
        parser.add_argument("--saturation", type=float, default=1.0,
                            help="饱和度因子（<1 变灰，>1 鲜艳），默认 1.0")
        args = parser.parse_args()
        try:
            deepen_image(args.input, args.output,
                         args.brightness, args.contrast, args.saturation)
        except Exception as e:
            print(f"错误：{e}", file=sys.stderr)
            sys.exit(1)
    else:
        # 交互模式
        print("===== 颜色加深工具（交互模式）=====")
        input_path = input("请输入输入图片路径: ").strip()
        if not input_path:
            print("输入路径不能为空，程序退出。")
            sys.exit(1)
        output_path = input("请输入输出图片路径（建议 .png）: ").strip()
        if not output_path:
            print("输出路径不能为空，程序退出。")
            sys.exit(1)

        # 询问是否使用默认加深，或自定义
        while True:
            choice = input("是否使用默认“加深”预设（亮度0.7，饱和度1.5）？(y/n): ").strip().lower()
            if choice in ('y', 'yes'):
                brightness, contrast, saturation = 0.7, 1.0, 1.5
                break
            elif choice in ('n', 'no'):
                # 自定义参数
                print("请输入各调整因子（数值 >0，<1 减弱，>1 增强，1.0 不变）：")
                while True:
                    try:
                        b = input("亮度因子（默认1.0）: ").strip()
                        brightness = float(b) if b else 1.0
                        c = input("对比度因子（默认1.0）: ").strip()
                        contrast = float(c) if c else 1.0
                        s = input("饱和度因子（默认1.0）: ").strip()
                        saturation = float(s) if s else 1.0
                        # 检查合法性
                        if brightness <= 0 or contrast <= 0 or saturation <= 0:
                            print("所有因子必须大于0，请重新输入。")
                            continue
                        break
                    except ValueError:
                        print("请输入有效数字，例如 0.8 或 1.5")
                break
            else:
                print("请输入 y 或 n")

        try:
            deepen_image(input_path, output_path, brightness, contrast, saturation)
        except Exception as e:
            print(f"错误：{e}", file=sys.stderr)
            sys.exit(1)

if __name__ == "__main__":
    main()