import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
const svg = readFileSync('docs/www2026/figures/drawio/fig1-loop.svg', 'utf8')
const html = '<!doctype html><html><head><meta charset=utf-8><style>body{margin:0;background:#fff}' +
  'svg{width:1400px;height:auto;display:block}</style></head><body>' + svg + '</body></html>'
writeFileSync('docs/www2026/.zoom.html', html)
execFileSync('C:/Program Files/Google/Chrome/Application/chrome.exe', ['--headless=new','--disable-gpu','--hide-scrollbars','--no-first-run',
  '--window-size=1420,860','--virtual-time-budget=3000',
  '--screenshot=E:/Code/AgentCloudDisk/docs/www2026/.zoom.png',
  'file:///E:/Code/AgentCloudDisk/docs/www2026/.zoom.html'], { stdio: 'ignore' })
console.log('ok')
