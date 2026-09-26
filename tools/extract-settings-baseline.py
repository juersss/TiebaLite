#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提取 AppPreferencesUtils 的（属性名 → 键名/类型/默认值）基线表，作为
SettingsKeys.kt / SettingsRepository 键名红线的比对基准（Phase 5 core:data）。

用法：
  python tools/extract-settings-baseline.py <AppPreferencesUtils.kt> [--json tools/settings-baseline.json]
默认打印表格；--json 输出 JSON 供脚本比对。
"""
import json
import re
import sys


def parse_props(path):
    text = open(path, encoding="utf-8").read()
    props = []
    i = 0
    while True:
        m = re.search(r"var\s+(\w+)\s+by\s+DataStoreDelegates\.(\w+)\s*\(", text[i:])
        if not m:
            break
        start = i + m.end()  # after '('
        # balance parens
        depth = 1
        j = start
        while j < len(text) and depth > 0:
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
            j += 1
        args = text[start : j - 1]
        i = j
        name, dtype = m.group(1), m.group(2)
        key = None
        default = None
        for am in re.finditer(r"(\w+)\s*=\s*([^,)]+)(?:,|$)", args):
            arg_name, arg_val = am.group(1), am.group(2).strip()
            if arg_name == "key":
                if arg_val.startswith('"'):
                    key = arg_val.strip('"')
                elif arg_val == "AppIconUtil.PREF_KEY_APP_ICON":
                    key = "app_icon"
                else:
                    key = arg_val  # 常量名，留作人工核对
            elif arg_name == "defaultValue":
                default = arg_val
        if key is None:
            key = name
        props.append({"name": name, "type": dtype, "key": key, "default": default})
    return props


def main():
    path = sys.argv[1]
    props = parse_props(path)
    if "--json" in sys.argv:
        idx = sys.argv.index("--json")
        out = sys.argv[idx + 1]
        with open(out, "w", encoding="utf-8") as f:
            json.dump(props, f, ensure_ascii=False, indent=1)
    print(f"共 {len(props)} 个属性：")
    for p in props:
        print(f"  {p['name']:35s} {p['type']:8s} key={p['key']!r:45s} default={p['default']}")


if __name__ == "__main__":
    main()
