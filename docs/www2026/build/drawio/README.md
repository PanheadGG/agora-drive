# 论文配图（draw.io 中间产物 + SVG 导出）

六张图由 **一份布局定义** 生成两个产物，保证 draw.io 里编辑的和论文里引用的一致。

| 文件 | 作用 |
| --- | --- |
| `layout.mjs` | 唯一布局定义：画布宽度、栅格、字号、配色、节点与连线 |
| `emit-drawio.mjs` | 生成 `.drawio`（mxGraph XML），可在 draw.io 中继续编辑 |
| `emit-svg.mjs` | 生成纯 SVG（无 foreignObject、无 light-dark），直接嵌入论文 |
| `*.drawio` | draw.io 中间产物，保留以便后续手工微调 |
| `../../figures/drawio/*.svg` | 论文实际引用的图 |

## 统一规格

- 画布宽度 **760**（六张图一致），高度按内容自适应（上下各留 16px）
- 栅格 10px；圆角 8px；线宽 1.5（节点）/ 1.4（连线）
- 字号：节点 12、副标题 10、连线 11；超长文字按框宽自动收缩
- 语义配色：客户端蓝、服务绿、Agent 橙、基础设施紫、存储青、判定黄、告警红
- 连线全部正交（水平 / 垂直 / 两段折），无斜线；长边沿列间空隙绕行

## 重新生成

```bash
node emit-drawio.mjs   # → *.drawio
node emit-svg.mjs      # → ../../figures/drawio/*.svg
```

改完 `layout.mjs` 后依次执行上面两条命令，再重建 PDF。

## 在 draw.io 里打开

`*.drawio` 可直接用 draw.io 桌面版或 https://app.diagrams.net 打开编辑。
若在 GUI 里改了图并要保持论文同步，请把改动回写到 `layout.mjs`，避免两边分叉。
