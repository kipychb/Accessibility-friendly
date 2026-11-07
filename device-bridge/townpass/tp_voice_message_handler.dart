import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_recognition_error.dart';
import 'package:speech_to_text/speech_to_text.dart';
import 'package:town_pass/util/web_message_handler/tp_web_message_handler.dart';

/// 語音橋接：讓微服務網頁透過 flutterObject 使用原生中文語音辨識（STT）與朗讀（TTS）。
///
/// 網頁 → App：`{"name":"voice","data":{"action":"startListening"}}`
///   action：startListening | stopListening | speak（需帶 text）| stopSpeaking
///
/// App → 網頁：`{"name":"voice","data":{"event":"...", ...}}`
///   event：listeningStarted | listeningStopped | speechResult(text,isFinal)
///          | speechError(code,message) | speakingStarted | speakingCompleted | speakingStopped
///
/// 不使用 LLM。辨識由 Android 系統語音服務執行（一般模式可能連網）。
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
        await bridge.startListening();
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

  void Function(Map<String, Object?> data)? emitter;

  final SpeechToText _speech = SpeechToText();
  final FlutterTts _tts = FlutterTts();
  bool _speechReady = false;
  bool _ttsReady = false;
  bool _starting = false;
  bool _listeningAnnounced = false;

  void _emit(String event, [Map<String, Object?> extra = const {}]) {
    debugPrint('VOICE_BRIDGE: $event $extra');
    emitter?.call({'event': event, ...extra});
  }

  void emitError(String code, String message) => _emit('speechError', {'code': code, 'message': message});

  Future<bool> _ensureSpeech() async {
    if (_speechReady) return true;
    // 只初始化一次；重複 initialize 會讓 Android 語音服務斷線（error_server_disconnected）。
    _speechReady = await _speech.initialize(
      onStatus: _onStatus,
      onError: _onError,
    );
    return _speechReady;
  }

  void _onStatus(String status) {
    if (status == 'listening' && !_listeningAnnounced) {
      _listeningAnnounced = true;
      _emit('listeningStarted');
    } else if ((status == 'notListening' || status == 'done') && _listeningAnnounced) {
      _listeningAnnounced = false;
      _emit('listeningStopped');
    }
    if (status == 'done') _finalizeWithLastText();
  }

  void _onError(SpeechRecognitionError error) {
    // 部分 Google 語音服務版本結束時只回 error_client／no_match，不送 final 結果。
    // 若已聽到文字，就把最後一次的文字當作 final，避免整個流程卡住。
    if (_finalizeWithLastText()) return;
    emitError(error.errorMsg, _errorMessage(error.errorMsg));
  }

  // 本次收音的狀態
  String _lastText = '';
  bool _finalSent = true;

  void _onResult(String text, bool isFinal) {
    if (_finalSent) return;
    final trimmed = text.trim();
    if (trimmed.isEmpty && !isFinal) return; // 忽略空白的中間結果，避免字幕閃爍
    if (trimmed.isNotEmpty) _lastText = trimmed;
    if (isFinal) {
      if (_lastText.isEmpty) return; // 交給 error / done 處理
      _finalSent = true;
      _emit('speechResult', {'text': _lastText, 'isFinal': true});
    } else {
      _emit('speechResult', {'text': trimmed, 'isFinal': false});
    }
  }

  /// 若尚未送出 final 且有聽到文字，補送 final。回傳是否有補送。
  bool _finalizeWithLastText() {
    if (_finalSent || _lastText.isEmpty) return false;
    _finalSent = true;
    _emit('speechResult', {'text': _lastText, 'isFinal': true});
    return true;
  }

  static String _errorMessage(String code) => switch (code) {
        'error_speech_timeout' || 'error_no_match' => '沒有聽清楚，請再按一次麥克風說話',
        'error_network' || 'error_network_timeout' || 'error_server' => '語音服務需要網路，請確認連線後再試',
        'error_permission' || 'error_insufficient_permissions' => '尚未允許麥克風權限，請到設定開啟',
        'error_busy' || 'error_server_disconnected' => '語音服務忙碌中，請稍後再試',
        'error_language_not_supported' || 'error_language_unavailable' => '此裝置的語音服務不支援中文辨識',
        _ => '語音辨識發生問題，請改用文字輸入',
      };

  Future<void> startListening() async {
    if (_starting || _speech.isListening) return;
    _starting = true;
    try {
      await _tts.stop();
      if (!await _ensureSpeech()) {
        final permitted = await _speech.hasPermission;
        permitted
            ? emitError('service_unavailable', '此裝置沒有可用的語音辨識服務，請改用文字輸入')
            : emitError('permission_denied', '尚未允許麥克風權限，請到設定開啟');
        return;
      }
      _lastText = '';
      _finalSent = false;
      await _speech.listen(
        onResult: (result) => _onResult(result.recognizedWords, result.finalResult),
        listenOptions: SpeechListenOptions(
          listenFor: const Duration(seconds: 20),
          pauseFor: const Duration(seconds: 4),
          localeId: 'zh-TW',
          partialResults: true,
          cancelOnError: true,
          // dictation：一般口述；search 模式會偏向地名／網搜詞（曾把「307」聽成「杉林溪」）
          listenMode: ListenMode.dictation,
        ),
      );
    } catch (e) {
      emitError('start_failed', '語音辨識無法開始，請改用文字輸入');
      debugPrint('VOICE_BRIDGE: start failed $e');
    } finally {
      _starting = false;
    }
  }

  Future<void> stopListening() async {
    if (_speech.isListening) await _speech.stop();
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
    _finalSent = true; // 朗讀時取消收音，不再補送辨識結果
    if (_speech.isListening) await _speech.cancel();
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
    _finalSent = true;
    if (_speech.isListening) await _speech.cancel();
    await _tts.stop();
  }
}
