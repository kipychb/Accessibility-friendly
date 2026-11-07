import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const port = process.env.CDP_PORT || '9222'
const pageUrl = process.env.ASSISTANT_URL || 'http://127.0.0.1:8080/'
const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json()
const target = targets.find(page => page.type === 'page' &&
  (!process.env.CDP_TARGET_URL || page.url === process.env.CDP_TARGET_URL))
assert.ok(target?.webSocketDebuggerUrl, 'A test browser or WebView page is required')
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true })
  socket.addEventListener('error', reject, { once: true })
})

let requestId = 0
function call(method, params) {
  return new Promise((resolve, reject) => {
    const id = ++requestId
    const timer = setTimeout(() => reject(new Error(method + ' timed out')), 10000)
    const onMessage = (event) => {
      const reply = JSON.parse(event.data)
      if (reply.id !== id) return
      clearTimeout(timer)
      socket.removeEventListener('message', onMessage)
      if (reply.error) reject(new Error(reply.error.message))
      else resolve(reply.result)
    }
    socket.addEventListener('message', onMessage)
    socket.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const result = await call('Runtime.evaluate', { expression, returnByValue: true })
  assert.ok(!result.exceptionDetails, result.exceptionDetails?.exception?.description)
  return result.result.value
}

async function waitFor(expression) {
  for (let attempt = 0; attempt < 40; attempt++) {
    if (await evaluate(expression)) return
    await delay(250)
  }
  assert.fail('Page did not reach the expected state: ' + expression + '\n' +
    await evaluate('document.body?.innerText.slice(-500)'))
}

async function submit(question) {
  await evaluate(`(() => {
    const field = document.querySelector('textarea');
    field.value = ${JSON.stringify(question)};
    field.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector('form').requestSubmit();
  })()`)
}

async function capture(name) {
  if (!process.env.SCREENSHOT_DIR) return
  const screenshot = await call('Page.captureScreenshot', { format: 'png' })
  await writeFile(join(process.env.SCREENSHOT_DIR, name + '.png'), Buffer.from(screenshot.data, 'base64'))
}

let speechFixture
try {
  await call('Page.enable', {})
  if (process.env.SCREENSHOT_DIR) {
    await call('Emulation.setDeviceMetricsOverride', {
      width: 390, height: 844, deviceScaleFactor: 1, mobile: true,
    })
  }
  speechFixture = await call('Page.addScriptToEvaluateOnNewDocument', { source: `
    Object.defineProperty(window, 'SpeechRecognition', { configurable: true, value: class {
      start() {
        this.onstart();
        const result = [{ transcript: '我想要申訴，家附近的人行道破損，輪椅很難通過。' }];
        result.isFinal = false;
        this.onresult({ results: [result] });
      }
      stop() {
        const result = [{ transcript: '我要申請敬老卡' }];
        result.isFinal = true;
        this.onresult({ results: [result] });
        this.onend();
      }
      abort() {}
    } });
  ` })
  const navigation = await call('Page.navigate', { url: pageUrl })
  assert.ok(!navigation.errorText, navigation.errorText)
  await waitFor("location.href === " + JSON.stringify(pageUrl) + " && !!document.querySelector('[role=dialog]')")
  assert.ok(await evaluate("String(window.SpeechRecognition).includes('我要申請敬老卡')"), 'Speech fixture not loaded')
  assert.equal(await evaluate("document.documentElement.lang"), 'zh-Hant')
  await capture('01-welcome')
  await evaluate("document.querySelector('.welcome .secondary').click()")
  await waitFor("!document.querySelector('[role=dialog]')")
  await evaluate("document.querySelector('.settings-panel button').click()")
  await evaluate("document.querySelector('.welcome .primary').click()")
  await waitFor("!!document.querySelector('.voice')")
  assert.ok(await evaluate("(() => { const r = document.querySelector('.voice').getBoundingClientRect(); return r.width >= 142 && Math.abs(r.width - r.height) < 1 })()"))
  await capture('02-home')

  await evaluate("document.querySelector('.voice').click()")
  await waitFor("document.querySelector('.record h2')?.textContent === '正在聆聽…'")
  assert.ok(await evaluate("document.querySelector('textarea').value.includes('人行道破損')"))
  await capture('03-recording')
  await evaluate("document.querySelector('.stop').click()")
  await waitFor("document.body.innerText.includes('可至臺北市各區公所社會課洽辦敬老卡')")

  await evaluate("document.querySelector('.continue .text-button').click()")
  await evaluate("document.querySelector('.voice-panel .text-button').click()")
  await submit('我想要申訴，家附近的人行道破損，輪椅很難通過。')
  await waitFor("document.body.innerText.includes('問題類型：道路設施')")
  await capture('04-reply')
  await evaluate("document.querySelector('.continue .text-button').click()")
  await submit('中山區民生東路三段，靠近公園的路口。')
  await waitFor("document.body.innerText.includes('我已整理好陳情內容')")
  assert.ok(await evaluate("!!document.querySelector('a[href=\"https://hello.gov.taipei/Front/main\"]:not([target])')"))
  await capture('05-next-step')
  await evaluate("document.querySelector('.answer .secondary').click()")
  await waitFor("document.querySelector('textarea')?.value === '中山區民生東路三段，靠近公園的路口。'")
  await submit('民生東路三段1號')
  await waitFor("document.querySelector('.user-bubble p')?.textContent === '民生東路三段1號'")
  await evaluate("document.querySelector('.tabs button:nth-child(2)').click()")
  await waitFor("document.querySelectorAll('.settings-panel a').length === 6")
  console.log('Browser smoke passed: five screens, round mic, simulated speech, draft editing, services')
} finally {
  try {
    if (speechFixture) {
      await call('Page.removeScriptToEvaluateOnNewDocument', { identifier: speechFixture.identifier })
    }
    if (process.env.CDP_RESTORE_TARGET === '1') {
      await call('Page.navigate', { url: target.url })
    }
  } finally {
    socket.close()
  }
}
