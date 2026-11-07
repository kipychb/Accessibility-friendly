// 語音轉接層：
// - 在臺北通 App（有 window.flutterObject）內：走 TownPass 原生語音 Handler（name: "voice"）
//   Android WebView 不支援瀏覽器 SpeechRecognition，所以 App 內一定要走這條。
// - 一般瀏覽器：沿用瀏覽器的 Web Speech API，方便在電腦上開發。
// 協定見 device-bridge/README.md。

const bridge = window.flutterObject
const BrowserRecognition = window.SpeechRecognition || window.webkitSpeechRecognition

let handlers = {}
let speakHandlers = {}
let recognition = null

function nativeSend(action, extra = {}) {
  bridge.postMessage(JSON.stringify({ name: 'voice', data: { action, ...extra } }))
}

if (bridge) {
  bridge.addEventListener('message', (event) => {
    let msg
    try { msg = typeof event.data === 'string' ? JSON.parse(event.data) : event.data } catch { return }
    if (!msg || msg.name !== 'voice' || !msg.data) return
    const { event: name, text, isFinal, message } = msg.data
    switch (name) {
      case 'listeningStarted': handlers.onStart?.(); break
      case 'speechResult': isFinal ? handlers.onFinal?.(text) : handlers.onPartial?.(text); break
      case 'listeningStopped': handlers.onEnd?.(); break
      case 'speechError':
        // 朗讀錯誤交給朗讀端；其餘視為辨識錯誤
        if (msg.data.code?.startsWith('tts') || msg.data.code === 'invalid_text') speakHandlers.onError?.(message)
        else handlers.onError?.(message)
        break
      // 例如第一次下載離線語音模型時的進度提示
      case 'voiceStatus': handlers.onStatus?.(message); break
      case 'speakingCompleted':
      case 'speakingStopped': speakHandlers.onEnd?.(); break
    }
  })
}

export const voiceMode = bridge ? 'townpass' : BrowserRecognition ? 'browser' : 'none'

export function startListening(h) {
  handlers = h
  if (bridge) { nativeSend('startListening'); return true }
  if (!BrowserRecognition) return false
  let finalText = ''
  recognition = new BrowserRecognition()
  recognition.lang = 'zh-TW'
  recognition.interimResults = true
  recognition.onstart = () => h.onStart?.()
  recognition.onresult = (event) => {
    const result = event.results[event.results.length - 1]
    if (result.isFinal) finalText = result[0].transcript
    else h.onPartial?.(result[0].transcript)
  }
  recognition.onerror = () => h.onError?.('語音辨識目前無法使用。請檢查麥克風權限，或改用文字輸入。')
  recognition.onend = () => {
    recognition = null
    h.onEnd?.()
    if (finalText.trim()) h.onFinal?.(finalText)
  }
  recognition.start()
  return true
}

export function stopListening() {
  if (bridge) nativeSend('stopListening')
  else recognition?.stop()
}

export function cancelListening() {
  handlers = {}
  if (bridge) nativeSend('stopListening')
  else if (recognition) { recognition.onend = null; recognition.abort(); recognition = null }
}

export function speak(text, h = {}) {
  speakHandlers = h
  if (bridge) { nativeSend('speak', { text }); return true }
  if (!window.speechSynthesis || !window.SpeechSynthesisUtterance) return false
  speechSynthesis.cancel()
  const utterance = new SpeechSynthesisUtterance(text)
  utterance.lang = 'zh-TW'
  utterance.onend = () => h.onEnd?.()
  utterance.onerror = () => h.onError?.('無法播放語音，請閱讀文字回覆。')
  speechSynthesis.speak(utterance)
  return true
}

export function stopSpeaking() {
  speakHandlers = {}
  if (bridge) nativeSend('stopSpeaking')
  else window.speechSynthesis?.cancel()
}
