#!/usr/bin/env python3
"""
Alpha 通道二值化工具 – 将 PNG 的透明度二值化：
- 透明度超过阈值的像素变为完全不透明 (255)
- 透明度低于或等于阈值的像素变为完全透明 (0)
颜色通道保持不变。
支持交互式（无参数运行）和命令行两种模式。
"""

import argparse
import sys
import numpy as np
from PIL import Image

def binarize_alpha(input_path, output_path, threshold=0):
    """
    将 PNG 的 Alpha 通道二值化。
    Args:
        threshold (int): 0-255，Alpha 大于此值视为不透明，否则透明。
                         默认 0，即任何 >0 的透明度都变为不透明。
    """
    # 读取为 RGBA
    img = Image.open(input_path).convert("RGBA")
    # 转为 numpy 数组
    arr = np.array(img)
    # 分离 Alpha
    alpha = arr[:, :, 3]
    # 生成二值化 mask：大于阈值设为 255，否则 0
    mask = (alpha > threshold).astype(np.uint8) * 255
    # 替换 Alpha 通道
    arr[:, :, 3] = mask
    # 保存
    out_img = Image.fromarray(arr, "RGBA")
    out_img.save(output_path)
    print(f"处理完成！阈值={threshold}，输出保存至：{output_path}")

def main():
    # 判断运行模式
    if len(sys.argv) >= 2:
        # 命令行模式
        parser = argparse.ArgumentParser(
            description="将 PNG 的 Alpha 通道二值化：半透明变为全透明或全不透明。"
        )
        parser.add_argument("input", help="输入图片路径（PNG）")
        parser.add_argument("output", help="输出图片路径（PNG）")
        parser.add_argument("--threshold", type=int, default=0,
                            help="透明度阈值（0-255），默认 0。大于此值的像素变为不透明，否则透明。")
        args = parser.parse_args()
        if not (0 <= args.threshold <= 255):
            print("错误：阈值必须在 0-255 之间", file=sys.stderr)
            sys.exit(1)
        try:
            binarize_alpha(args.input, args.output, args.threshold)
        except Exception as e:
            print(f"错误：{e}", file=sys.stderr)
            sys.exit(1)
    else:
        # 交互模式
        print("===== Alpha 通道二值化工具 =====")
        input_path = input("请输入输入图片路径: ").strip()
        if not input_path:
            print("输入路径不能为空，程序退出。")
            sys.exit(1)
        output_path = input("请输入输出图片路径（建议 .png）: ").strip()
        if not output_path:
            print("输出路径不能为空，程序退出。")
            sys.exit(1)

        # 询问阈值
        while True:
            threshold_str = input("请输入透明度阈值（0-255，默认 0，即任何不透明度都变为完全不透明）: ").strip()
            if not threshold_str:
                threshold = 0
                break
            try:
                threshold = int(threshold_str)
                if 0 <= threshold <= 255:
                    break
                else:
                    print("阈值必须在 0-255 之间，请重新输入。")
            except ValueError:
                print("请输入有效整数。")

        try:
            binarize_alpha(input_path, output_path, threshold)
        except Exception as e:
            print(f"错误：{e}", file=sys.stderr)
            sys.exit(1)

if __name__ == "__main__":
    main()