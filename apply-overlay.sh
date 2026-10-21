#!/usr/bin/env bash
# PhotonCamera 增量补丁应用脚本（本地 / GitHub Actions 通用）
#
# 用法:
#   ./apply-overlay.sh <overlay目录> <上游源码目录>
#   例: ./apply-overlay.sh overlay upstream
#
# 行为（按优先级）:
#   1. git apply --check 成功 → 精确合并（hunk 级，上游没动过的
#      区域自动跟上上游最新代码）
#   2. 已完整应用过（--reverse --check 通过）→ 跳过（幂等，可重复运行）
#   3. 仅因上次残留的新增文件冲突 → 清掉残留后重试精确合并
#   4. 上游真改了同一区域 → 降级为 files/ 完整文件覆盖：
#      功能保住，该补丁涉及文件的上游更新丢失，报告列出，
#      建议后续手动 rebase
#   5. 输出应用报告（stdout；CI 下同时写入 $GITHUB_STEP_SUMMARY）
set -euo pipefail

OVERLAY_DIR="${1:?用法: apply-overlay.sh <overlay目录> <上游源码目录>}"
TARGET_DIR="${2:?用法: apply-overlay.sh <overlay目录> <上游源码目录>}"

cd "$TARGET_DIR"

REPORT=""
APPLIED=0
OVERRIDDEN=0
SKIPPED=0

add_report() {
    REPORT="${REPORT}${1}
"
    echo "$1"
}

for patch in "$OVERLAY_DIR"/patches/*.patch; do
    [ -e "$patch" ] || { echo "!! patches/ 目录为空"; exit 1; }
    name="$(basename "$patch" .patch)"

    # 从 patch 头解析涉及的源文件（b/ 侧路径）与新增文件
    mapfile -t patch_files < <(
        grep '^diff --git ' "$patch" | sed 's|^diff --git a/.* b/||'
    )
    mapfile -t new_files < <(
        awk '/^diff --git /{p=$NF; sub(/^b\//,"",p)} /^new file mode/{print p}' "$patch"
    )

    # 路径 A: 精确合并
    if git apply --check "$patch" 2>/dev/null && git apply "$patch"; then
        add_report "| ✅ applied | \`$name\` | 精确合并，上游其余更新全保留 |"
        APPLIED=$((APPLIED + 1))
        continue
    fi

    # 路径 B: 已应用过（幂等重入）
    if git apply --reverse --check "$patch" 2>/dev/null; then
        add_report "| ✅ already | \`$name\` | 检测到已应用过，跳过（可重复运行） |"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    # 路径 C: 上次应用残留的新增文件挡路 → 清掉与 files/ 一致的残留后重试
    cleaned=0
    for f in "${new_files[@]:-}"; do
        [ -n "$f" ] || continue
        if [ -f "$f" ] && cmp -s "$f" "$OVERLAY_DIR/files/$f" 2>/dev/null; then
            rm "$f"
            cleaned=1
        fi
    done
    if [ "$cleaned" -eq 1 ] && git apply --check "$patch" 2>/dev/null && git apply "$patch"; then
        add_report "| ✅ applied | \`$name\` | 清理上次残留后精确合并 |"
        APPLIED=$((APPLIED + 1))
        continue
    fi

    # 路径 D: 覆盖降级
    missing=0
    for f in "${patch_files[@]}"; do
        src="$OVERLAY_DIR/files/$f"
        if [ -f "$src" ]; then
            mkdir -p "$(dirname "$f")"
            cp "$src" "$f"
        else
            missing=1
        fi
    done
    if [ "$missing" -eq 0 ]; then
        add_report "| ⚠️ overridden | \`$name\` | 上游改了同一区域，已用完整文件覆盖。涉及文件的上游更新丢失，建议有空时手动 rebase |"
        OVERRIDDEN=$((OVERRIDDEN + 1))
    else
        add_report "| ❌ failed | \`$name\` | 覆盖降级也失败（files/ 缺文件），需手动处理 |"
        exit 2
    fi
done

# 签名文件兜底（不在任何 patch 里，纯资源）
if [ -f "$OVERLAY_DIR/files/app/release.keystore" ]; then
    mkdir -p app
    cp "$OVERLAY_DIR/files/app/release.keystore" app/release.keystore
fi

echo
echo "===== 增量应用完成: $APPLIED 个合并/重入, $SKIPPED 个跳过, $OVERRIDDEN 个覆盖 ====="

# CI: 写入 job summary
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "## 增量补丁应用报告"
        echo
        echo "| 状态 | 补丁 | 说明 |"
        echo "|---|---|---|"
        echo "$REPORT"
    } >> "$GITHUB_STEP_SUMMARY"
fi
