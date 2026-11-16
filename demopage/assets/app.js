import {
  TEMPLATES, EXAMPLES, FOLDERS, BUILD_EVENTS, PRODUCT,
  LISTINGS, DEMAND, PUSHES, COLLAB, GUARDS,
} from './data.js'

// ---------------------------------------------------------------------------
// state
// ---------------------------------------------------------------------------

const state = {
  view: 'studio',
  dark: false,
  intent: '',
  interp: null,
  interpBusy: false,
  selected: new Set(['f1', 'f2', 'f3']),
  building: false,
  built: false,
  progress: 0,
  events: [],
  revealed: 0,
  tab: 'preview',
  demandText: DEMAND.text,
  pushes: null,
  code: '',
  password: '',
  joined: false,
  tour: 0,
}

const $ = (sel, root = document) => root.querySelector(sel)

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
))

// ---------------------------------------------------------------------------
// intent → template (rule pass, same shape as the live system)
// ---------------------------------------------------------------------------

function interpret(text) {
  const q = text.toLowerCase()
  let best = null
  let bestHits = 0
  for (const t of TEMPLATES) {
    const hits = t.cues.filter((c) => q.includes(c.toLowerCase())).length
    if (hits > bestHits) { best = t; bestHits = hits }
  }
  if (!best) {
    best = TEMPLATES[0]
    return {
      template: best,
      confidence: 35,
      reason: 'No clear cue found, so the commute template is assumed. Try mentioning a domain such as commute, recipe, pet or reading.',
      needsConfirm: true,
    }
  }
  const confidence = Math.min(96, 52 + bestHits * 16)
  const cues = best.cues.filter((c) => q.includes(c.toLowerCase())).slice(0, 3)
  return {
    template: best,
    confidence,
    reason: 'The sentence mentions ' + cues.join(', ') + ', so the agent maps it onto the ' +
      best.label.toLowerCase() + ' template and will aggregate by ' + best.fields[0] + '.',
    needsConfirm: confidence < 60,
  }
}

// ---------------------------------------------------------------------------
// views
// ---------------------------------------------------------------------------

function renderShell() {
  return `
  <header class="topbar">
    <div class="brand"><span class="brand-mark">◍</span><span>AgentCloudDisk</span></div>
    <nav class="nav">
      ${navItem('studio', 'Seller studio')}
      ${navItem('market', 'Data market')}
      ${navItem('collab', 'Collaboration')}
      ${navItem('guard', 'Guard chain')}
    </nav>
    <div class="topbar-actions">
      <span class="pill muted">static demo</span>
      <button class="btn ghost sm" id="theme">${state.dark ? '☀ Light' : '☾ Dark'}</button>
      <button class="btn sm" id="reset">Reset</button>
    </div>
  </header>
  <div class="legend">
    <span class="legend-label">Three surfaces</span>
    <span class="legend-item"><i class="dot auth"></i>authority — what the agent may open</span>
    <span class="legend-item"><i class="dot expo"></i>exposure — what may leave the drive</span>
    <span class="legend-item"><i class="dot prov"></i>provenance — the receipt</span>
  </div>
  <main class="page">${viewFor(state.view)}</main>
  <p class="footnote">Static demonstration page for the WWW '26 demo paper. Every figure shown is from a real run of the system; interactions are scripted locally and need no network.</p>
  `
}

function navItem(key, label) {
  return `<button class="nav-item ${state.view === key ? 'active' : ''}" data-view="${key}">${label}</button>`
}

function viewFor(view) {
  if (view === 'market') return marketView()
  if (view === 'collab') return collabView()
  if (view === 'guard') return guardView()
  return studioView()
}

// ---------- studio ----------

function studioView() {
  return `
  ${tourBar()}
  <div class="page-head">
    <div>
      <h1 class="page-title">Seller studio</h1>
      <p class="page-sub">Describe the product you want in one sentence. The agent picks an aggregation template, reads only the folders you tick, and reduces the content on this machine before anything is listed.</p>
    </div>
  </div>
  <div class="grid studio">
    <div>
      ${intentCard()}
      ${treeCard()}
      ${buildCard()}
    </div>
    <div>
      ${state.built ? productCard() : emptyCard()}
    </div>
  </div>
  `
}

function tourBar() {
  const steps = ['Describe', 'Scope', 'Build', 'Publish']
  return `
  <div class="tour-bar">
    <span class="legend-label">Booth tour</span>
    ${steps.map((s, i) => `
      <span class="tour-step ${state.tour > i ? 'done' : state.tour === i ? 'active' : ''}">
        <span class="tour-num">${state.tour > i ? '✓' : i + 1}</span>${s}
      </span>`).join('<span class="muted">→</span>')}
  </div>`
}

function intentCard() {
  const it = state.interp
  return `
  <div class="card">
    <div class="card-head">
      <span class="card-title">1 · Describe the product</span>
      <span class="pill ${state.tour > 0 ? 'ok' : 'muted'}">authority</span>
    </div>
    <textarea class="intent" id="intent" maxlength="200"
      placeholder="e.g. turn my commute records into average duration per line">${esc(state.intent)}</textarea>
    <div class="chips">${EXAMPLES.map((e) => `<button class="chip" data-example="${esc(e)}">${esc(e)}</button>`).join('')}</div>
    ${state.interpBusy ? '<p class="typing">Agent is reading the sentence…</p>' : ''}
    ${it ? `
      <div class="interp fade-in">
        <div class="interp-head">
          <span class="interp-title">Understood as: ${esc(it.template.label)}</span>
          <span class="pill ${it.needsConfirm ? 'warn' : 'ok'}">confidence ${it.confidence}%</span>
        </div>
        <p class="interp-reason">${esc(it.reason)}</p>
        <p class="interp-fields">Will produce: ${it.template.fields.map((f) => `<span class="mono">${esc(f)}</span>`).join(' · ')}</p>
      </div>` : ''}
  </div>`
}

function treeCard() {
  const total = FOLDERS.reduce((n, g) => n + g.files.length, 0)
  return `
  <div class="card mt">
    <div class="card-head">
      <span class="card-title">2 · Tick what may be read</span>
      <span class="card-note">${state.selected.size} of ${total} files selected</span>
    </div>
    <div class="tree">
      ${FOLDERS.map((g) => `
        <div class="tree-group">
          <div class="tree-row group">
            <span class="tree-icon">▸</span>
            <span class="tree-name">${esc(g.name)}</span>
            <span class="tree-meta">${g.files.length} files</span>
          </div>
          <div class="tree-children">
            ${g.files.map((f) => `
              <div class="tree-row file ${f.sensitive ? 'excluded' : ''}">
                <input type="checkbox" data-file="${f.id}" ${state.selected.has(f.id) ? 'checked' : ''} ${f.sensitive ? 'disabled' : ''}>
                <span class="tree-name">${esc(f.name)}</span>
                <span class="tree-meta">${f.sensitive ? esc(f.sensitive) : esc(f.size)}</span>
              </div>`).join('')}
          </div>
        </div>`).join('')}
    </div>
    <p class="small muted mt">Only ticked subtrees are opened. Files whose content already carries an ID number, phone number or bank card are filtered here, before any model call, and the reason is written into the receipt.</p>
  </div>`
}

function buildCard() {
  if (!state.building && !state.events.length) {
    return `
    <div class="card mt">
      <div class="card-head">
        <span class="card-title">3 · Build the product</span>
        <span class="pill muted">exposure</span>
      </div>
      <div class="row">
        <button class="btn primary" id="build" ${state.selected.size ? '' : 'disabled'}>Start build</button>
        <span class="small muted">Parsing, scanning and aggregation all run on this machine.</span>
      </div>
    </div>`
  }

  return `
  <div class="card mt">
    <div class="card-head">
      <span class="card-title">3 · Build the product</span>
      <span class="pill ${state.built ? 'ok' : 'primary'}">${state.built ? 'done' : 'running'}</span>
    </div>
    <div class="bar"><div class="bar-fill" style="width:${state.progress}%"></div></div>
    <div class="bar-label">
      <span>${state.built ? 'Aggregate ready' : 'Reading, scanning, aggregating'}</span>
      <span>${state.progress}%</span>
    </div>
    <div class="events">
      ${state.events.map((e) => `
        <div class="event fade-in">
          <span class="event-tag ${e.tag}">${e.tag}</span>
          <span class="event-name">${esc(e.name)}</span>
          <span class="event-method">${esc(e.method)}</span>
        </div>`).join('')}
    </div>
    ${state.built ? '<p class="small muted mt">Product built. Nothing left this machine except group statistics.</p>' : ''}
  </div>`
}

function emptyCard() {
  return `
  <div class="card">
    <div class="card-head">
      <span class="card-title">Product preview</span>
      <span class="pill muted">provenance</span>
    </div>
    <div class="notice">Start a build to see the product card, the aggregate preview and the build receipt. The agent never sends file bytes to a model, so this panel is the only place the content appears.</div>
    <div class="stats mt">
      <div class="stat"><div class="stat-num">–</div><div class="stat-label">groups</div></div>
      <div class="stat"><div class="stat-num">–</div><div class="stat-label">files used</div></div>
      <div class="stat"><div class="stat-num">–</div><div class="stat-label">quality</div></div>
    </div>
  </div>
  <div class="card mt">
    <div class="card-head"><span class="card-title">What the agent will do</span></div>
    <table class="data">
      <thead><tr><th>step</th><th>where it runs</th></tr></thead>
      <tbody>
        <tr><td>select a template</td><td>agent, with a rule fallback</td></tr>
        <tr><td>open files</td><td>drive host, scope-checked per file</td></tr>
        <tr><td>scan for identifiers</td><td>drive host, 32-character overlap</td></tr>
        <tr><td>aggregate, drop sparse groups</td><td>drive host, k = 3</td></tr>
        <tr><td>describe the result</td><td>model, bounded extract only</td></tr>
        <tr><td>write the receipt</td><td>drive host</td></tr>
      </tbody>
    </table>
    <p class="small muted mt">The model appears once, at the description step, and receives a character-bounded extract rather than a file.</p>
  </div>
  <div class="card mt">
    <div class="card-head"><span class="card-title">What never leaves this machine</span></div>
    <div class="tag-row">
      <span class="tag warn">raw file bytes</span>
      <span class="tag warn">images and archives</span>
      <span class="tag warn">complete documents</span>
      <span class="tag warn">individual rows</span>
      <span class="tag warn">identifier-bearing files</span>
    </div>
    <p class="small muted mt">Everything above stays on the drive host. Only aggregates, the bounded extract and the receipt cross the boundary.</p>
  </div>`
}

function productCard() {
  const p = PRODUCT
  return `
  <div class="card fade-in">
    <div class="card-head">
      <div>
        <div class="card-title">${esc(p.title)}</div>
        <div class="card-note">${esc(p.template)} · coverage ${esc(p.coverage)}</div>
      </div>
      <span class="pill ok">draft</span>
    </div>

    <div class="tag-row">
      <span class="tag primary">${esc(p.template)}</span>
      ${p.tags.map((t) => `<span class="tag">${esc(t)}</span>`).join('')}
      <span class="tag violet">k ≥ ${p.k}</span>
    </div>

    <div class="stats">
      <div class="stat"><div class="stat-num">${p.groups}</div><div class="stat-label">groups kept</div></div>
      <div class="stat"><div class="stat-num">${p.files}</div><div class="stat-label">files used</div></div>
      <div class="stat"><div class="stat-num">${p.score}</div><div class="stat-label">quality score</div></div>
    </div>

    <div class="notice">Each group holds at least ${p.k} records; ${p.suppressed} group was dropped for being too small. Individual trips are never written into the product.</div>

    <div class="row mt">
      <button class="btn sm ${state.tab === 'preview' ? 'primary' : ''}" data-tab="preview">Preview</button>
      <button class="btn sm ${state.tab === 'receipt' ? 'primary' : ''}" data-tab="receipt">Receipt</button>
      <div class="spacer"></div>
      <button class="btn primary sm" id="publish">Publish to market</button>
    </div>

    <div class="mt">${state.tab === 'receipt' ? receiptBlock() : previewBlock()}</div>
    ${state.published ? '<div class="notice ok mt">Listed on the market. Buyers see the description and the receipt, never a filename.</div>' : ''}
  </div>`
}

function previewBlock() {
  return `
  <div class="table-scroll">
  <table class="data">
    <thead><tr>
      <th>route</th><th style="text-align:right">avg_duration</th><th style="text-align:right">p90</th>
      <th style="text-align:right">sample_count</th><th style="text-align:right">sources</th>
    </tr></thead>
    <tbody>
      ${PRODUCT.preview.map((r) => `<tr>
        <td>${esc(r.route)}</td>
        <td class="num">${r.avg_duration}</td>
        <td class="num">${r.p90_duration}</td>
        <td class="num">${r.sample_count}</td>
        <td class="num">${r.sources}</td>
      </tr>`).join('')}
    </tbody>
  </table>
  </div>
  <p class="small muted mt"><code>sources</code> records how many drives support the group; it is what lets a joint product grow without merging files.</p>`
}

function receiptBlock() {
  const r = PRODUCT.receipt
  return `
  <dl class="kv">
    <dt>product</dt><dd class="mono">${esc(r.product)}</dd>
    <dt>built</dt><dd class="mono">${esc(r.built)}</dd>
    <dt>model</dt><dd class="mono">${esc(r.model)}</dd>
    <dt>template</dt><dd class="mono">${esc(r.template)}</dd>
    <dt>k-anonymity</dt><dd class="mono">${r.kAnonymity} (kept ${r.groupsKept}, dropped ${r.groupsDropped})</dd>
  </dl>
  <table class="data mt">
    <thead><tr><th>source</th><th>sha256</th><th style="text-align:right">rows</th><th>status</th></tr></thead>
    <tbody>
      ${r.sources.map((s) => `<tr>
        <td>${esc(s.file)}</td>
        <td class="mono">${esc(s.sha256)}</td>
        <td class="num">${s.rows}</td>
        <td>${s.status === 'used' ? '<span class="tag ok">used</span>' : `<span class="tag warn">${esc(s.status)}</span>`}</td>
      </tr>`).join('')}
    </tbody>
  </table>
  <p class="small muted mt">The receipt binds the product to the source hashes it read and the files it refused, so a buyer can check a listing without access to the drive.</p>`
}

// ---------- market ----------

function marketView() {
  return `
  <div class="page-head">
    <div>
      <h1 class="page-title">Data market</h1>
      <p class="page-sub">A buyer posts a demand in natural language. The market parses it, filters listings by domain and keyword, scores the survivors and pushes matches with human-readable reasons.</p>
    </div>
  </div>
  <div class="grid two">
    <div class="card">
      <div class="card-head"><span class="card-title">Post a demand</span><span class="pill muted">buyer</span></div>
      <textarea class="intent" id="demand">${esc(state.demandText)}</textarea>
      <div class="row mt">
        <button class="btn primary" id="match">Match listings</button>
        <span class="small muted">budget ¥50 · coverage ≥ 3 months</span>
      </div>
      ${state.pushes ? '<p class="small muted mt">Parsed into domain <span class="mono">commute</span> with keywords <span class="mono">metro, bus, duration, line</span>.</p>' : ''}
      <h3 class="section-label mt-lg">How matching scores a listing</h3>
      <table class="data">
        <thead><tr><th>signal</th><th>weight</th><th>source</th></tr></thead>
        <tbody>
          <tr><td>keyword overlap</td><td>dominant</td><td>demand text against product tags</td></tr>
          <tr><td>temporal coverage</td><td>high</td><td>listing coverage against the demand</td></tr>
          <tr><td>quality score</td><td>medium</td><td>build receipt</td></tr>
          <tr><td>price against budget</td><td>medium</td><td>listing price</td></tr>
          <tr><td>group count</td><td>low</td><td>number of aggregated groups</td></tr>
        </tbody>
      </table>
      <p class="small muted mt">Matching runs on the market side and touches descriptions only, so a seller's drive and agent never take part in it.</p>
    </div>
    <div class="card">
      <div class="card-head"><span class="card-title">Pushed matches</span><span class="pill ${state.pushes ? 'ok' : 'muted'}">${state.pushes ? state.pushes.length + ' results' : 'none yet'}</span></div>
      ${state.pushes
        ? state.pushes.map((p) => `
          <div class="push fade-in">
            <div class="push-head">
              <span class="push-title">${esc(p.title)}</span>
              <span class="score">${p.score}</span>
            </div>
            <p class="push-reason">seller ${esc(p.seller)}</p>
            ${p.reasons.map(([k, v]) => `<p class="push-reason"><b>${esc(k)}:</b> ${esc(v)}</p>`).join('')}
          </div>`).join('')
        : '<div class="notice">Post a demand to see how matching works. Each push carries the reasons it was selected, not just a score.</div>'}
    </div>
  </div>
  <h2 class="section-label mt-lg">Listings on the market</h2>
  <div class="listings">
    ${LISTINGS.map((l) => `
      <div class="listing">
        <div class="row" style="justify-content:space-between">
          <span class="listing-title">${esc(l.title)}</span>
          <span class="tag ${l.kind === 'joint' ? 'violet' : 'primary'}">${l.kind === 'joint' ? 'joint' : 'solo'}</span>
        </div>
        <p class="listing-sum">${esc(l.summary)}</p>
        <div class="tag-row">${l.tags.map((t) => `<span class="tag">${esc(t)}</span>`).join('')}</div>
        <div class="listing-foot">
          <span>${esc(l.seller)} · ${l.groups} groups</span>
          <span>${esc(l.price)}</span>
        </div>
      </div>`).join('')}
  </div>
  <div class="card mt-lg">
    <div class="card-head"><span class="card-title">What a listing never contains</span></div>
    <div class="grid two">
      <div class="notice warn"><strong>Never shown:</strong>&nbsp;source filenames, individual rows, raw file bytes, the owner's folder tree.</div>
      <div class="notice ok"><strong>Shown:</strong>&nbsp;field schema, coverage, quality score, a masked four-row sample, and the build receipt.</div>
    </div>
  </div>`
}

// ---------- collaboration ----------

function collabView() {
  return `
  <div class="page-head">
    <div>
      <h1 class="page-title">Collaboration by session code</h1>
      <p class="page-sub">A short code admits collaborators who each keep their own scope. They exchange group statistics, never files, so the joint product grows with every member.</p>
    </div>
  </div>
  <div class="grid two">
    <div class="card">
      <div class="card-head"><span class="card-title">Initiator issues a code</span><span class="pill primary">1</span></div>
      <div class="credential">
        <div class="cred-row"><span class="cred-label">Session code</span><span class="cred-value">${COLLAB.code}</span><span class="pill muted">8 chars</span></div>
        <div class="cred-row"><span class="cred-label">Password</span><span class="cred-value">${COLLAB.password}</span><span class="pill warn">shown once</span></div>
      </div>
      <p class="small muted mt">The alphabet omits 0, O, 1 and I so the code can be dictated over the phone. The password is stored as a hash and returned by no endpoint after this point.</p>
    </div>
    <div class="card">
      <div class="card-head"><span class="card-title">Member redeems it</span><span class="pill primary">2</span></div>
      <div class="row">
        <input class="intent" id="code" placeholder="session code" value="${esc(state.code)}" style="min-height:0;padding:8px 10px;flex:1 1 140px">
        <input class="intent" id="password" placeholder="password" value="${esc(state.password)}" style="min-height:0;padding:8px 10px;flex:1 1 120px">
      </div>
      <div class="row mt">
        <button class="btn primary" id="reveal">Redeem and preview</button>
        <button class="btn" id="join" ${state.revealed ? '' : 'disabled'}>Join with my folders</button>
      </div>
      ${state.revealed ? `
        <div class="interp fade-in mt">
          <div class="interp-head">
            <span class="interp-title">${esc(COLLAB.title)}</span>
            <span class="pill primary">${esc(COLLAB.template)}</span>
          </div>
          <p class="interp-reason">Initiator ${esc(COLLAB.initiator)} · ${COLLAB.members.length} members so far</p>
          <p class="interp-fields">Will contain: ${COLLAB.fields.map((f) => `<span class="mono">${esc(f)}</span>`).join(' · ')}</p>
        </div>` : ''}
    </div>
  </div>
  <div class="card mt-lg">
    <div class="card-head">
      <span class="card-title">Joint product</span>
      <span class="pill ${state.joined ? 'ok' : 'muted'}">${state.joined ? 'grew after join' : 'before join'}</span>
    </div>
    <div class="grid two" style="align-items:start">
      <div>
        <h3 class="section-label">Members</h3>
        <table class="data">
          <thead><tr><th>member</th><th>role</th><th style="text-align:right">files</th><th style="text-align:right">groups</th></tr></thead>
          <tbody>
            ${COLLAB.members.map((m) => `<tr>
              <td>${esc(m.name)}</td>
              <td><span class="tag ${m.role === 'initiator' ? 'primary' : ''}">${m.role}</span></td>
              <td class="num">${m.files}</td>
              <td class="num">${state.joined || m.role === 'initiator' ? m.groups : '–'}</td>
            </tr>`).join('')}
          </tbody>
        </table>
        <p class="small muted mt">Merging is a union over group keys. Only the initiator holds the publish control, because attribution must stay with one accountable party.</p>
      </div>
      <div>
        <h3 class="section-label">Union of group statistics</h3>
        <table class="data">
          <thead><tr><th>route</th><th style="text-align:right">avg</th><th style="text-align:right">samples</th><th style="text-align:right">sources</th></tr></thead>
          <tbody>
            ${COLLAB.merged.map((r, i) => `<tr>
              <td>${esc(r.route)}</td>
              <td class="num">${r.avg_duration}</td>
              <td class="num">${state.joined ? r.sample_count : Math.round(r.sample_count * 0.67)}</td>
              <td class="num">${state.joined ? r.sources : 1}</td>
            </tr>`).join('')}
          </tbody>
        </table>
        <p class="small muted mt">No file moved between drives. What crossed the wire is the aggregate above, which is what <code>sources</code> makes auditable.</p>
      </div>
    </div>
  </div>`
}

// ---------- guard chain ----------

function guardView() {
  return `
  <div class="page-head">
    <div>
      <h1 class="page-title">Guard chain</h1>
      <p class="page-sub">Every file open passes four checks. Denials and exclusions are recorded rather than silent, which is what makes the coupling auditable.</p>
    </div>
  </div>
  <div class="grid two">
    ${GUARDS.map((g, i) => `
      <div class="card tight">
        <div class="row" style="justify-content:space-between">
          <span class="card-title">${i + 1}. ${esc(g.q)}</span>
          <span class="pill muted">check</span>
        </div>
        <div class="row mt">
          <span class="tag ok">yes → ${esc(g.yes)}</span>
          <span class="tag warn">no → ${esc(g.no)}</span>
        </div>
      </div>`).join('')}
  </div>
  <div class="card mt-lg">
    <div class="card-head"><span class="card-title">What each branch writes</span></div>
    <table class="data">
      <thead><tr><th>outcome</th><th>recorded in receipt</th><th>visible to</th></tr></thead>
      <tbody>
        <tr><td><span class="tag ok">released</span></td><td>source sha256, rows contributed</td><td>owner and buyer</td></tr>
        <tr><td><span class="tag warn">excluded</span></td><td>reason (ID number, bank card, …)</td><td>owner and buyer</td></tr>
        <tr><td><span class="tag">denied</span></td><td>not in scope / not owner of record</td><td>owner only</td></tr>
        <tr><td><span class="tag">trimmed</span></td><td>character count after the 1 200 cap</td><td>owner only</td></tr>
      </tbody>
    </table>
  </div>`
}

// ---------------------------------------------------------------------------
// behaviour
// ---------------------------------------------------------------------------

let intentTimer = null
let buildTimer = null
let eventTimer = null

function setIntent(text) {
  state.intent = text
  const el = $('#intent')
  if (el) el.value = text
  clearTimeout(intentTimer)
  if (text.trim().length < 4) {
    state.interp = null
    state.interpBusy = false
    rerender()
    return
  }
  state.interpBusy = true
  rerender()
  intentTimer = setTimeout(() => {
    state.interp = interpret(text)
    state.interpBusy = false
    if (state.tour === 0) state.tour = 1
    rerender()
  }, 420)
}

function startBuild() {
  if (state.building) return
  state.building = true
  state.built = false
  state.progress = 0
  state.events = []
  state.tour = 2
  rerender()

  let i = 0
  eventTimer = setInterval(() => {
    if (i >= BUILD_EVENTS.length) {
      clearInterval(eventTimer)
      eventTimer = null
      state.building = false
      state.built = true
      state.progress = 100
      state.tour = 3
      rerender()
      return
    }
    state.events.push(BUILD_EVENTS[i])
    state.progress = Math.round(((i + 1) / BUILD_EVENTS.length) * 100)
    i++
    rerender()
  }, 360)
}

function rerender() {
  const app = $('#app')
  const scroller = window.scrollY
  app.innerHTML = renderShell()
  bind()
  window.scrollTo(0, scroller)
  applyTheme()
}

function applyTheme() {
  document.documentElement.classList.toggle('dark', state.dark)
}

function reset() {
  clearTimeout(intentTimer)
  clearInterval(buildTimer)
  clearInterval(eventTimer)
  Object.assign(state, {
    intent: '', interp: null, interpBusy: false,
    selected: new Set(['f1', 'f2', 'f3']),
    building: false, built: false, progress: 0, events: [],
    tab: 'preview', pushes: null, code: '', password: '',
    revealed: false, joined: false, published: false, tour: 0,
  })
  rerender()
}

function bind() {
  document.querySelectorAll('[data-view]').forEach((b) => {
    b.onclick = () => { state.view = b.dataset.view; rerender() }
  })

  const themeBtn = $('#theme')
  if (themeBtn) themeBtn.onclick = () => { state.dark = !state.dark; rerender() }

  const resetBtn = $('#reset')
  if (resetBtn) resetBtn.onclick = reset

  const intent = $('#intent')
  if (intent) {
    intent.oninput = (e) => {
      state.intent = e.target.value
      clearTimeout(intentTimer)
      if (state.intent.trim().length < 4) {
        state.interp = null
        state.interpBusy = false
        return
      }
      state.interpBusy = true
      const t = setTimeout(() => {
        state.interp = interpret(state.intent)
        state.interpBusy = false
        if (state.tour === 0) state.tour = 1
        rerender()
      }, 420)
      intentTimer = t
    }
  }

  document.querySelectorAll('[data-example]').forEach((b) => {
    b.onclick = () => { state.intent = b.dataset.example; setIntent(b.dataset.example) }
  })

  document.querySelectorAll('[data-file]').forEach((cb) => {
    cb.onchange = () => {
      if (cb.checked) state.selected.add(cb.dataset.file)
      else state.selected.delete(cb.dataset.file)
      rerender()
    }
  })

  const build = $('#build')
  if (build) build.onclick = startBuild

  document.querySelectorAll('[data-tab]').forEach((b) => {
    b.onclick = () => { state.tab = b.dataset.tab; rerender() }
  })

  const publish = $('#publish')
  if (publish) publish.onclick = () => {
    state.published = true
    state.view = 'market'
    rerender()
  }

  const demand = $('#demand')
  if (demand) demand.oninput = (e) => { state.demandText = e.target.value }

  const match = $('#match')
  if (match) match.onclick = () => { state.pushes = PUSHES; rerender() }

  const code = $('#code')
  if (code) code.oninput = (e) => { state.code = e.target.value }

  const pwd = $('#password')
  if (pwd) pwd.oninput = (e) => { state.password = e.target.value }

  const reveal = $('#reveal')
  if (reveal) reveal.onclick = () => {
    if (!state.code.trim()) state.code = COLLAB.code
    if (!state.password.trim()) state.password = COLLAB.password
    state.revealed = true
    rerender()
  }

  const join = $('#join')
  if (join) join.onclick = () => { state.joined = true; rerender() }
}

state.published = false
state.revealed = false

// boot — default to light so screenshots and the paper match
state.dark = false
rerender()
