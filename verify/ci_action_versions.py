#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核对 workflow 里每个 `uses:` 指向的 action 究竟跑在哪个 Node 上。

为什么需要这个脚本
------------------
GitHub 会对「还在用 Node 20 的 action」给出弃用注解，并**强行**把它塞到 Node 24 上跑。
清掉注解之后，靠什么保证它不会回来？靠读 workflow 的文字是**读不出来**的 ——
`uses: actions/upload-artifact@v5` 看着像"已经是新主版本了"，但 v5 的 `runs.using`
仍然是 `node20`（v5 只是"预先支持" node24，默认不启用）。这就是本脚本存在的理由：
**用 GitHub 上真实的 action.yml 说话，而不是靠版本号猜。**

顺带还能挡住另一类改动：某个 action 的新大版本引入破坏性变更或**许可变更**
（例：`gradle/actions@v6` 把缓存拆成专有组件 `gradle-actions-caching`，升上去等于
接受 Gradle 商业条款）。脚本会把每一版的运行时打出来，升级前先跑一遍。

判据
----
读目标 ref 的 `action.yml`，取 `runs.using`。
  - `node24` / `node22` → 合格
  - `node20` / `node16` → **不合格**，退出码 1
  - `composite` / `docker` → 与 Node 无关，跳过（不算问题）

用法
----
    python verify/ci_action_versions.py                 # 扫 .github/workflows/
    python verify/ci_action_versions.py --offline       # 只列引用，不联网

注意：本机是原生 Windows Python，**读不了 Git Bash 的 /tmp/... 路径**，
传入路径请用 Windows 风格（或在 bash 里用 `cygpath -w` 转换）。
"""

import argparse
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("需要 PyYAML：pip install pyyaml\n")
    raise SystemExit(2)

# Node 版本 → 是否合格。22 也算合格（不是弃用目标），但实践中很少见。
OK_RUNTIMES = {"node24", "node22"}
# 明确有问题的：GitHub 会为这些发弃用注解
BAD_RUNTIMES = {"node20", "node16", "node12"}
# 与 Node 无关的运行方式
SKIP_RUNTIMES = {"composite", "docker"}

USES_RE = re.compile(r"^(?P<owner>[^/@]+)/(?P<repo>[^/@]+)(?P<sub>/.*)?@(?P<ref>.+)$")


def find_workflows(root):
    """返回 root 下所有 workflow 文件（.github/workflows/*.yml|yaml），按名称排序。"""
    out = []
    d = os.path.join(root, ".github", "workflows")
    if not os.path.isdir(d):
        return out
    for name in sorted(os.listdir(d)):
        if name.endswith((".yml", ".yaml")):
            out.append(os.path.join(d, name))
    return out


def walk_steps(node, path=""):
    """递归找出所有 `uses:`，返回 [(在文件里的位置描述, uses 字符串)]。

    递归而不是按 jobs.*.steps 取，是因为可能有 reusable workflow（jobs.x.uses）
    与嵌套结构；这里只想「不漏」。
    """
    found = []
    if isinstance(node, dict):
        for k, v in node.items():
            here = "%s.%s" % (path, k) if path else str(k)
            if k == "uses" and isinstance(v, str):
                found.append((here, v))
            else:
                found.extend(walk_steps(v, here))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            found.extend(walk_steps(v, "%s[%d]" % (path, i)))
    return found


def collects(root):
    """扫出所有 (workflow 文件, 位置, uses 文本)。跳过本地 action 与 docker://。"""
    items = []
    for wf in find_workflows(root):
        with io.open(wf, encoding="utf-8") as f:
            try:
                doc = yaml.safe_load(f)
            except Exception as e:
                items.append((wf, "<YAML 解析失败>", "!! %s" % e))
                continue
        for where, uses in walk_steps(doc):
            if uses.startswith("./") or uses.startswith("docker://"):
                continue  # 本地 action、容器 action，与 Node 运行时无关
            items.append((wf, where, uses))
    return items


def fetch_action_yml(owner, repo, sub, ref, tries=4, timeout=25):
    """取 action.yml 原文。走环境里的代理（若设置了 https_proxy）。"""
    subpath = (sub or "").strip("/")
    url = "https://raw.githubusercontent.com/%s/%s/%s/%saction.yml" % (
        owner, repo, ref, (subpath + "/") if subpath else "")
    last = None
    for attempt in range(1, tries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "wb-ci-audit/1.0"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return url, r.read().decode("utf-8", "replace"), None
        except urllib.error.HTTPError as e:
            # 404 是确定的"不存在"，不必重试
            if e.code == 404:
                return url, None, "HTTP 404（该 ref 下没有 action.yml）"
            last = "HTTP %s" % e.code
        except Exception as e:  # 网络抖动，重试
            last = "%s: %s" % (type(e).__name__, e)
        if attempt < tries:
            time.sleep(1.5 * attempt)
    return url, None, last


def runtime_of(text):
    """从 action.yml 里读 runs.using。解析失败返回 (None, 原因)。"""
    try:
        d = yaml.safe_load(text)
    except Exception as e:
        return None, "action.yml 解析失败: %s" % e
    if not isinstance(d, dict):
        return None, "action.yml 不是映射"
    runs = d.get("runs") or {}
    using = runs.get("using")
    if not using:
        return None, "没有 runs.using"
    return str(using).strip().strip("'\""), None


def main():
    ap = argparse.ArgumentParser(description="核对 workflow 里 action 的 Node 运行时")
    ap.add_argument("root", nargs="?", default=".",
                    help="工程根目录（含 .github/workflows），默认当前目录")
    ap.add_argument("--offline", action="store_true",
                    help="不联网，只列出 workflow 里引用了哪些 action")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    items = collects(root)
    if not items:
        print("在 %s/.github/workflows 下没找到任何 workflow" % root)
        return 0

    by_ref = {}   # uses 文本 -> (runtime, err)
    bad, unknown = [], []

    print("workflow 里的 action 引用（共 %d 处）：" % len(items))
    for wf, where, uses in items:
        rel = os.path.relpath(wf, root)
        if uses.startswith("!!"):
            print("  %-28s %-34s %s" % (rel, where, uses))
            unknown.append((rel, where, uses, "YAML 解析失败"))
            continue
        print("  %-28s %-34s %s" % (rel, where, uses))

        if args.offline:
            continue

        m = USES_RE.match(uses)
        if not m:
            unknown.append((rel, where, uses, "不是 owner/repo[/path]@ref 形式"))
            continue

        if uses in by_ref:
            runtime, err = by_ref[uses]
        else:
            url, text, err = fetch_action_yml(m.group("owner"), m.group("repo"),
                                              m.group("sub"), m.group("ref"))
            if text is None:
                runtime = None
            else:
                runtime, err = runtime_of(text)
            by_ref[uses] = (runtime, err)

        tag = "" if runtime else "  <未知>"
        print("        └─ runs.using = %s%s" % (runtime or "?", tag))
        if err:
            print("           (%s)" % err)

        if runtime in BAD_RUNTIMES:
            bad.append((rel, where, uses, runtime))
        elif runtime and runtime not in OK_RUNTIMES and runtime not in SKIP_RUNTIMES:
            unknown.append((rel, where, uses, "未预期的 runs.using=%s" % runtime))
        elif runtime is None:
            unknown.append((rel, where, uses, err or "取不到 action.yml"))

    print()
    if args.offline:
        print("--offline：未联网核对运行时，仅列出引用。")
        return 0

    if bad:
        print("！！仍有使用已弃用 Node 运行时的 action（%d 处）：" % len(bad))
        for rel, where, uses, runtime in bad:
            print("   %-28s %-34s %-28s %s" % (rel, where, uses, runtime))
        print()
        print("提示：不同仓库把 node24 放在哪一版**各不相同**，不要按同一个数字套。")
        print("      actions/checkout        → v5")
        print("      actions/setup-java      → v5")
        print("      actions/upload-artifact → v6   （v5 仍是 node20！）")
        print("      gradle/actions          → v5   （v6 引入专有缓存组件与商业条款）")
        return 1

    if unknown:
        print("有 %d 处无法判定（网络或形态问题），请人工确认：" % len(unknown))
        for rel, where, uses, why in unknown:
            print("   %-28s %-34s %s  ← %s" % (rel, where, uses, why))
        return 2

    distinct = sorted({u for _, _, u in items})
    print("全部通过 ✅  共 %d 处引用 / %d 个不同 action，均未使用已弃用的 Node 运行时。"
          % (len(items), len(distinct)))
    for u in distinct:
        print("   %-34s runs.using = %s" % (u, by_ref.get(u, (None,))[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
