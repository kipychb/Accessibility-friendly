/*
 * TownPass 語音橋接（前端用）
 * 需搭配 TownPass App 的 VoiceMessageHandler（name: "voice"）。
 *
 * 用法：
 *   TownPassVoice.on('speechResult', ({ text, isFinal }) => { ... });
 *   TownPassVoice.startListening();
 *   TownPassVoice.speak('307 公車預計 5 分鐘後到站');
 *
 * 事件：listeningStarted, listeningStopped, speechResult {text,isFinal},
 *       speechError {code,message}, speakingStarted, speakingCompleted, speakingStopped
 *
 * 一般瀏覽器沒有 window.flutterObject，isAvailable() 會回傳 false，請改用文字輸入或模擬資料。
 */
(function () {
  const listeners = {};

  function emit(event, data) {
    (listeners[event] || []).forEach((cb) => {
      try { cb(data || {}); } catch (e) { console.error('[TownPassVoice]', e); }
    });
    (listeners['*'] || []).forEach((cb) => {
      try { cb(event, data || {}); } catch (e) { console.error('[TownPassVoice]', e); }
    });
  }

  function send(action, extra) {
    if (!window.flutterObject) {
      emit('speechError', { code: 'bridge_unavailable', message: '目前不在臺北通 App 內，無法使用語音' });
      return false;
    }
    window.flutterObject.postMessage(JSON.stringify({ name: 'voice', data: Object.assign({ action }, extra) }));
    return true;
  }

  function onMessage(event) {
    let msg;
    try { msg = typeof event.data === 'string' ? JSON.parse(event.data) : event.data; } catch (e) { return; }
    if (!msg || msg.name !== 'voice' || !msg.data || !msg.data.event) return;
    const { event: name, ...data } = msg.data;
    emit(name, data);
  }

  if (window.flutterObject) window.flutterObject.addEventListener('message', onMessage);

  window.TownPassVoice = {
    isAvailable: () => !!window.flutterObject,
    startListening: () => send('startListening'),
    stopListening: () => send('stopListening'),
    speak: (text) => send('speak', { text: String(text || '') }),
    stopSpeaking: () => send('stopSpeaking'),
    on(event, cb) { (listeners[event] = listeners[event] || []).push(cb); return () => this.off(event, cb); },
    off(event, cb) { listeners[event] = (listeners[event] || []).filter((f) => f !== cb); },
  };
})();
