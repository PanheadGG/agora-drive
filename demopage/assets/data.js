// Static demo dataset. Mirrors the seeded state of the real system so that
// every number shown here could have come from a live run.

export const TEMPLATES = [
  {
    key: 'commute_stats',
    label: 'Commute timeliness',
    fields: ['route', 'departure', 'arrival', 'duration', 'transfers'],
    cues: ['commute', 'metro', 'bus', 'line', 'duration', '通勤', '地铁', '公交', '耗时'],
  },
  {
    key: 'recipe_library',
    label: 'Home-recipe library',
    fields: ['dish', 'ingredients', 'steps'],
    cues: ['recipe', 'cook', 'dish', 'ingredient', '菜谱', '食材', '做法'],
  },
  {
    key: 'pet_feeding',
    label: 'Pet-feeding records',
    fields: ['pet', 'weight', 'deworm', 'food'],
    cues: ['pet', 'cat', 'dog', 'feeding', 'weight', '宠物', '猫', '狗', '喂养'],
  },
  {
    key: 'reading_notes',
    label: 'Reading notes',
    fields: ['book', 'notes', 'topics'],
    cues: ['reading', 'book', 'note', '读书', '笔记', '书'],
  },
]

export const EXAMPLES = [
  'turn my commute records into average duration per line',
  'turn my recipe notes into a searchable recipe library',
  'summarise my cat feeding table by weight and deworming',
  'aggregate my reading notes by book',
]

export const FOLDERS = [
  {
    id: 'f-commute',
    name: 'Commute 2026',
    files: [
      { id: 'f1', name: 'commute-q1.xlsx', size: '18.4 KB', method: 'streamed Excel', rows: 46 },
      { id: 'f2', name: 'commute-q2.xlsx', size: '21.7 KB', method: 'streamed Excel', rows: 52 },
      { id: 'f3', name: 'line-notes.docx', size: '9.2 KB', method: 'StAX', rows: 6 },
    ],
  },
  {
    id: 'f-recipe',
    name: 'Recipes',
    files: [
      { id: 'f4', name: 'family-dishes.pdf', size: '412 KB', method: 'PDF literal parser', rows: 34 },
      { id: 'f5', name: 'weeknight.docx', size: '11.8 KB', method: 'StAX', rows: 18 },
    ],
  },
  {
    id: 'f-pet',
    name: 'Cat log',
    files: [
      { id: 'f6', name: 'feeding.xlsx', size: '7.1 KB', method: 'streamed Excel', rows: 40 },
      { id: 'f7', name: 'vet-scan.png', size: '1.2 MB', method: 'on-device OCR', rows: 3 },
    ],
  },
  {
    id: 'f-sensitive',
    name: 'Contracts',
    files: [
      { id: 'f8', name: 'vendor-contract.docx', size: '88 KB', method: 'StAX', rows: 0, sensitive: 'ID number detected' },
      { id: 'f9', name: 'invoice-scan.pdf', size: '640 KB', method: 'PDF literal parser', rows: 0, sensitive: 'bank card detected' },
    ],
  },
]

// Interleaved per-file events used to animate the build.
export const BUILD_EVENTS = [
  { tag: 'ok', name: 'commute-q1.xlsx', method: 'streamed Excel' },
  { tag: 'ok', name: 'commute-q2.xlsx', method: 'streamed Excel' },
  { tag: 'ok', name: 'line-notes.docx', method: 'StAX' },
  { tag: 'warn', name: 'vendor-contract.docx', method: 'excluded: ID number' },
  { tag: 'warn', name: 'invoice-scan.pdf', method: 'excluded: bank card' },
  { tag: 'info', name: 'aggregate', method: 'k = 3, 3 groups kept, 1 dropped' },
]

export const PRODUCT = {
  title: 'Commute timeliness by line',
  template: 'Commute timeliness',
  templateKey: 'commute_stats',
  groups: 3,
  files: 3,
  rows: 104,
  score: 82,
  k: 3,
  suppressed: 1,
  coverage: '2026-01-06 – 2026-03-27',
  tags: ['commute', 'metro', 'timeliness'],
  preview: [
    { route: 'Metro Line 2', avg_duration: 44.3, p90_duration: 46, sample_count: 58, sources: 2 },
    { route: 'Metro Line 1', avg_duration: 50.2, p90_duration: 52, sample_count: 31, sources: 1 },
    { route: 'Bus 37', avg_duration: 61.7, p90_duration: 68, sample_count: 15, sources: 1 },
  ],
  receipt: {
    product: 'prod_7f3a91',
    built: '2026-03-28 14:02:11',
    model: 'deepseek-chat (temperature 0.2)',
    template: 'commute_stats',
    kAnonymity: 3,
    rowsRead: 104,
    groupsKept: 3,
    groupsDropped: 1,
    sources: [
      { file: 'commute-q1.xlsx', sha256: '9c1f4e7a…3b2d', rows: 46, status: 'used' },
      { file: 'commute-q2.xlsx', sha256: '4d8b02f5…91ac', rows: 52, status: 'used' },
      { file: 'line-notes.docx', sha256: 'e2a7c60b…77f4', rows: 6, status: 'used' },
      { file: 'vendor-contract.docx', sha256: '—', rows: 0, status: 'excluded: ID number' },
      { file: 'invoice-scan.pdf', sha256: '—', rows: 0, status: 'excluded: bank card' },
    ],
  },
}

export const LISTINGS = [
  {
    id: 'lst_a1',
    title: 'Commute timeliness by line',
    summary: 'Average and p90 duration per metro and bus line, aggregated over one quarter. Group-level statistics only; no individual trip is included.',
    seller: 'seller_a',
    price: '¥30.00',
    tags: ['commute', 'metro', 'timeliness'],
    groups: 3,
    kind: 'solo',
    quality: 82,
  },
  {
    id: 'lst_b2',
    title: 'Cross-city commute, joint product',
    summary: 'Two owners pooled their aggregates through a session code, so each line carries an additional field recording how many drives support it.',
    seller: 'seller_a + seller_b',
    price: '¥45.00',
    tags: ['commute', 'joint', 'two drives'],
    groups: 4,
    kind: 'joint',
    quality: 88,
  },
  {
    id: 'lst_c3',
    title: 'Weeknight recipe library',
    summary: 'Dish names with ingredient counts and step counts, extracted from PDF and DOCX recipe notes.',
    seller: 'seller_b',
    price: '¥18.00',
    tags: ['recipe', 'household'],
    groups: 12,
    kind: 'solo',
    quality: 74,
  },
]

export const DEMAND = {
  text: 'I need average metro and bus commute durations by line, at least one quarter of coverage.',
  parsed: {
    domain: 'commute',
    keywords: ['metro', 'bus', 'duration', 'line'],
    coverage: '≥ 3 months',
    budget: '¥50',
  },
}

export const PUSHES = [
  {
    title: 'Commute timeliness by line',
    seller: 'seller_a',
    score: 92,
    reasons: [
      ['Matched keywords', 'commute, metro, line, duration'],
      ['Coverage', '2026-01 – 2026-03, meets ≥ 3 months'],
      ['Price', '¥30 within ¥50 budget'],
    ],
  },
  {
    title: 'Cross-city commute, joint product',
    seller: 'seller_a + seller_b',
    score: 86,
    reasons: [
      ['Matched keywords', 'commute, metro'],
      ['Coverage', 'two drives, wider line coverage'],
      ['Note', 'fewer groups than the single-owner listing'],
    ],
  },
]

export const COLLAB = {
  code: 'XPRZ98NB',
  password: 'K7M2QF',
  title: 'Cross-city commute, joint product',
  template: 'Commute timeliness',
  fields: ['route', 'avg_duration', 'p90_duration', 'sample_count', 'sources'],
  initiator: 'seller_a',
  members: [
    { name: 'seller_a', role: 'initiator', files: 3, groups: 3 },
    { name: 'seller_b', role: 'member', files: 2, groups: 2 },
  ],
  merged: [
    { route: 'Metro Line 2', avg_duration: 46.2, p90_duration: 49, sample_count: 87, sources: 2 },
    { route: 'Metro Line 1', avg_duration: 50.2, p90_duration: 52, sample_count: 31, sources: 1 },
    { route: 'Bus 37', avg_duration: 61.7, p90_duration: 68, sample_count: 15, sources: 1 },
    { route: 'Bus 112', avg_duration: 55.4, p90_duration: 59, sample_count: 22, sources: 1 },
  ],
}

export const GUARDS = [
  { q: 'In ticked scope?', yes: 'Owner of record?', no: 'Deny: not in scope' },
  { q: 'Owner of record?', yes: 'PII scan clean?', no: 'Deny: other owner' },
  { q: 'PII scan clean?', yes: 'Within model quota?', no: 'Exclude: receipt reason' },
  { q: 'Within model quota?', yes: 'Release to agent', no: 'Trim to 1 200 chars' },
]
