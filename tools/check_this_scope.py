#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态自检：抓「`this` 不是 Context，却当成 Context 传给 Ui.dp / Ui.borderedBg 等」的错误。

背景：2.10 把各页自己的 dp() 收敛成 Ui.dp(Context, float)，调用点统一写成
`dp(this, 8)`。这在 Activity 的方法里没问题，但在这两种地方是错的：

  1. **自定义 View / 内部类**里，`this` 是那个 View 或内部类，不是 Context。
     例：EdgeGestureMenuActivity.HitPreview extends View 的 onDraw() 里
     `dp(this, 12)` —— 编译报 incompatible types。
  2. **匿名内部类**（new Runnable(){...}、new OnSeekBarChangeListener(){...}）
     里，`this` 指向匿名类实例，不是 Activity。

这两类都会让 javac 报 `incompatible types: ... cannot be converted to Context`。

【★ 为什么这个脚本必须用「单趟状态机」剥离注释与字符串】
第一版是先 re.sub 删行注释、再删块注释、再删字符串 —— 这是错的，真实的坑：
  源码里 `"content://..."`、`"http://127.0.0.1:1666"` 这类字符串含有 `//`，
  先删行注释会把字符串从这里截断，后面紧跟着的 `*/` 就被当成块注释开头，
  一路吞到下一个 `*/` 为止 —— 于是几千行代码消失，class 的结束位置算错，
  范围外的 `dp(this,` 全被误判成「在 Activity 里」，脚本报 OK。
  2.10 就是这样漏掉了 EdgeGestureMenuActivity 的 17 处，直到真实构建才炸。
（DeviceParsers 的采样脚本里还含 "/*" "*/"，同样会触发这个问题。）

所以下面 scan() 用一个循环一次走完，明确区分：
普通代码 / 行注释 / 块注释 / 字符串 / 字符字面量 五种状态。

用法：
    python3 tools/check_this_scope.py

返回码 0 = 没发现问题；1 = 有问题（逐条列出文件、行号、所在作用域）。
"""
import glob
import os
import re
import sys

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   'app', 'src', 'main', 'java', 'com', 'xiaosun', 'ocr')

# 这些方法的第一个参数必须是 Context
CONTEXT_FIRST_ARG = ['dp', 'borderedBg', 'solidBg', 'wideTop']


def scan(s):
    """单趟剥离注释与字面量，返回纯代码文本（长度与原文不必相等，但结构一致）。"""
    out = []
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        nxt = s[i + 1] if i + 1 < n else ''
        # 行注释
        if c == '/' and nxt == '/':
            j = s.find('\n', i)
            if j < 0:
                i = n
            else:
                out.append('\n')
                i = j
            continue
        # 块注释：整体替换为一个空格，避免把两侧 token 粘在一起
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


def brace_ranges(code):
    """
    返回所有「可能改变 this 含义」的作用域：
      · 具名类型：class / interface / enum / record
      · 匿名类：new Xxx(...) { ... }
    每项是 (名称, 起始, 结束)。名称只用于报错提示。

    【刻意不收 lambda】
    与匿名内部类相反，lambda 里的 `this` 指向**外层实例**，所以
    `list.forEach(v -> dp(this, 8))` 是合法的，不该报错也不用改。
    本工程已有大量这种写法（如 EdgeGesturePrefs.setMenuIconDp(this, v)），
    如果这里把 `->` 也算作作用域，会造出一堆假警报。
    """
    out = []

    # 具名类型
    for m in re.finditer(r'\b(class|interface|enum|record)\s+(\w+)', code):
        i = code.find('{', m.end())
        if i < 0:
            continue
        depth = 0
        j = i
        while j < len(code):
            if code[j] == '{':
                depth += 1
            elif code[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.append((m.group(2), m.start(), j))

    # 匿名类：new Foo(...) 后面紧跟 {，且不是 new int[]{...} 这种数组
    for m in re.finditer(r'\bnew\s+[\w.$]+(?:<[^>]*>)?\s*\(', code):
        # 跳过构造参数，找到配对的 )
        i = m.end() - 1
        depth = 0
        j = i
        while j < len(code):
            if code[j] == '(':
                depth += 1
            elif code[j] == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        # 看后面第一个非空白字符是不是 {
        k = j + 1
        while k < len(code) and code[k] in ' \t\r\n':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        # 确认不是 new int[] 之类（那种是 new 类型[），这里已经要求有 (，故安全）
        depth = 0
        j2 = k
        while j2 < len(code):
            if code[j2] == '{':
                depth += 1
            elif code[j2] == '}':
                depth -= 1
                if depth == 0:
                    break
            j2 += 1
        label = '匿名类 new %s()' % code[m.start():m.end() - 1].split('new', 1)[-1].strip().rstrip('(').strip()
        out.append((label, m.start(), j2))

    return out


def main():
    files = sorted(glob.glob(os.path.join(SRC, '*.java')))
    if not files:
        print('找不到源码目录：%s' % SRC)
        return 1

    call_re = re.compile(r'\b(?:%s)\(\s*this\s*[,)]' % '|'.join(CONTEXT_FIRST_ARG))

    total = 0
    for path in files:
        name = os.path.basename(path)[:-5]
        raw = open(path, encoding='utf-8').read()
        code = scan(raw)
        ranges = brace_ranges(code)

        problems = []
        for m in call_re.finditer(code):
            pos = m.start()
            inner = None
            for label, a, b in ranges:
                if a <= pos <= b:
                    if inner is None or a > inner[1]:
                        inner = (label, a, b)
            if inner and inner[0] != name:
                # 行号按「剥离后代码里换行数」估算不会准，所以回到原文找
                problems.append((inner[0], m.group(0)))

        if problems:
            total += len(problems)
            print(os.path.basename(path))
            for label, snippet in problems:
                print('    在 %s 里: %s' % (label, snippet))

    if total:
        print('\n✗ 共 %d 处把 `this` 当 Context 用（javac 会报 incompatible types）' % total)
        print('  正确写法：自定义 View 里用 getContext()；'
              '匿名内部类里用 OuterActivity.this')
        return 1

    print('✓ 检查 %d 个文件，没有把 `this` 当 Context 用的地方' % len(files))
    return 0


if __name__ == '__main__':
    sys.exit(main())