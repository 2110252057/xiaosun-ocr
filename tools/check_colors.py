#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态自检：颜色常量（C_*）的一致性。

【2.9 之后它管三件事】

1. **用了但没定义**。色板已经收敛到 Palette.java 唯一一份，各页通过
   `import static com.xiaosun.ocr.Palette.*;` 引入。若某个文件用了 C_XXX
   却既没定义、也没有那条 import static，javac 会报 `cannot find symbol` ——
   而这四个字和「桩类缺符号」长得一模一样，做局部编译校验时极容易一起被
   过滤掉，于是直到 Gradle 全量构建才炸。这是 2.6 真实踩过的坑，所以留着。

2. **定义了两份**。C_ 常量只允许出现在 Palette.java。别的文件里再冒出一个
   `static final int C_BRAND = …` 就说明又回去各写一份了 —— 那正是 2.6 到 2.9
   之间「C_SUB 与 C_TEXT_SUB 并存」「C_DANGER 有两个不同的红」的成因。

3. **import 了但一个都没用**。留给后来者清理，或说明某页确实不再需要色板。

用法：
    python3 tools/check_colors.py

返回码 0 = 正常；1 = 有问题（逐条列出文件与行号）。
"""
import glob
import os
import re
import sys

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   'app', 'src', 'main', 'java', 'com', 'xiaosun', 'ocr')

PALETTE = 'Palette.java'
IMPORT_STATIC = 'import static com.xiaosun.ocr.Palette.*;'


def strip_comments_and_strings(s):
    """去掉注释与字符串字面量，避免把注释里的常量名算成「使用」。"""
    s = re.sub(r'//[^\n]*', '', s)
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'"(\\.|[^"\\])*"', '""', s)
    return s


def main():
    files = sorted(glob.glob(os.path.join(SRC, '*.java')))
    if not files:
        print('找不到源码目录：%s' % SRC)
        return 1

    # 先把 Palette.java 里的定义收集起来，作为全局可用的名字集合。
    palette_path = os.path.join(SRC, PALETTE)
    if not os.path.exists(palette_path):
        print('✗ 找不到 %s —— 色板必须集中在这个文件里' % PALETTE)
        return 1
    palette_code = strip_comments_and_strings(open(palette_path, encoding='utf-8').read())
    palette_names = set(re.findall(r'\bstatic final int (C_[A-Z0-9_]+)\s*=', palette_code))
    if not palette_names:
        print('✗ %s 里没有找到任何 static final int C_XXX' % PALETTE)
        return 1

    problems = []

    for path in files:
        name = os.path.basename(path)
        raw = open(path, encoding='utf-8').read()
        code = strip_comments_and_strings(raw)

        decls = re.findall(r'\bint\s+([A-Z][A-Z0-9_]*(?:\s*,\s*[A-Z][A-Z0-9_]*)*)\s*[;=]', code)
        defined_here = set()
        for part in decls:
            for n in part.split(','):
                n = n.strip()
                if n.startswith('C_'):
                    defined_here.add(n)

        used = set(re.findall(r'\b(C_[A-Z0-9_]+)\b', code))
        has_import = IMPORT_STATIC in code

        # --- 检查 2：除 Palette 外，不允许本地再定义 C_ 常量 ---
        if name != PALETTE and defined_here:
            problems.append('%s  本地重复定义了颜色常量: %s'
                            % (name, ', '.join(sorted(defined_here))))

        # --- 检查 1：用了但没定义、也没 import ---
        if name != PALETTE and used and not has_import:
            missing = sorted(used)
            problems.append('%s  用了 %s 但没有 `%s`'
                            % (name, ', '.join(missing), IMPORT_STATIC))
            for token in missing:
                pattern = re.compile(r'\b%s\b' % token)
                for i, line in enumerate(raw.split('\n'), 1):
                    if pattern.search(line) and 'int ' not in line:
                        problems.append('      L%-5d %s' % (i, line.strip()))
                        break

        # --- 检查 3：import 了却一个都没用 ---
        if name != PALETTE and has_import and not used:
            problems.append('%s  有 `%s` 但一个 C_ 常量都没用到'
                            % (name, IMPORT_STATIC))

    if problems:
        for p in problems:
            print(p)
        print('\n✗ 颜色常量有多处问题（前两类都会让 javac 报 cannot find symbol）')
        return 1

    print('✓ 检查 %d 个文件：色板只在 %s 定义（%d 个常量），其余文件均正确 import static'
          % (len(files), PALETTE, len(palette_names)))
    return 0


if __name__ == '__main__':
    sys.exit(main())