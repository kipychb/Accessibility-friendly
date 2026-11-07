import 'dart:async';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:record/record.dart';
import 'package:speech_to_text/speech_recognition_error.dart';
import 'package:speech_to_text/speech_to_text.dart';
import 'package:town_pass/util/web_message_handler/tp_llm_message_handler.dart';
import 'package:town_pass/util/web_message_handler/tp_web_message_handler.dart';
import 'package:whisper_ggml/whisper_ggml.dart';

/// 語音橋接：讓微服務網頁透過 flutterObject 使用原生中文語音辨識（STT）與朗讀（TTS）。
///
/// 網頁 → App：`{"name":"voice","data":{"action":"startListening"}}`
///   action：startListening | stopListening | speak（需帶 text）| stopSpeaking
///
/// App → 網頁：`{"name":"voice","data":{"event":"...", ...}}`
///   event：listeningStarted | listeningStopped | speechResult(text,isFinal)
///          | speechError(code,message) | speakingStarted | speakingCompleted | speakingStopped
///          | voiceStatus(message)（例如首次下載模型，前端可忽略）
///
/// 辨識（混合）：
/// - 有網路：Android 系統語音服務（Google），經 speech_to_text 套件（BSD-3）。
/// - 離線或無系統語音服務：Whisper（OpenAI，MIT）+ whisper.cpp（MIT），經 whisper_ggml 套件在裝置上執行。
///   模型首次使用時下載（ggml-base，約 148 MB），之後不需網路。
/// 本 Handler 不使用 LLM。
class VoiceMessageHandler extends TPWebMessageHandler {
  static const int maxSpeakLength = 1000;

  @override
  String get name => 'voice';

  @override
  Future<void> handle({
    required Object? message,
    required WebUri? sourceOrigin,
    required bool isMainFrame,
    required Function(WebMessage replyWebMessage)? onReply,
  }) async {
    final bridge = VoiceBridge.instance;
    // 每次收到命令就更新回傳通道，事件一律送給最近一次發出命令的頁面。
    bridge.emitter = (data) => onReply?.call(replyWebMessage(data: data));

    if (!isMainFrame) {
      bridge.emitError('not_main_frame', '只接受主頁面的語音命令');
      return;
    }
    if (message is! Map) {
      bridge.emitError('invalid_command', '語音命令格式錯誤');
      return;
    }

    switch (message['action']) {
      case 'startListening':
        // offline: true → 離線模式，一律用裝置上的 Whisper，不使用 Google 語音服務
        await bridge.startListening(forceOffline: message['offline'] == true);
      case 'stopListening':
        await bridge.stopListening();
      case 'speak':
        await bridge.speak(message['text']);
      case 'stopSpeaking':
        await bridge.stopSpeaking();
      default:
        bridge.emitError('invalid_command', '不支援的語音命令');
    }
  }
}

class VoiceBridge {
  VoiceBridge._();

  static final VoiceBridge instance = VoiceBridge._();

  /// 使用的 Whisper 模型（多語言 base）。
  static const WhisperModel model = WhisperModel.base;

  /// 引導 Whisper 輸出繁體中文，並偏向市政服務常用詞。
  /// 注意：Whisper 在安靜時可能把提示詞原樣吐出，所以結果會再過濾（見 [_isPromptEcho]）。
  static const String _initialPrompt = '公車、站牌、敬老卡、陳情、無障礙。';

  void Function(Map<String, Object?> data)? emitter;

  final FlutterTts _tts = FlutterTts();
  final AudioRecorder _recorder = AudioRecorder();
  final WhisperController _whisper = WhisperController();
  bool _ttsReady = false;

  // 線上引擎：Android 系統語音服務（Google），有網路時使用
  final SpeechToText _speech = SpeechToText();
  bool _speechReady = false;
  bool _googleActive = false;
  bool _googleAnnounced = false;
  bool _googleFinalSent = true;
  String _googleText = '';

  // 離線引擎（Whisper）本次收音的狀態
  bool _starting = false;
  bool _listening = false;
  bool _cancelled = false;
  String? _audioPath;
  StreamSubscription<Amplitude>? _amplitudeSub;
  Timer? _maxTimer;
  DateTime _startedAt = DateTime.now();
  DateTime? _lastVoiceAt;
  DateTime? _quietSince;
  double? _noiseFloor;
  double _speechPeak = -160;

  void _emit(String event, [Map<String, Object?> extra = const {}]) {
    debugPrint('VOICE_BRIDGE: $event $extra');
    emitter?.call({'event': event, ...extra});
  }

  void emitError(String code, String message) => _emit('speechError', {'code': code, 'message': message});

  /// 確保模型檔在裝置上；沒有就下載（只有第一次需要網路）。
  Future<String?> _ensureModel() async {
    final path = await _whisper.getPath(model);
    if (File(path).existsSync() && File(path).lengthSync() > 1000000) return path;
    _emit('voiceStatus', {'message': '第一次使用，正在下載離線語音模型（約 150 MB）…'});
    try {
      final downloaded = await _whisper.downloadModel(model);
      if (File(downloaded).lengthSync() > 1000000) return downloaded;
      File(downloaded).deleteSync();
    } catch (e) {
      debugPrint('VOICE_BRIDGE: model download failed $e');
    }
    return null;
  }

  /// 有網路且裝置有系統語音服務時用 Google（快、準）；否則用 Whisper 離線辨識。
  Future<void> startListening({bool forceOffline = false}) async {
    if (_starting || _listening || _googleActive) return;
    _starting = true;
    try {
      await _tts.stop();
      // 模擬器／低階手機 CPU 有限：說話時先暫停 Gemma 背景推論，避免語音辨識被拖慢
      LlmBridge.instance.yieldToVoice();
      if (!await _recorder.hasPermission()) {
        emitError('permission_denied', '尚未允許麥克風權限，請到設定開啟');
        return;
      }
      if (!forceOffline && await _isOnline() && await _ensureGoogle()) {
        await _startGoogle();
      } else {
        _emit('voiceStatus', {
          'message': forceOffline ? '離線模式：使用裝置上的語音辨識，說完需稍候幾秒' : '目前離線，使用裝置上的語音辨識，說完需稍候幾秒',
          'engine': 'whisper',
        });
        await _startWhisper();
      }
    } catch (e) {
      debugPrint('VOICE_BRIDGE: start failed $e');
      emitError('start_failed', '語音辨識無法開始，請改用文字輸入');
    } finally {
      _starting = false;
    }
  }

  static Future<bool> _isOnline() async {
    try {
      final result = await InternetAddress.lookup('www.google.com').timeout(const Duration(milliseconds: 1500));
      return result.isNotEmpty && result.first.rawAddress.isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  // ───────────── 線上：Google（Android 系統語音服務） ─────────────

  Future<bool> _ensureGoogle() async {
    if (_speechReady) return true;
    // 只初始化一次；重複 initialize 會讓 Android 語音服務斷線（error_server_disconnected）。
    _speechReady = await _speech.initialize(onStatus: _onGoogleStatus, onError: _onGoogleError);
    return _speechReady;
  }

  Future<void> _startGoogle() async {
    _googleText = '';
    _googleFinalSent = false;
    _googleActive = true;
    await _speech.listen(
      onResult: (r) => _onGoogleResult(r.recognizedWords, r.finalResult),
      listenOptions: SpeechListenOptions(
        listenFor: const Duration(seconds: 20),
        pauseFor: const Duration(seconds: 4),
        localeId: 'zh-TW',
        partialResults: true,
        cancelOnError: true,
        // dictation：一般口述；search 模式會偏向地名（曾把「307」聽成「杉林溪」）
        listenMode: ListenMode.dictation,
      ),
    );
  }

  void _onGoogleStatus(String status) {
    if (status == 'listening' && !_googleAnnounced) {
      _googleAnnounced = true;
      _emit('listeningStarted');
    } else if ((status == 'notListening' || status == 'done') && _googleAnnounced) {
      _googleAnnounced = false;
      _emit('listeningStopped');
    }
    if (status == 'done') {
      _finalizeGoogle();
      _googleActive = false;
    }
  }

  void _onGoogleError(SpeechRecognitionError error) {
    _googleActive = false;
    // Google 結束時常只回 error_client、不送 final：已聽到文字就當 final。
    if (_finalizeGoogle()) return;
    if (_googleFinalSent) return;
    _googleFinalSent = true;
    final offline = error.errorMsg.startsWith('error_network') || error.errorMsg == 'error_server';
    emitError(
      error.errorMsg,
      offline ? '網路不穩，請再按一次麥克風，將改用離線辨識' : '沒有聽清楚，請再按一次麥克風說話',
    );
  }

  void _onGoogleResult(String text, bool isFinal) {
    if (_googleFinalSent) return;
    final trimmed = text.trim();
    if (trimmed.isEmpty && !isFinal) return; // 忽略空白中間結果，避免字幕閃爍
    if (trimmed.isNotEmpty) _googleText = trimmed;
    if (isFinal && _googleText.isNotEmpty) {
      _googleFinalSent = true;
      _emit('speechResult', {'text': _googleText, 'isFinal': true});
    } else if (!isFinal) {
      _emit('speechResult', {'text': trimmed, 'isFinal': false});
    }
  }

  bool _finalizeGoogle() {
    if (_googleFinalSent || _googleText.isEmpty) return false;
    _googleFinalSent = true;
    _emit('speechResult', {'text': _googleText, 'isFinal': true});
    return true;
  }

  // ───────────── 離線：Whisper ─────────────

  Future<void> _startWhisper() async {
    {
      final modelPath = await _ensureModel();
      if (modelPath == null) {
        emitError('model_missing', '第一次使用需要連網下載離線語音模型，請連線後再試，或改用文字輸入');
        return;
      }

      // 先錄成 16 kHz 單聲道 WAV，說完再一次交給 Whisper。
      // （邊錄邊辨識在模擬器的 x86 CPU 上跟不上，且每次啟動要先載入模型約 5 秒，會漏掉開頭。）
      _audioPath = '${Directory.systemTemp.path}/voice_utterance.wav';
      await _recorder.start(
        const RecordConfig(encoder: AudioEncoder.wav, sampleRate: 16000, numChannels: 1),
        path: _audioPath!,
      );
      _cancelled = false;
      _lastVoiceAt = null;
      _quietSince = null;
      _noiseFloor = null;
      _speechPeak = -160;
      _startedAt = DateTime.now();
      _listening = true;
      _emit('listeningStarted');
      // 說完話停頓約 1.2 秒自動結束，手部不便的使用者不必再按停止
      _amplitudeSub = _recorder.onAmplitudeChanged(const Duration(milliseconds: 150)).listen(_onAmplitude);
      _maxTimer = Timer(const Duration(seconds: 10), _finish);
    }
  }

  /// 依音量判斷說話是否結束。噪音底線會緩慢上升，適應有背景聲的環境；
  /// 說話中音量明顯低於峰值（或接近底線）持續 1.2 秒即視為說完。
  void _onAmplitude(Amplitude amplitude) {
    if (!_listening) return;
    final now = DateTime.now();
    final db = amplitude.current.clamp(-120.0, 0.0);
    final floor = _noiseFloor == null
        ? db
        : (db < _noiseFloor! ? db : _noiseFloor! + (db - _noiseFloor!) * 0.03);
    _noiseFloor = floor;
    final elapsed = now.difference(_startedAt);
    if (elapsed < const Duration(milliseconds: 450)) return;

    final speaking = db > floor + 10 && (_lastVoiceAt == null || db > _speechPeak - 18);
    if (speaking) {
      _lastVoiceAt = now;
      _quietSince = null;
      if (db > _speechPeak) _speechPeak = db;
    } else if (_lastVoiceAt != null) {
      _quietSince ??= now;
      if (now.difference(_quietSince!) > const Duration(milliseconds: 1200)) _finish();
    } else if (elapsed > const Duration(seconds: 6)) {
      _finish();
    }
  }

  /// 結束收音並送出最終結果。
  Future<void> _finish() async {
    if (!_listening) return;
    _listening = false;
    _maxTimer?.cancel();
    await _amplitudeSub?.cancel();
    await _recorder.stop();
    _emit('listeningStopped', {
      'recordedMs': DateTime.now().difference(_startedAt).inMilliseconds,
      'noiseFloorDb': _noiseFloor?.round(),
      'peakDb': _speechPeak.round(),
    });

    if (_cancelled) return;
    // 完全沒偵測到說話：不交給 Whisper（安靜音訊容易產生幻覺文字），直接回報
    final heardVoice = _lastVoiceAt != null;
    var text = '';
    if (heardVoice && _audioPath != null) {
      _emit('voiceStatus', {'message': '辨識中…'});
      final started = DateTime.now();
      try {
        final result = await _whisper
            .transcribe(
              model: model,
              audioPath: _audioPath!,
              lang: 'zh',
              initialPrompt: _initialPrompt,
              suppressNonSpeechTokens: true,
              keepModelLoaded: true, // 第二句起不用重新載入模型
            )
            .timeout(const Duration(seconds: 90));
        text = _clean(result?.transcription.text ?? '');
      } catch (e) {
        debugPrint('VOICE_BRIDGE: whisper transcribe failed $e');
      }
      debugPrint('VOICE_BRIDGE: transcribe took ${DateTime.now().difference(started).inMilliseconds} ms');
    }
    if (_cancelled) return;
    if (_isPromptEcho(text)) text = '';
    if (text.isEmpty) {
      emitError('no_speech', '沒有聽清楚，請再按一次麥克風說話');
    } else {
      _emit('speechResult', {'text': text, 'isFinal': true});
    }
  }

  /// 取消收音，不送出結果（朗讀、離開頁面時）。
  Future<void> _cancelListening() async {
    if (_googleActive) {
      _googleFinalSent = true;
      _googleActive = false;
      await _speech.cancel();
    }
    if (!_listening) return;
    _cancelled = true;
    await _finish();
  }

  /// Whisper 在安靜或雜音時，常把提示詞或字幕常見語句當成結果。
  static bool _isPromptEcho(String text) {
    final t = text.replaceAll(RegExp(r'[\s，。、！？,.!?]'), '');
    if (t.isEmpty) return true;
    final prompt = _initialPrompt.replaceAll(RegExp(r'[\s，。、！？,.!?]'), '');
    return prompt.contains(t) || RegExp(r'字幕|訂閱|謝謝觀看|請不吝').hasMatch(t);
  }

  /// 去掉 Whisper 的非語音標註（例如 [BLANK_AUDIO]、(笑)）與多餘空白。
  static String _clean(String text) =>
      text.replaceAll(RegExp(r'\[[^\]]*\]|\([^)]*\)|（[^）]*）'), '').replaceAll(RegExp(r'\s+'), ' ').trim();

  Future<void> stopListening() async {
    if (_googleActive) {
      await _speech.stop(); // 已說的內容會送出 final
    } else {
      await _finish();
    }
  }

  Future<void> _ensureTts() async {
    if (_ttsReady) return;
    _tts.setStartHandler(() => _emit('speakingStarted'));
    _tts.setCompletionHandler(() => _emit('speakingCompleted'));
    _tts.setErrorHandler((msg) => emitError('tts_failed', '朗讀失敗，請直接閱讀畫面文字'));
    await _tts.setLanguage('zh-TW');
    await _tts.setSpeechRate(0.45);
    _ttsReady = true;
  }

  Future<void> speak(Object? text) async {
    if (text is! String || text.trim().isEmpty || text.length > VoiceMessageHandler.maxSpeakLength) {
      emitError('invalid_text', '朗讀內容為空或過長');
      return;
    }
    await _cancelListening(); // 朗讀時取消收音，不再送出辨識結果
    await _ensureTts();
    if (await _tts.isLanguageAvailable('zh-TW') != true) {
      emitError('tts_language_unavailable', '此裝置沒有中文朗讀語音，請直接閱讀畫面文字');
      return;
    }
    await _tts.stop();
    await _tts.speak(text);
  }

  Future<void> stopSpeaking() async {
    await _tts.stop();
    _emit('speakingStopped');
  }

  /// 離開語音頁面時呼叫，避免背景繼續收音或朗讀。
  Future<void> stopAll() async {
    debugPrint('VOICE_BRIDGE: stopAll');
    await _cancelListening();
    await _tts.stop();
  }
}

/// 包住 WebView：頁面關閉（dispose）或 App 進入背景時停止收音與朗讀。
class VoiceLifecycleGuard extends StatefulWidget {
  const VoiceLifecycleGuard({super.key, required this.child});

  final Widget child;

  @override
  State<VoiceLifecycleGuard> createState() => _VoiceLifecycleGuardState();
}

class _VoiceLifecycleGuardState extends State<VoiceLifecycleGuard> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused || state == AppLifecycleState.hidden) {
      VoiceBridge.instance.stopAll();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    VoiceBridge.instance.stopAll();
    VoiceBridge.instance.emitter = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
