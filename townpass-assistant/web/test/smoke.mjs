import assert from 'node:assert/strict'
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

let speechFixture
try {
  await call('Page.enable', {})
  speechFixture = await call('Page.addScriptToEvaluateOnNewDocument', { source: `
    Object.defineProperty(window, 'SpeechRecognition', { configurable: true, value: class {
      start() { this.onstart(); }
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
  await waitFor("location.href === " + JSON.stringify(pageUrl) + " && !!document.querySelector('textarea')")
  assert.ok(await evaluate("String(window.SpeechRecognition).includes('我要申請敬老卡')"), 'Speech fixture not loaded')
  assert.equal(await evaluate("document.documentElement.lang"), 'zh-Hant')
  assert.ok(await evaluate("document.querySelector('.voice').getBoundingClientRect().height >= 48"))

  await submit('人行道破損，輪椅很難通過')
  await waitFor("document.body.innerText.includes('請說出道路名稱')")
  await submit('民生東路三段')
  await waitFor("document.body.innerText.includes('已整理陳情草稿')")
  assert.ok(await evaluate("!!document.querySelector('a[href=\"https://hello.gov.taipei/Front/main\"]:not([target])')"))

  await waitFor("!document.querySelector('.voice').disabled")
  await evaluate("document.querySelector('.voice').click()")
  await waitFor("document.querySelector('.voice').getAttribute('aria-pressed') === 'true'")
  await evaluate("document.querySelector('.voice').click()")
  await waitFor("document.body.innerText.includes('可至臺北市各區公所社會課洽辦敬老卡')")
  console.log('Browser smoke passed: accessible controls, complaint follow-up, simulated zh-TW transcript')
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
