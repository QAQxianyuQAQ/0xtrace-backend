# -*- coding: utf-8 -*-
"""出题人本地工具（不进镜像）：从 puzzle_9x9.json 生成

  ① data/puzzle.json   公开数据 —— 只有碎片形状 + 印在碎片上的提示数，放进镜像
  ② data/answer.sha256 标准答案的 sha256 —— 镜像里只有哈希，没有明文答案

答案明文（regions / solution）只留在出题机上的 puzzle_9x9.json 里。
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                                   # 容器题/
DATA = os.path.join(ROOT, "data")


def _default_src():
    """题面源文件 puzzle_9x9.json：仓库布局和交付包布局都能找到。"""
    cands = [
        os.path.join(os.path.dirname(ROOT), "puzzle_9x9.json"),                      # 仓库根布局
        os.path.join(os.path.dirname(os.path.dirname(ROOT)), "04_出题人内部_勿分发",
                     "puzzle_9x9.json"),                                             # 交付包布局
        os.path.join(ROOT, "puzzle_9x9.json"),
        os.path.join(os.getcwd(), "puzzle_9x9.json"),
    ]
    for c in cands:
        if os.path.exists(c):
            return c
    return cands[0]


SRC = sys.argv[1] if len(sys.argv) > 1 else _default_src()


def canonical(n, placement, grid):
    """提交内容的规范化字符串。

    ⚠ 服务端 app/server.py 里的 canonical() 必须与这里逐字符一致，
      否则正确提交也会被判错。改动请同步修改两处（测试会交叉验证）。
    """
    pl = ",".join("%d:%d:%d" % (i, dr, dc) for i, (dr, dc) in enumerate(placement))
    gd = ",".join(str(v) for row in grid for v in row)
    return "sacc-jigsaw-sudoku-v1|N=%d|%s|%s" % (n, pl, gd)


def answer_payload(puzzle_path=SRC):
    """正确落位 [[dr,dc]×N] 与完整解 [[...]×N]。只在出题机上用，绝不进镜像。"""
    P = json.load(open(puzzle_path, encoding="utf-8"))
    n = P["N"]
    placement = [[min(r for r, _ in reg), min(c for _, c in reg)] for reg in P["regions"]]
    grid = [P["solution"][r * n:(r + 1) * n] for r in range(n)]
    return n, placement, grid


def build(puzzle_path=SRC):
    """返回 (public_data, digest, canonical_str)。"""
    P = json.load(open(puzzle_path, encoding="utf-8"))
    n = P["N"]
    pieces = [{"cells": p["cells"], "digits": p["digits"]} for p in P["pieces"]]
    _, placement, grid = answer_payload(puzzle_path)
    canon = canonical(n, placement, grid)
    return ({"N": n, "pieces": pieces}, hashlib.sha256(canon.encode()).hexdigest(), canon)


def main():
    public, digest, canon = build()
    os.makedirs(DATA, exist_ok=True)
    with open(os.path.join(DATA, "puzzle.json"), "w", encoding="utf-8") as f:
        json.dump(public, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(DATA, "answer.sha256"), "w", encoding="utf-8") as f:
        f.write(digest + "\n")
    n_givens = sum(len(p["digits"]) for p in public["pieces"])
    print("data/puzzle.json    已生成：%d 块 / %d 个提示数 / %d 字节"
          % (len(public["pieces"]), n_givens,
             os.path.getsize(os.path.join(DATA, "puzzle.json"))))
    print("data/answer.sha256  已生成：%s" % digest)
    print("规范化答案串长度 %d，明文答案只留在 %s（不进镜像）" % (len(canon), SRC))


if __name__ == "__main__":
    sys.exit(main())
