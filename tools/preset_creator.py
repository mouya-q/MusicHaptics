#!/usr/bin/env python3
"""
preset_creator.py — 预设创建工具
─────────────────────────────────────────────────────────────────
为已知歌曲快速生成触觉预设（手动调参版 + 自动分析版）
"""
import argparse
import json


def create_default_preset(song: str, bass: float = 1.4, mid: float = 1.0) -> dict:
    return {
        "preset_name": f"Manual_{song}",
        "manual_tuning": {
            "bass_boost": bass,
            "mid_boost": mid,
            "treble_boost": 1.0,
        },
        "description": f"手动预设：低音强调 {bass}x，适合 {song}",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--song", default="Generic")
    parser.add_argument("--output", default="presets/default.json")
    args = parser.parse_args()
    preset = create_default_preset(args.song)
    with open(args.output, "w") as f:
        json.dump(preset, f, indent=2)
    print(f"Preset created: {args.output}")
