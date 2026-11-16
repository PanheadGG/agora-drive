# AgentCloudDisk 演示论文（中英双语）

围绕「唤醒网盘沉睡数据 → 产出可交易数据产品」组织，参照三篇 WWW'26 Demo 论文的语言风格：
`2603.05240v1`（GCAgent）、`3774905.3793123`（DriftNavi）、`oneke`（OneKE）。

## 文件

| 文件 | 说明 |
| --- | --- |
| `AgentCloudDisk_EN.md` / `.pdf` | 英文版（4 页） |
| `AgentCloudDisk_ZH.md` / `.pdf` | 中文版（4 页） |
| `figures/*.svg` | 六张配图（draw.io 生成的纯 SVG） |
| `build/` | Markdown → 双栏 HTML → PDF 的构建链路 |

## 重新构建

```bash
cd build
node build-pdf.mjs ../AgentCloudDisk_EN.md ../AgentCloudDisk_EN.html --lang en
node html2pdf.mjs ../AgentCloudDisk_EN.html ../AgentCloudDisk_EN.pdf
```

中文版把 `EN` 换成 `ZH`、`--lang en` 换成 `--lang zh`。

依赖：`marked`（已在 build/node_modules 内）；PDF 导出用本机 Chrome。

## 章节结构

1 Introduction · 2 Design and Implementation（2.1–2.6）· 3 Demonstration（3.1–3.4）· 4 Implementation and Ethics · 5 Verification · 6 Limitations · 7 Conclusion and Future Work · References
