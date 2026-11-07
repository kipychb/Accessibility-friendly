<script setup>
import { nextTick, onUnmounted, ref } from 'vue'

const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition
const text = ref('')
const turns = ref([])
const context = ref(null)
const busy = ref(false)
const listening = ref(false)
const notice = ref('')
const input = ref(null)
let recognition
let finalized = false

function toggleVoice() {
  if (listening.value) {
    recognition.stop()
    return
  }
  if (!Recognition) {
    notice.value = '這個環境無法使用語音辨識。可以輸入文字或使用裝置鍵盤的語音輸入。'
    input.value?.focus()
    return
  }
  finalized = false
  notice.value = ''
  recognition = new Recognition()
  recognition.lang = 'zh-TW'
  recognition.interimResults = true
  recognition.onstart = () => { listening.value = true }
  recognition.onresult = (event) => {
    const result = event.results[event.results.length - 1]
    text.value = result[0].transcript
    finalized = result.isFinal
  }
  recognition.onerror = () => {
    finalized = false
    notice.value = '語音辨識目前無法使用。請檢查麥克風權限，或改用文字輸入。'
  }
  recognition.onend = () => {
    listening.value = false
    if (finalized && text.value.trim()) submit(text.value)
  }
  try {
    recognition.start()
  } catch {
    notice.value = '無法啟動麥克風。你仍可輸入文字。'
  }
}

async function submit(question, selectedContext = context.value) {
  if (busy.value || !question.trim()) return
  busy.value = true
  notice.value = ''
  try {
    const response = await fetch('/api/v1/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: question, context: selectedContext }),
    })
    if (!response.ok) throw new Error('Query failed')
    const reply = await response.json()
    turns.value.push({ question, reply })
    context.value = reply.context ?? null
    text.value = ''
  } catch {
    notice.value = '暫時無法查詢，請稍後再試。'
  } finally {
    busy.value = false
  }
}

function play(speech) {
  if (!window.speechSynthesis || !window.SpeechSynthesisUtterance) {
    notice.value = '這個環境無法播放語音，仍可閱讀文字回覆。'
    return
  }
  speechSynthesis.cancel()
  const utterance = new SpeechSynthesisUtterance(speech)
  utterance.lang = 'zh-TW'
  speechSynthesis.speak(utterance)
}

async function reset() {
  context.value = null
  turns.value = []
  text.value = ''
  notice.value = ''
  await nextTick()
  input.value?.focus()
}

onUnmounted(() => {
  if (recognition) {
    recognition.onend = null
    recognition.abort()
  }
  window.speechSynthesis?.cancel()
})
</script>

<template>
  <main>
    <h1>台北通無障礙語音助理</h1>
    <p>點一下開始說話，不用一直按住。也可以輸入文字。</p>
    <p v-if="Recognition">語音辨識由瀏覽器提供，請避免說出證號或電話。</p>
    <button type="button" class="voice" :disabled="busy" :aria-pressed="listening" @click="toggleVoice">
      {{ listening ? '停止聆聽' : '開始說話' }}
    </button>
    <form @submit.prevent="submit(text)">
      <label for="question">你的問題</label>
      <textarea id="question" ref="input" v-model="text" maxlength="400" rows="2"
        placeholder="例如，一號公車華江站往松仁路幾點來"></textarea>
      <div class="actions">
        <button :disabled="busy || listening || !text.trim()">送出查詢</button>
        <button type="button" :disabled="busy || listening" @click="reset">新對話</button>
      </div>
    </form>
    <p role="status">{{ notice || (busy ? '正在查詢' : listening ? '正在聆聽' : '') }}</p>
    <section aria-label="對話" aria-live="polite">
      <article v-for="({ question, reply }, index) in turns" :key="index">
        <p><strong>你</strong> {{ question }}</p>
        <p><strong>助理</strong> {{ reply.speech }}</p>
        <p v-if="reply.updatedAt">資料時間 {{ new Date(reply.updatedAt).toLocaleString('zh-TW', { timeZone: 'Asia/Taipei' }) }}</p>
        <template v-if="reply.draft">
          <p>陳情描述 {{ reply.draft.description }}</p>
          <p>地點 {{ reply.draft.location }}</p>
        </template>
        <div class="actions">
          <button type="button" @click="play(reply.speech)">播放回覆</button>
          <a v-if="reply.service" :href="reply.service.officialUrl">
            前往{{ reply.service.title }}
          </a>
        </div>
        <div v-if="index === turns.length - 1 && reply.choices" class="actions">
          <button v-for="choice in reply.choices" :key="choice.label" type="button"
            :disabled="busy || listening" @click="submit('確認站牌', choice.context)">{{ choice.label }}</button>
        </div>
      </article>
    </section>
  </main>
</template>

<style>
:root { font-family: system-ui, sans-serif; color: #12353a; background: #f2f9f9; font-size: 18px; }
main { max-width: 42rem; margin: auto; padding: 1rem; }
h1 { font-size: 1.65rem; }
button, textarea, a { font: inherit; }
button, a { min-height: 48px; padding: .6rem .85rem; border-radius: .6rem; box-sizing: border-box; }
button { border: 1px solid #106268; color: #12353a; background: white; cursor: pointer; }
button:disabled { opacity: .55; cursor: default; }
a { display: inline-flex; align-items: center; color: #104e55; }
button:focus-visible, textarea:focus-visible, a:focus-visible { outline: 3px solid #9c4b07; outline-offset: 2px; }
.voice { width: 100%; min-height: 72px; background: #106268; color: white; font-weight: 700; }
form, article { margin-block: 1rem; }
label, textarea { display: block; width: 100%; }
textarea { box-sizing: border-box; padding: .65rem; border: 1px solid #537377; border-radius: .4rem; }
.actions { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: .65rem; }
article { background: white; border-radius: .7rem; border: 1px solid #a8c3c5; padding: .65rem 1rem; overflow-wrap: anywhere; }
</style>
