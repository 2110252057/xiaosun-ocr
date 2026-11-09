#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态自检：找出「调用了 Ui / Palette 的成员，但没写对应 import static」的文件。

为什么需要（2.12 首次构建的真实事故）：
新建 RegionSelectActivity 时只写了 `import static com.xiaosun.ocr.Ui.dp;`，
但代码里还调了 `solidBg(this, bg, 12)`。javac 报了一个错：

    RegionSelectActivity.java:311: error: cannot find symbol
            btn.setBackground(solidBg(this, bg, 12));

这类错误**只有编译期才会暴露**，而且三个脚本全过：
    · check_colors.py   —— 只查「色常量是否只在 Palette 定义」
    · check_imports.py  —— 只查「import 了没用」（多余）
    · check_this_scope.py —— 只查「this 当 Context」
全是「多用」或「用法错」，没有一个是「用了但没导入」。

所以补这个脚本，专门查「少用」：

  ① Ui 成员（dp / solidBg / borderedBg / wide / wideTop）
     必须满足其中之一：`import static com.xiaosun.ocr.Ui.*;`
     或 `import static com.xiaosun.ocr.Ui.<该成员>;`
     或本文件自己定义了同名方法（LogCard 就自己有一份 dp(Activity,float)）。

  ② Palette 的 14 个 C_ 常量：必须是 `import static com.xiaosun.ocr.Palette.*;`
     或逐个具名导入。

判断「调用了」时先剥离注释与字符串 —— 否则注释里写一句「已改用 solidBg()」
就会被当成真调用（这坑 check_imports.py 的注释里已经踩过，这里沿用同一个
单趟状态机）。

用法：
    python3 tools/check_static_imports.py

返回码 0 = 全部齐备；1 = 有缺失（逐条列出「文件 + 缺哪个成员」）。
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'xiaosun', 'ocr')

UI = 'import static com.xiaosun.ocr.Ui.*;'
UI_NAMED = 'import static com.xiaosun.ocr.Ui.'
PAL = 'import static com.xiaosun.ocr.Palette.*;'
PAL_NAMED = 'import static com.xiaosun.ocr.Palette.'


def scan(s):
    """单趟剥离注释与字面量，返回纯代码文本（与 check_imports.py 同一算法）。"""
    out = []
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        nxt = s[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = s.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and nxt == '*':
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 2
            out.append(' ')
            continue
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


def ui_members():
    """从 Ui.java 读出它提供的静态方法名。"""
    path = os.path.join(SRC, 'Ui.java')
    code = scan(open(path, encoding='utf-8').read())
    return sorted(set(re.findall(r'static\s+[\w.<>\[\]]+\s+(\w+)\s*\(', code)))


def palette_members():
    """从 Palette.java 读出它提供的色常量名。"""
    path = os.path.join(SRC, 'Palette.java')
    code = scan(open(path, encoding='utf-8').read())
    return sorted(set(re.findall(r'static\s+final\s+int\s+(\w+)', code)))


def own_methods(code):
    """本文件自己定义的同名方法（自己有一份就不需要导入）。

    ★ 必须连**实例方法**一起算，不能只看 static。
    EdgeGestureService 里有 `private int dp(float v)`（实例方法），
    第一版只匹配 static，于是把它的 `this.dp(...)` 全当成缺导入报了出来 ——
    这是脚本自己的 bug，不是代码问题。
    宁可放宽（多算了几个名字）也不要误报：误报会让人关掉这个检查。
    """
    return set(re.findall(r'(?:static\s+)?[\w.<>\[\]]+\s+(\w+)\s*\(', code))


def main():
    ui = ui_members()
    pal = palette_members()
    if not ui or not pal:
        print('读不出 Ui / Palette 的成员，脚本本身有问题：ui=%s pal=%s' % (ui, pal))
        return 1

    files = [p for p in sorted(glob.glob(os.path.join(SRC, '*.java')))
             if os.path.basename(p) not in ('Ui.java', 'Palette.java')]
    if not files:
        print('找不到源码目录：%s' % SRC)
        return 1

    total = 0
    for path in files:
        raw = open(path, encoding='utf-8').read()
        code = scan(raw)
        # import 行本身不算「调用」
        body = '\n'.join(l for l in code.split('\n')
                         if not l.strip().startswith('import '))

        has_ui_star = UI in raw
        has_pal_star = PAL in raw
        ui_named = set(re.findall(r'import static com\.xiaosun\.ocr\.Ui\.(\w+);', raw))
        pal_named = set(re.findall(r'import static com\.xiaosun\.ocr\.Palette\.(\w+);', raw))
        own = own_methods(code)

        missing = []

        for m in ui:
            if has_ui_star or m in ui_named or m in own:
                continue
            # 方法调用：排除 obj.method( 这种
            if re.search(r'(?<![\w.])%s\s*\(' % re.escape(m), body):
                missing.append('Ui.%s' % m)

        for m in pal:
            if has_pal_star or m in pal_named:
                continue
            if re.search(r'(?<![\w.])%s\b' % re.escape(m), body):
                missing.append('Palette.%s' % m)

        if missing:
            total += len(missing)
            print(os.path.basename(path))
            for m in missing:
                print('    缺 import static: %s' % m)

    if total:
        print('\n✗ 共 %d 处「用了但没导入」（编译期才会报 cannot find symbol）' % total)
        return 1

    print('✓ 检查 %d 个文件，Ui(%d 个成员) / Palette(%d 个常量) 的静态导入全部齐备'
          % (len(files), len(ui), len(pal)))
    return 0


if __name__ == '__main__':
    sys.exit(main())