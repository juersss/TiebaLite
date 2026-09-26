#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 5 生成器：从 tools/settings-baseline.json（62 键基线）生成——
  1) SettingsKeys.kt 的完整内容（键名单一事实源）
  2) SettingsRepository.kt 的接口声明块 + override 块（Phase 6.5 起：旧 AppPreferencesUtils
     代理层已删除，不再生成其属性块）
  3) SettingsRepository.kt 的 override 块

用法：python tools/gen-settings-files.py   （输出到 stdout 供人工落盘/粘贴）
默认值特例映射（原为 app 侧常量，迁 core:data 后改指 SettingsKeys 常量）：
  LauncherIcons.DEFAULT_ICON  → SettingsKeys.DEFAULT_APP_ICON
  ThemeUtil.THEME_DEFAULT     → SettingsKeys.THEME_DEFAULT
  TRANSLUCENT_THEME_LIGHT     → SettingsKeys.TRANSLUCENT_THEME_LIGHT
"""
import json
import sys

BASELINE = "tools/settings-baseline.json"

TYPE_TO_KOTLIN = {"long": "Long", "boolean": "Boolean", "string": "String?", "int": "Int", "float": "Float"}
TYPE_TO_KEYFUN = {
    "long": "longPreferencesKey",
    "boolean": "booleanPreferencesKey",
    "string": "stringPreferencesKey",
    "int": "intPreferencesKey",
    "float": "floatPreferencesKey",
}
SPECIAL_DEFAULTS = {
    "LauncherIcons.DEFAULT_ICON": "SettingsKeys.DEFAULT_APP_ICON",
    "ThemeUtil.THEME_DEFAULT": "SettingsKeys.THEME_DEFAULT",
    "TRANSLUCENT_THEME_LIGHT": "SettingsKeys.TRANSLUCENT_THEME_LIGHT",
}
DEFAULT_KEYED = {  # 无显式 defaultValue 的委托默认值（与 DataStoreDelegates 定义一致）
    "long": "0L",
    "boolean": "false",
    "string": None,
    "int": "0",
    "float": "0F",
}


def snake(name):
    result = []
    for i, ch in enumerate(name):
        if ch.isupper():
            prev_lower = i > 0 and name[i - 1].islower()
            next_lower = i + 1 < len(name) and name[i + 1].islower()
            if result and (prev_lower or next_lower):
                result.append("_")
        result.append(ch)
    return "".join(result).upper()


def kotlin_str(s):
    return json.dumps(s)  # 双引号字符串字面量


def main():
    props = json.load(open(BASELINE, encoding="utf-8"))
    out = sys.stdout
    out.write("// ===== SettingsKeys.kt 键名常量块（生成自 settings-baseline.json，勿手改；改键名先改基线）=====\n")
    out.write("object SettingsKeys {\n")
    out.write("    // ── 默认值常量（原为 app 侧 ThemeUtil/LauncherIcons 的值，迁入 core:data 保持单一事实源）──\n")
    out.write('    const val THEME_DEFAULT = "tieba"\n')
    out.write("    const val TRANSLUCENT_THEME_LIGHT = 0\n")
    out.write('    const val DEFAULT_APP_ICON = "com.huanchengfly.tieba.post.MainActivityV2"\n\n')
    out.write("    // ── 键名（62 个，键名红线：一字不动）──\n")
    for p in props:
        out.write("    const val KEY_%s = %s\n" % (snake(p["name"]), kotlin_str(p["key"])))
    out.write("}\n\n")

    # Phase 6.5（2026-09-17）：旧 AppPreferencesUtils 代理层已删除（SettingsRepository 成为
    # 唯一实现），故不再生成其属性声明块。默认值块由下方 SettingsRepository override 块承担。
    out.write("// ===== SettingsRepository.kt 接口声明块（无初始化器）=====\n")
    for p in props:
        out.write("    val %s: Settings<%s>\n" % (p["name"], TYPE_TO_KOTLIN[p["type"]]))
    out.write("\n")

    out.write("// ===== SettingsRepository.kt override 块 =====\n")
    for p in props:
        d = p["default"]
        if d is None:
            d = DEFAULT_KEYED[p["type"]]
        elif d in SPECIAL_DEFAULTS:
            d = SPECIAL_DEFAULTS[d]
        default_expr = "null" if d is None else d
        # string 设置对外是 Settings<String?>（与 AppPreferencesUtils 的可空属性一致），
        # 而 stringPreferencesKey() 返回 Key<String>——泛型不变量需非受检转换（仅字符串）。
        key_fun = TYPE_TO_KEYFUN[p["type"]]
        key_expr = "%s(SettingsKeys.KEY_%s)" % (key_fun, snake(p["name"]))
        if p["type"] == "string":
            key_expr += " as Preferences.Key<String?>"
        out.write(
            "    override val %s: Settings<%s> = SimpleSettings(%s, %s)\n"
            % (p["name"], TYPE_TO_KOTLIN[p["type"]], key_expr, default_expr)
        )
    out.write("\n")

    TEST_VALUE = {"long": "1L", "boolean": "true", "int": "1", "float": "2.0f", "string": '"t"'}
    out.write("// ===== 测试：62 项写入 =====\n")
    for p in props:
        out.write("        repo.%s.set(%s)\n" % (p["name"], TEST_VALUE[p["type"]]))
    out.write("\n// ===== 测试：62 项断言 =====\n")
    for p in props:
        out.write(
            "        assertEquals(%s, prefs[%s(SettingsKeys.KEY_%s)])\n"
            % (TEST_VALUE[p["type"]], TYPE_TO_KEYFUN[p["type"]], snake(p["name"]))
        )
    out.write("\n// ===== 测试：62 项默认值对齐（APU 现值 vs repo snapshot）=====\n")
    for p in props:
        out.write("        assertEquals(apu.%s, runBlocking { repo.%s.snapshot() })\n" % (p["name"], p["name"]))


if __name__ == "__main__":
    main()
