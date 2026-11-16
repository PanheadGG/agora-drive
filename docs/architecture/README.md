# AgoraDrive 架构图（drawio）

本目录存放架构图的 **drawio 中间文件** 及导出图，中英双版，版式一致。

图的中列**就是论文 2.1 节的三个绑定面**，一列一个面，与正文一一对应：权责面（Authority）、暴露面（Exposure）、溯源面（Provenance）。左列是原料与产物，右列是市场与交付，下排是模板注册表与案例库。

| 文件 | 作用 |
| --- | --- |
| `build-architecture.cjs` | 图源生成脚本：几何共享，中英文案分表，图标为内联 SVG |
| `agent-cloud-disk-architecture.drawio` | **中文版 drawio 中间文件** |
| `agent-cloud-disk-architecture-en.drawio` | **English drawio source file** |
| `export-architecture.cjs` | 用本机 Chrome + diagrams.net viewer 导出 2x PNG |
| `agent-cloud-disk-architecture.png` / `-en.png` | 2x 位图导出（3202×1526） |

改图流程：改 `build-architecture.cjs` → `node build-architecture.cjs`（两种语言都生成，也可传 `zh` 或 `en`）→ `node export-architecture.cjs`。
也可直接在 draw.io 里改 `.drawio`，但下次跑生成脚本会覆盖手改内容。

## 图中每一块对应的代码

| 图元 | 代码位置 |
| --- | --- |
| ① 意图域 · 4 个内置模板 | `backend/src/agent/market/ProductTemplateRegistry.kt`（字段 / groupBy / measures / cues / tags） |
| ② Source 原材料、流式解析 | `user_file.expandRows` → `backend/src/agent/FileContentParser.kt`、`OcrService.kt`、`PdfTextExtractor.kt` |
| 权责面 · Authority（谁能打开哪些文件） | `ProductIntentParser.parse`（`ruleMatch` / `LlmClient.chatJson`，置信度低于 55 标 needsConfirm）+ `user_file.expandRows` 生成的范围记录 |
| 暴露面 · Exposure（什么可以离开网盘） | `FileContentParser` 主机侧解析、`StreamingSensitiveScanner`（32 字符回看）、`ProductBuilder.anonymizeAggregate`（`K_ANONYMITY = 3`） |
| 溯源面 · Provenance（事后能不能核对） | `ProductBuilder.buildReceipt`（sources / excluded / digest / quality / model）与 `ProductBuilder.extract` 的排除分支 |
| ③ 数据产品（产品卡） | `data_product` / `product_listing` 表，`SellerAgentService.runBuild` 写入 |
| 模板注册表 / 案例库 | `ProductTemplateRegistry`；`product_build_item`（采纳与排除的事实） |
| ④ 市场与交付 | `MarketService.publish` / `createDemand` / `matchDemand` / `requestDelivery` |

## 视觉约定

- 图标是**内联 SVG data URI**（`shape=image`），不依赖外部图片文件，`.drawio` 单文件自包含、可离线打开编辑。
- 注意：diagrams.net viewer 只渲染 URL-encode 的 data URI，base64 会显示成占位图；颜色里的 `#` 用 `encodeURIComponent` 转义后仍是合法 XML。
- 分组框用极浅的横向渐变 + 圆角区分层级；三个 Agent 阶段用胶囊形标题条 + 左侧语义图标（意图 / 分组 / 校验）。
- 图标只在有意义的地方出现：模板卡（通勤 / 菜谱 / 宠物 / 书籍）、数据源、产品、市场四步、模板库与案例库、Output 卡右上角。不堆装饰。

## 说明

- 产品卡里的「5 组 / 1 个文件 / 62 分 / 2 号线 42.3」是演示种子数据的示例值（`demo` 账号 13 个混合格式文件），不是常量。
- 图中的 `digest: 8f3c…` 表示凭证摘要取 sha256 前 32 位（`ProductBuilder.digestOf`）。
- 协同（`CollabCoordinator`）在后端仍可用、前端已隐藏，因此图中未画成主链路。
- 图中不再画运行时调用链（`SellerController` → 线程池 → 轮询），那属于底层实现；正文 4 节已用文字说明。

---

# AgoraDrive architecture (English)

This folder holds the **drawio source** of the architecture diagram plus its exports, in Chinese and English, sharing one layout: raw material and product on the left, the three-stage agent pipeline in the middle, market and delivery on the right, schema and case repositories along the bottom.

| File | Purpose |
| --- | --- |
| `build-architecture.cjs` | Diagram source: shared geometry, per-language copy, inline SVG icons |
| `agent-cloud-disk-architecture-en.drawio` | **English drawio source**, open it in draw.io / diagrams.net |
| `agent-cloud-disk-architecture.drawio` | Chinese drawio source |
| `export-architecture.cjs` | Exports a `.drawio` to a 2x PNG via local Chrome + the diagrams.net viewer |
| `agent-cloud-disk-architecture-en.png` | 2x export, 3202×1526 |

To change the diagram: edit `build-architecture.cjs`, run `node build-architecture.cjs` (both languages, or pass `zh` / `en`), then `node export-architecture.cjs`. Editing the `.drawio` directly works too, but the generator overwrites it on the next run.

## What each block maps to in the code

| Block | Code |
| --- | --- |
| ① Four built-in templates | `backend/src/agent/market/ProductTemplateRegistry.kt` (fields / groupBy / measures / cues / tags) |
| ② Source raw material, streaming parse | `user_file.expandRows` → `backend/src/agent/FileContentParser.kt`, `OcrService.kt`, `PdfTextExtractor.kt` |
| Authority surface (who may open what) | `ProductIntentParser.parse` (`ruleMatch` / `LlmClient.chatJson`; confidence under 55 sets needsConfirm) plus the scope record built by `user_file.expandRows` |
| Exposure surface (what may leave) | host-side `FileContentParser`, `StreamingSensitiveScanner` (32-char tail), `ProductBuilder.anonymizeAggregate` (`K_ANONYMITY = 3`) |
| Provenance surface (checkable after) | `ProductBuilder.buildReceipt` (sources / excluded / digest / quality / model) and the exclusion branches of `ProductBuilder.extract` |
| ③ Data product (the card) | tables `data_product` / `product_listing`, written by `SellerAgentService.runBuild` |
| Schema / case repositories | `ProductTemplateRegistry`; `product_build_item` (the kept-and-dropped facts) |
| ④ Market and delivery | `MarketService.publish` / `createDemand` / `matchDemand` / `requestDelivery` |

## Visual conventions

- Icons are **inline SVG data URIs** (`shape=image`), so the `.drawio` is self-contained and editable offline with no external image files.
- The diagrams.net viewer only renders URL-encoded data URIs — base64 shows a placeholder — and escaping `#` with `encodeURIComponent` keeps the URI XML-safe.
- Group frames use a very light horizontal gradient and rounded corners to separate levels; the three agent stages get capsule headers with one semantic icon each (intent / grouping / gate).
- Icons appear only where they carry meaning: template cards, source, product, the four market steps, both repositories, and the top-right corner of each Output card.

## Notes

- The card values (5 groups / 1 file / score 62 / Line 2 42.3) are sample output from the seeded demo data (13 mixed-format files for the `demo` account), not constants.
- `digest: 8f3c…` means the receipt digest is the first 32 hex characters of the source sha256 (`ProductBuilder.digestOf`).
- Collaboration (`CollabCoordinator`) still works on the backend but is hidden in the UI, so it is not drawn as a main path.
- The runtime call chain (`SellerController` → thread pool → polling) is deliberately not drawn: that is implementation detail, and Section 4 of the paper covers it in prose.
