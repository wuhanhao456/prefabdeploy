# Prefab Deploy CurseForge 发布素材

本目录包含 Prefab Deploy 0.1.3 的发布图标、简介和中英双语说明。文字依据仓库当前文档编写，保留本地导入范围、蓝图版本限制和可选资源来源的版本要求。

## 图标

- `prefabdeploy-icon-400.png`：400×400 RGB PNG，用于 CurseForge 项目图标。
- `prefabdeploy-icon-1024.png`：1024×1024 RGB PNG，保留较大尺寸。
- `prefabdeploy-tool-transparent-1024.png`：1024×1024 RGBA PNG，仅包含工具模型，可用于后续排版。
- `icon-render.json`：模型来源、纹理帧、视角参数和输入输出 SHA-256。

图标直接读取建筑建造工具的模型和原始纹理，共 195 个元素，包括蓝图面板、塔吊和城堡全息预览。城堡动画纹理固定使用第 0 帧。模型以正交视角渲染，再加入背景和阴影；该图是项目图标，不是游戏截图。

CurseForge 的[项目提交指南](https://support.curseforge.com/support/solutions/articles/9000199552-overview-of-the-project-submission-page)要求方形 PNG，尺寸至少为 400×400；[审核规则](https://support.curseforge.com/support/solutions/articles/9000197279-project-and-modpack-moderation-policies)列出的图标尺寸为 400×400。本目录提供这一尺寸，核对日期为 2026-10-05。

## 发布文本

- `summary.txt`：英文和中文单行简介。项目 Summary 字段可选择其中一行。
- `description.en.md`：完整英文说明。
- `description.zh-CN.md`：完整中文说明。
- `description.bilingual.md`：英文在前、中文在后的合并版，可直接粘贴到支持 Markdown 的 Description 编辑器。

说明面向玩家和整合包作者，直接写出操作、条件和限制。中文和英语分别组织句子，保持“建筑建造工具 / Building Tool”“建造 / build / construction”等术语一致。正文链接指向项目仓库，不依赖本地相对路径。

发布时上传 `prefabdeploy-0.1.3.jar`。此目录未上传至 CurseForge，也未更改 Mod JAR 或游戏模型。

## 重新渲染

在仓库根目录运行以下命令。Python 环境需要 Pillow 和 NumPy。

```powershell
python tools/render_curseforge_icon.py
```

默认输出到本目录。可通过 `--azimuth`、`--elevation` 和 `--frame` 调整视角及城堡动画帧。脚本只读取 `src/main/resources/assets/`，不会改写模型和纹理。

## 检查范围

已核对 PNG 格式、尺寸、模型来源和资源哈希，并检查图标在 400×400 和缩小后的显示。发布说明已与 README、数据包、费用和资源兼容文档核对。

本次工作没有运行新的游戏测试。这里的检查只覆盖发布素材；CurseForge 编辑器中的排版和实际上传结果需在发布时确认。
