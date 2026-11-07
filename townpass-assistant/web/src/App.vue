<script setup>
import { computed, nextTick, onUnmounted, ref } from 'vue'
import Icon from './Icon.vue'
import { cancelListening, speak, startListening, stopListening, stopSpeaking, voiceMode } from './voice.js'

const text = ref('')
const turns = ref([])
const context = ref(null)
const busy = ref(false)
const listening = ref(false)
const notice = ref('')
const input = ref(null)
const screen = ref('home')
const enabled = ref(false)
const onboarding = ref(true)
const services = ref([])
const latest = computed(() => turns.value.at(-1))
const examples = ['我要申訴道路問題', '我要申請敬老卡', '附近有無障礙設施嗎？']

function enable() {
  enabled.value = true
  onboarding.value = false
  screen.value = 'home'
}

function cancelRecording() {
  if (listening.value) cancelListening()
  listening.value = false
}

async function navigate(page) {
  cancelRecording()
  screen.value = page
  notice.value = ''
  if (page === 'services' && !services.value.length) {
    try {
      const response = await fetch('/api/v1/services')
      if (!response.ok) throw new Error('Service list unavailable')
      services.value = await response.json()
    } catch {
      notice.value = '暫時無法載入服務清單。'
    }
  }
}

async function compose(value = '') {
  screen.value = 'record'
  text.value = value
  await nextTick()
  input.value?.focus()
}

function editDraft(draft) {
  context.value = { draft: draft.description }
  compose(draft.location)
}

function askExample(question) {
  context.value = null
  submit(question)
}

function stopVoice() {
  if (listening.value) stopListening()
  else if (text.value.trim()) submit(text.value)
}

function toggleVoice() {
  if (listening.value || busy.value) return
  stopSpeaking()
  screen.value = 'record'
  text.value = ''
  notice.value = ''
  if (voiceMode === 'none') {
    notice.value = '這個環境無法使用語音辨識。可以輸入文字或使用裝置鍵盤的語音輸入。'
    nextTick(() => input.value?.focus())
    return
  }
  listening.value = true
  try {
    startListening({
      onStart: () => { listening.value = true },
      onPartial: (partial) => { text.value = partial },
      // 只有 final 且非空才送出查詢，同一句只送一次
      onFinal: (finalText) => {
        listening.value = false
        text.value = finalText
        if (finalText.trim()) submit(finalText)
      },
      onError: (message) => {
        listening.value = false
        notice.value = message || '語音辨識目前無法使用。請檢查麥克風權限，或改用文字輸入。'
        nextTick(() => input.value?.focus())
      },
      onEnd: () => { listening.value = false },
    })
  } catch {
    listening.value = false
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
    screen.value = 'reply'
    context.value = reply.context ?? null
    text.value = ''
    // 先顯示完整字幕，再自動朗讀；朗讀失敗不清除字幕
    await nextTick()
    if (reply.speech) play(reply.speech)
  } catch {
    notice.value = '暫時無法查詢，請稍後再試。'
  } finally {
    busy.value = false
  }
}

function play(speech) {
  const ok = speak(speech, { onError: (message) => { notice.value = message || '無法播放語音，請閱讀文字回覆。' } })
  if (!ok) notice.value = '這個環境無法播放語音，仍可閱讀文字回覆。'
}

function reset() {
  context.value = null
  turns.value = []
  text.value = ''
  notice.value = ''
  cancelRecording()
  screen.value = 'home'
}

onUnmounted(() => {
  cancelRecording()
  stopSpeaking()
})
</script>



<template>
  <div class="phone">
    <header class="app-bar" :class="{ quiet: onboarding || !enabled }">
      <template v-if="enabled && !onboarding">
        <button class="icon-button" aria-label="返回首頁" @click="navigate('home')"><Icon name="back" /></button>
        <h1>台北通 語音助理</h1>
        <button class="icon-button" aria-label="設定" @click="navigate('settings')"><Icon name="settings" /></button>
      </template>
      <h1 v-else>首頁</h1>
    </header>

    <main class="content" :inert="onboarding">
      <section v-if="!enabled" class="settings-panel" :class="{ 'behind-welcome': onboarding }">
        <button class="primary" @click="onboarding = true">啟用語音助理模式</button>
      </section>

      <section v-else-if="screen === 'home'" class="home">
        <div class="greeting">
          <svg class="waves" viewBox="0 0 390 84" preserveAspectRatio="none" aria-hidden="true">
            <path fill="#e0f4f4" d="M0 57 Q52 12 113 48 T239 63 Q278 60 326 32 Q360 14 390 29 V84 H0Z"/>
            <path fill="#d5eff0" opacity=".72" d="M0 80 Q54 47 80 61 T185 71 Q230 88 302 72 T390 62 V84H0Z"/>
          </svg>
          <span class="greeting-icon"><Icon name="greeting" /></span>
          <h2>陳小姐，早安</h2>
        </div>
        <div class="voice-panel">
          <button class="voice" type="button" aria-label="開始說話" :disabled="busy" @click="toggleVoice">
            <Icon name="mic" />
          </button>
          <h2>點擊說話</h2>
          <p>或直接說出你的需求</p>
          <div class="examples">
            <h3>你可以這樣說</h3>
            <button v-for="question in examples" :key="question" class="chip" :disabled="busy"
              @click="askExample(question)">{{ question }}</button>
          </div>
          <button class="text-button" @click="compose()">改用文字輸入</button>
        </div>
      </section>

      <section v-else-if="screen === 'record'" class="record">
        <div class="listen-badge"><Icon name="wave" /></div>
        <h2>{{ listening ? '正在聆聽…' : '輸入你的需求' }}</h2>
        <form class="transcript" @submit.prevent="stopVoice">
          <label for="question">語音輸入（即時轉文字）</label>
          <textarea id="question" ref="input" v-model="text" maxlength="400" rows="3"
            placeholder="說出的內容會顯示在這裡，也可以直接輸入。"></textarea>
          <button class="stop" type="submit" :disabled="busy || (!listening && !text.trim())"
            :aria-label="listening ? '停止聆聽' : '送出查詢'"><Icon :name="listening ? 'stop' : 'check'" /></button>
          <span class="stop-label">{{ listening ? '點擊停止' : '送出查詢' }}</span>
        </form>
      </section>

      <section v-else-if="screen === 'reply' && latest" class="conversation" aria-label="對話" aria-live="polite">
        <article class="bubble user-bubble">
          <header><span class="avatar"><Icon name="person" /></span><strong>你（語音轉文字）</strong></header>
          <p>{{ latest.question }}</p>
        </article>
        <article class="bubble assistant-bubble">
          <header><span class="avatar bot"><Icon name="robot" /></span><strong>台北通 助理</strong></header>
          <div class="answer">
            <template v-if="latest.reply.draft">
              <p>好的，我已整理好陳情內容。</p>
              <p>是否要前往<span class="accent">臺北市</span> 1999 陳情系統送出？</p>
              <details><summary>確認陳情內容</summary>
                <p>{{ latest.reply.draft.description }}</p><p>{{ latest.reply.draft.location }}</p>
              </details>
              <a class="primary" :href="latest.reply.service.officialUrl"><Icon name="external" />前往 1999 陳情</a>
              <button class="secondary" @click="editDraft(latest.reply.draft)">修改內容</button>
            </template>
            <template v-else-if="latest.reply.type === 'CLARIFY' && latest.reply.context?.draft">
              <p>我可以協助您向市府提出陳情。請確認以下資訊：</p>
              <ul><li>問題類型：道路設施</li><li>地點：請問是哪一條路？</li><li>其他補充：例如破損範圍或影響情況。</li></ul>
            </template>
            <template v-else>
              <p>{{ latest.reply.speech }}</p>
              <p v-if="latest.reply.updatedAt" class="muted">資料時間 {{ new Date(latest.reply.updatedAt).toLocaleString('zh-TW', { timeZone: 'Asia/Taipei' }) }}</p>
              <a v-if="latest.reply.service && latest.reply.type !== 'CLARIFY'" class="primary"
                :href="latest.reply.service.officialUrl"><Icon name="external" />前往{{ latest.reply.service.title }}</a>
            </template>
            <button v-for="choice in latest.reply.choices" :key="choice.label" class="secondary"
              :disabled="busy" @click="submit('確認站牌', choice.context)">{{ choice.label }}</button>
          </div>
          <button class="play" @click="play(latest.reply.speech)"><Icon name="sound" />播放回覆</button>
        </article>
        <div class="continue">
          <button v-if="latest.reply.type === 'CLARIFY'" class="secondary" @click="toggleVoice"><Icon name="mic" />繼續說話</button>
          <button v-if="latest.reply.type === 'CLARIFY'" class="text-button" @click="compose()">輸入補充內容</button>
          <button v-else class="text-button" @click="reset">開始新對話</button>
        </div>
      </section>

      <section v-else-if="screen === 'services'" class="settings-panel">
        <h2>服務</h2>
        <a v-for="service in services" :key="service.id" class="secondary" :href="service.officialUrl">
          <Icon name="external" />{{ service.title }}
        </a>
      </section>

      <section v-else class="settings-panel">
        <h2>{{ screen === 'settings' ? '設定' : '我的' }}</h2>
        <button class="secondary" @click="reset">開始新對話</button>
        <button class="secondary" @click="enabled = false; reset()">關閉語音助理模式</button>
      </section>
      <p v-if="notice || busy" class="status" role="status">{{ notice || '正在查詢…' }}</p>
    </main>

    <nav v-if="enabled" class="tabs" aria-label="主要導覽" :inert="onboarding">
      <button v-for="tab in [{ id: 'home', icon: 'home', label: '首頁' }, { id: 'services', icon: 'grid', label: '服務' }, { id: 'profile', icon: 'person', label: '我的' }]"
        :key="tab.id" :class="{ active: tab.id === screen || tab.id === 'home' && ['record', 'reply'].includes(screen) }"
        @click="navigate(tab.id)"><Icon :name="tab.icon" />{{ tab.label }}</button>
    </nav>

    <div v-if="onboarding" class="scrim">
      <section class="welcome" role="dialog" aria-modal="true" aria-labelledby="welcome-title">
        <div class="welcome-icon"><Icon name="wave" /></div>
        <h2 id="welcome-title">啟用<br>「語音助理模式」嗎？</h2>
        <ul>
          <li v-for="item in ['用說的就能查詢與辦理', '同步顯示語音文字', '大字體、簡單操作']" :key="item">
            <Icon name="check" />{{ item }}
          </li>
        </ul>
        <button class="primary" autofocus @click="enable">開始使用</button>
        <button class="secondary" @click="onboarding = false">先不要</button>
        <p>可隨時在設定中開啟或關閉</p>
      </section>
    </div>
  </div>
</template>

<style src="./assistant.css"></style>
