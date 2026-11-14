# 双栏 PDF 构建

本机没有 pandoc / LaTeX / LibreOffice，因此用 **Markdown → 双栏 HTML → Chrome printToPDF** 这条链路。

## 依赖

```bash
cd docs/www2026/build
npm install          # 安装 marked 与 mermaid
```

## 生成

```bash
cd docs/www2026/build

# 1) Markdown → 双栏 HTML（自动把 mermaid 块渲染成 SVG、截图限高、题目区跨栏）
node build-pdf.mjs ../WWW2026_Demo_AgentCloudDisk_EN.md ../WWW2026_Demo_AgentCloudDisk_EN.pdf --lang en
node build-pdf.mjs ../WWW2026_Demo_AgentCloudDisk_ZH.md ../WWW2026_Demo_AgentCloudDisk_ZH.pdf --lang zh

# 2) HTML → PDF（Chrome DevTools Protocol，A4，等 mermaid 渲染完成再导出）
node html2pdf.mjs ../WWW2026_Demo_AgentCloudDisk_EN.html ../WWW2026_Demo_AgentCloudDisk_EN.pdf
node html2pdf.mjs ../WWW2026_Demo_AgentCloudDisk_ZH.html ../WWW2026_Demo_AgentCloudDisk_ZH.pdf
```

Chrome 路径可用环境变量 `CHROME_PATH` 覆盖，默认 `C:\Program Files\Google\Chrome\Application\chrome.exe`。

## 排版约定

- A4，页边距 14/13/15/13 mm，正文双栏、栏间距 7 mm
- 题目区 `column-span: all` 跨双栏，含 venue 行与作者区
- 截图：`max-height: 46mm`（表格单元格内 40mm），自动居中
- 流程图：撑满栏宽，`max-height: 76mm`
- 图注：仅「整段只有一个 `<em>` 且以 `图 N` / `Figure N` 开头」的段落
- 表格：`table-layout: fixed`，禁止断词
