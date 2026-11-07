// 自然問法測試：每句送到 /api/v1/query，檢查是否對應到預期的服務 ID。
// 用法：node qa/run-natural.mjs [http://127.0.0.1:8080]
import { readFile } from 'node:fs/promises'

const base = process.argv[2] ?? 'http://127.0.0.1:8080'
const cases = JSON.parse(await readFile(new URL('./natural-questions.json', import.meta.url), 'utf8'))
let pass = 0
const failures = []
for (const { id, q } of cases) {
  const res = await fetch(`${base}/api/v1/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: q }),
  })
  const reply = await res.json()
  const got = reply.service?.id ?? reply.type
  if (got === id) pass++
  else failures.push({ id, q, got })
}
console.log(`PASS ${pass}/${cases.length}`)
for (const f of failures) console.log(`FAIL  expected ${f.id.padEnd(22)} got ${String(f.got).padEnd(22)} ${f.q}`)
process.exitCode = failures.length ? 1 : 0
