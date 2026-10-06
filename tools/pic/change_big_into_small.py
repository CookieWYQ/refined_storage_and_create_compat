#!/usr/bin/env python3
"""
将高分辨率像素风格图片（视觉上 16×16 色块）转换为真正的低分辨率像素图片。
支持：
- 自动保留原图透明度（若有 Alpha 通道）
- 手动指定透明色（将 RGB 颜色设为透明）
- 排除水印区域（矩形框内像素不参与采样）
- 交互式 / 命令行两种运行方式
"""

import numpy as np
from PIL import Image
import argparse
import sys

def convert_to_pixel_art(input_path, output_path, grid_size=16,
                         watermark_rect=None, transparent_color=None):
    """
    Args:
        transparent_color (tuple): RGB 颜色，若指定则强制将该颜色的像素透明
                                   （无论原图是否透明，都会覆盖该位置的平均 Alpha 为 0）
    """
    # 以 RGBA 模式读取（若原图无 Alpha，则自动添加不透明通道）
    img = Image.open(input_path).convert('RGBA')
    width, height = img.size

    # 若尺寸不能被 grid_size 整除，裁剪边缘
    if width % grid_size != 0 or height % grid_size != 0:
        new_w = width - (width % grid_size)
        new_h = height - (height % grid_size)
        img = img.crop((0, 0, new_w, new_h))
        width, height = new_w, new_h
        print(f"警告：原图尺寸不能被 {grid_size} 整除，已裁剪为 {width}x{height}")

    # 转为 NumPy 数组 (H, W, 4)
    arr = np.array(img)
    # 分离 RGB 和 Alpha
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3].astype(np.float32) / 255.0  # 归一化到 [0,1] 便于加权

    # 权重矩阵（1=参与平均，0=忽略），与 Alpha 区分
    weights = np.ones((height, width), dtype=np.float32)

    # 排除水印区域
    if watermark_rect is not None:
        x1, y1, x2, y2 = watermark_rect
        x1 = max(0, min(x1, width))
        x2 = max(0, min(x2, width))
        y1 = max(0, min(y1, height))
        y2 = max(0, min(y2, height))
        if x1 < x2 and y1 < y2:
            weights[y1:y2, x1:x2] = 0.0
            print(f"已忽略水印区域：({x1},{y1}) 到 ({x2},{y2})")
        else:
            print("警告：水印区域无效，忽略")

    # 重塑为 (grid_size, block_h, grid_size, block_w, ...)
    block_h = height // grid_size
    block_w = width // grid_size

    rgb_reshaped = rgb.reshape(grid_size, block_h, grid_size, block_w, 3)
    alpha_reshaped = alpha.reshape(grid_size, block_h, grid_size, block_w)
    weights_reshaped = weights.reshape(grid_size, block_h, grid_size, block_w)

    # 加权平均（RGB 和 Alpha 均按权重加权）
    # 分子：加权像素和
    sum_weighted_rgb = np.sum(rgb_reshaped * weights_reshaped[..., np.newaxis], axis=(1, 3))
    sum_weighted_alpha = np.sum(alpha_reshaped * weights_reshaped, axis=(1, 3))
    sum_weights = np.sum(weights_reshaped, axis=(1, 3))

    # 后备：未加权平均（当某块权重和为0时使用）
    avg_rgb_all = rgb_reshaped.mean(axis=(1, 3)).astype(np.float32)
    avg_alpha_all = alpha_reshaped.mean(axis=(1, 3))

    # 初始化输出数组
    out_rgb = np.zeros((grid_size, grid_size, 3), dtype=np.uint8)
    out_alpha = np.zeros((grid_size, grid_size), dtype=np.float32)

    for i in range(grid_size):
        for j in range(grid_size):
            if sum_weights[i, j] > 0:
                avg_rgb = sum_weighted_rgb[i, j] / sum_weights[i, j]
                avg_a = sum_weighted_alpha[i, j] / sum_weights[i, j]
            else:
                avg_rgb = avg_rgb_all[i, j]
                avg_a = avg_alpha_all[i, j]
            out_rgb[i, j] = np.clip(np.round(avg_rgb), 0, 255).astype(np.uint8)
            out_alpha[i, j] = np.clip(avg_a, 0.0, 1.0)

    # 如果用户指定了透明色，则强制将该颜色的像素 Alpha 设为 0（覆盖平均 Alpha）
    if transparent_color is not None:
        trans = np.array(transparent_color, dtype=np.uint8)
        mask = np.all(out_rgb == trans, axis=-1)
        out_alpha[mask] = 0.0
        print(f"已指定透明色 {transparent_color}，匹配像素将被完全透明。")

    # 将 Alpha 转换回 0-255 整数
    out_alpha_uint8 = (out_alpha * 255).astype(np.uint8)

    # 判断输出模式：若所有 Alpha 均为 255 且未指定透明色，则输出 RGB（无 Alpha）
    if np.all(out_alpha_uint8 == 255) and transparent_color is None:
        out_img = Image.fromarray(out_rgb, 'RGB')
        print("输出 RGB 图片（不透明）")
    else:
        rgba = np.dstack((out_rgb, out_alpha_uint8))
        out_img = Image.fromarray(rgba, 'RGBA')
        print("输出 RGBA 图片（包含透明度）")

    out_img.save(output_path)
    print(f"转换完成！输出图片尺寸：{out_img.size}，保存至：{output_path}")

# ---------- 辅助函数 ----------
def parse_watermark(s):
    parts = list(map(int, s.split()))
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("水印区域需要四个整数：x1 y1 x2 y2")
    return tuple(parts)

def parse_color(s):
    parts = list(map(int, s.split()))
    if len(parts) != 3:
        raise argparse.ArgumentTypeError("颜色需要三个整数（R G B），范围 0-255")
    if not all(0 <= v <= 255 for v in parts):
        raise argparse.ArgumentTypeError("颜色值必须在 0-255 之间")
    return tuple(parts)

# ---------- 主入口 ----------
if __name__ == "__main__":
    if len(sys.argv) >= 2:
        # 命令行模式
        parser = argparse.ArgumentParser(
            description="将像素风格图片转换为真正的低分辨率图片，支持透明度保留与水印排除。"
        )
        parser.add_argument('input', help='输入图片路径')
        parser.add_argument('output', help='输出图片路径（建议 .png）')
        parser.add_argument('--grid', type=int, default=16, help='网格数（输出尺寸），默认16')
        parser.add_argument('--watermark', type=parse_watermark, default=None,
                            help='水印区域像素坐标，格式：x1 y1 x2 y2')
        parser.add_argument('--transparent-color', type=parse_color, default=None,
                            help='强制透明颜色（RGB），例如 "255 255 255"（会覆盖原透明度）')
        args = parser.parse_args()
        try:
            convert_to_pixel_art(args.input, args.output, args.grid,
                                 args.watermark, args.transparent_color)
        except Exception as e:
            print(f"错误：{e}", file=sys.stderr)
            sys.exit(1)
    else:
        # 交互模式
        print("未检测到命令行参数，进入交互模式。")
        input_path = input("请输入输入图片路径: ").strip()
        if not input_path:
            print("输入路径不能为空，程序退出。")
            sys.exit(1)
        output_path = input("请输入输出图片路径（建议 .png）: ").strip()
        if not output_path:
            print("输出路径不能为空，程序退出。")
            sys.exit(1)
        grid_str = input("请输入网格数（输出图片尺寸，默认为16）: ").strip()
        grid = int(grid_str) if grid_str else 16

        # 水印排除
        watermark_rect = None
        while True:
            choice = input("是否排除水印区域？(y/n): ").strip().lower()
            if choice in ('y', 'yes'):
                print("请输入水印矩形区域的左上角和右下角像素坐标，格式：x1 y1 x2 y2")
                print("例如：1800 1900 2048 2048")
                coords = input("坐标: ").strip()
                if coords:
                    parts = list(map(int, coords.split()))
                    if len(parts) == 4:
                        watermark_rect = tuple(parts)
                        break
                    else:
                        print("输入错误，需要四个整数，请重新输入。")
                else:
                    print("未输入坐标，跳过水印排除。")
                    break
            elif choice in ('n', 'no'):
                break
            else:
                print("请输入 y 或 n")

        # 透明处理
        transparent_color = None
        # 先询问是否想指定透明色（覆盖原透明度）
        while True:
            choice = input("是否指定一种颜色强制透明（将覆盖原透明度）？(y/n): ").strip().lower()
            if choice in ('y', 'yes'):
                print("请输入要透明的颜色 RGB 值（例如 255 255 255）：")
                color_str = input("颜色: ").strip()
                if color_str:
                    parts = list(map(int, color_str.split()))
                    if len(parts) == 3 and all(0 <= v <= 255 for v in parts):
                        transparent_color = tuple(parts)
                        break
                    else:
                        print("输入错误，需要三个 0-255 的整数，请重新输入。")
                else:
                    print("未输入颜色，跳过。")
                    break
            elif choice in ('n', 'no'):
                break
            else:
                print("请输入 y 或 n")

        try:
            convert_to_pixel_art(input_path, output_path, grid,
                                 watermark_rect, transparent_color)
        except Exception as e:
            print(f"错误：{e}", file=sys.stderr)
            sys.exit(1)