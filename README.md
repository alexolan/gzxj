# PhotonCamera 增量补丁包（Overlay）

把「闪退修复 + AI 构图 + 诊断日志」从完整源码里剥出来做成的增量包。
配合 `overlay-build.yml` 工作流使用：每次构建自动拉上游最新代码，再叠加
本增量包——上游作者更新后无需重新打包，直接跑工作流即可升级。

## 包内容

```
overlay/
├── patches/                  # 增量补丁（相对基线 commit 6b7afa28）
│   ├── 0001-crash-fix-metadata-safe-read.patch   闪退修复（5 文件）
│   ├── 0002-diagnostic-logging.patch              诊断日志（4 文件）
│   └── 0003-ai-composition.patch                 AI 构图功能（7 文件）
├── files/                    # 上述补丁涉及文件的完整版 + 签名 keystore
│                               （patch 冲突时的降级覆盖源）
├── apply-overlay.sh          # 应用脚本（本地 / CI 通用）
├── manifest.json             # 补丁清单
└── README.md                 # 本文件
```

## 升级机制

上游更新后的三种情况：

| 上游改动位置 | 结果 |
|---|---|
| 与补丁无关的文件 | patch 精确合并，上游更新全保留 |
| 补丁文件里、但区域不重叠 | 同上（git apply 是 hunk 级合并） |
| 补丁文件里、同一区域被上游改动 | 自动降级：完整文件覆盖，功能不丢，该文件上游更新暂缺（Summary 会列出，后续手动 rebase 即可） |

## 在 GitHub 上使用（推荐）

1. 新建一个仓库（或用现有仓库），把 `overlay/` 整个目录传上去
2. 把 `overlay-build.yml` 传到 `.github/workflows/`
3. Actions 页面 Run workflow（可选填上游分支/tag）→ 产物：
   - `overlay-apk`：签好名的 release APK
   - `overlay-mapping`：R8 混淆映射（还原崩溃堆栈用）
   - `overlay-apply-report`：本次补丁应用报告（哪些精确合并/哪些覆盖）

之后上游每次更新，重跑工作流即可；也可以改 workflow 里的
`upstream_ref` 输入指定上游某个 tag 构建。

## 在本地使用

```bash
git clone https://github.com/bjzhou/PhotonCamera.git upstream
cd upstream
git checkout <想用的上游版本>
# 回到外面执行应用脚本
bash overlay/apply-overlay.sh overlay upstream
# 之后正常构建
cd upstream && ./gradlew :app:assembleDefaultRelease
```

## 签名

内置 `app/release.keystore`（store/key 密码均为 `photon2026`，alias `nova`），
与 v2~v5 历史交付的 APK 同一签名，可直接覆盖安装。

## AI 构图的 API 配置

装机后：设置 → AI 服务 → 填 API Key（OpenAI 兼容格式）。
- GLM 官方：Base URL `https://open.bigmodel.cn/api/paas/v4`，模型选视觉版（带 v）
- 不配置则自动走本机离线构图分析
