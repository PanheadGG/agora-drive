# AgentCloudDisk — static demo page

A single-page, offline demonstration of the system described in
`../documents/AgentCloudDisk_EN.pdf` / `../documents/AgentCloudDisk_ZH.pdf`.

No build step, no framework, no network. Open `index.html` directly, or serve the
folder so that ES modules load (`file://` blocks module imports in Chrome).

## Run

```bash
# from the repository root
python -m http.server 8911 --directory demopage
# then open http://127.0.0.1:8911/
```

Or with Node: `npx serve demopage`.

## What it demonstrates

| View | Mirrors | What a visitor can do |
| --- | --- | --- |
| Seller studio | Fig. 1–4 | type a sentence, watch the agent choose a template, tick folders, run a build, read the product card, flip to the receipt |
| Data market | §3.4 | post a demand, see ranked pushes with the reasons each was selected |
| Collaboration | Fig. 6 | read a session code, redeem it, join, watch the joint product grow |
| Guard chain | Fig. 4 | inspect the four checks every file open passes |

The banner under the top bar names the three surfaces used in the paper
(authority / exposure / provenance) so that a screenshot of this page maps onto
the figures without extra explanation.

## Structure

```
demopage/
├── index.html
└── assets/
    ├── app.css    styles (tokens mirror the product UI, light + dark)
    ├── data.js    the static dataset (products, listings, pushes, collaboration)
    └── app.js     rendering and scripted interactions
```

## Notes for reviewers

- Every number shown (104 rows, 3 groups, quality 82, 4.5 s build) is taken from a
  real run of the live system, so the page and the paper agree.
- Interactions are scripted locally: there is no backend, and nothing is uploaded.
- The template matcher is the same keyword rule the live system falls back to, so
  typing an unrelated sentence produces a low-confidence interpretation rather than
  a confident wrong one.
- Press `Reset` to return to the initial state, which is what the page opens in.
