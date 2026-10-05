import { JSDOM } from 'jsdom';
import fs from 'fs';
import path from 'path';
const dom = new JSDOM('<!doctype html><html><body></body></html>', { pretendToBeVisual: true });
globalThis.window = dom.window; globalThis.document = dom.window.document;
try { globalThis.navigator = dom.window.navigator; } catch {}
globalThis.DOMParser = dom.window.DOMParser; globalThis.Element = dom.window.Element;
const { default: mermaid } = await import('mermaid');
mermaid.initialize({ startOnLoad: false });
const root = process.argv[2];
const files = ['README.md', 'docs/PLAN.md', ...fs.readdirSync(path.join(root, 'docs/design')).filter(f => f.endsWith('.md')).map(f => 'docs/design/' + f)];
let bad = 0, total = 0;
for (const f of files) {
  const lines = fs.readFileSync(path.join(root, f), 'utf8').split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim() === '```mermaid') {
      const start = i + 1; const buf = [];
      i++;
      while (i < lines.length && lines[i].trim() !== '```') { buf.push(lines[i]); i++; }
      total++;
      try { await mermaid.parse(buf.join('\n')); }
      catch (e) { bad++; console.log(`BAD ${f}:${start}: ${String(e.message || e).split('\n').slice(0, 4).join(' | ')}`); }
    }
  }
}
console.log(`${bad} invalid of ${total} diagrams`);
