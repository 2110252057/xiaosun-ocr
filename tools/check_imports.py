#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态自检：找出「import 了但代码里没用」的类型。

为什么需要：重构删掉一个方法后，它原先需要的 import 会静默变成死代码。
这类残留不会让编译失败（javac 只给 warning），但会让后来者以为那个类型
还在被使用，读代码时顺着它找，白花时间。2.9 把各页的 dp()/borderedBg()
合并进 Ui 之后，TypedValue 就是这么变多余的（已清理）。

【注意：扫描器必须单趟走完】
不能先删注释、再删字符串 —— DeviceStats 的采样脚本里含有 "/*" / "*/" 这类
shell 内容，先删注释会让正则的 `/*` 匹配到字符串中间，把几千行代码全吞掉，
于是每个 import 都显得「没用」。下面 scan() 用一个状态机一次走完，
识别行注释、块注释、字符串、字符字面量四态。

判断方式：取 import 的最后一段简单名，在剥离后的正文里按词边界找一次。
`import static` 与通配 import 不参与（没法按简单名判断）。

用法：
    python3 tools/check_imports.py

返回码 0 = 没有多余 import；1 = 有（逐条列出）。
"""
import glob
import os
import re
import sys

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   'app', 'src', 'main', 'java', 'com', 'xiaosun', 'ocr')


def scan(s):
    """单趟剥离注释与字面量，返回纯代码文本。"""
    out = []
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        nxt = s[i + 1] if i + 1 < n else ''
        # 行注释
        if c == '/' and nxt == '/':
            j = s.find('\n', i)
            i = n if j < 0 else j
            continue
        # 块注释
        if c == '/' and nxt == '*':
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 2
            out.append(' ')
            continue
        # 字符串
        if c == '"':
            i += 1
            while i < n:
                if s[i] == '\\':
                    i += 2
                    continue
                if s[i] == '"':
                    i += 1
                    break
                i += 1
            out.append('""')
            continue
        # 字符字面量
        if c == "'":
            i += 1
            while i < n:
                if s[i] == '\\':
                    i += 2
                    continue
                if s[i] == "'":
                    i += 1
                    break
                i += 1
            out.append("''")
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def main():
    files = sorted(glob.glob(os.path.join(SRC, '*.java')))
    if not files:
        print('找不到源码目录：%s' % SRC)
        return 1

    total = 0
    for path in files:
        raw = open(path, encoding='utf-8').read()
        lines = raw.split('\n')
        code = scan(raw)

        # 去掉所有 import 行后再统计使用，否则 import 语句本身会让名字「看起来被用」
        body = '\n'.join(l for l in code.split('\n')
                         if not l.strip().startswith('import '))

        unused = []
        for i, line in enumerate(lines, 1):
            st = line.strip()
            if not st.startswith('import '):
                continue
            if st.startswith('import static '):
                continue
            if st.endswith('.*;'):
                continue
            m = re.match(r'import\s+([\w.]+);', st)
            if not m:
                continue
            simple = m.group(1).rsplit('.', 1)[-1]
            if not re.search(r'\b%s\b' % re.escape(simple), body):
                unused.append((i, st))

        if unused:
            total += len(unused)
            print(os.path.basename(path))
            for line_no, st in unused:
                print('    L%-5d %s' % (line_no, st))

    if total:
        print('\n✗ 共 %d 条多余的 import（不影响编译，但会误导后来者）' % total)
        return 1

    print('✓ 检查 %d 个文件，没有多余的 import' % len(files))
    return 0


if __name__ == '__main__':
    sys.exit(main())