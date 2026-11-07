import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_to_text.dart';

/// A device service; the host owns origin checks and JSON transport.
class VoiceService {
  VoiceService(this.emit);
  final void Function(String name, Map<String, Object?> data) emit;
  final _speech = SpeechToText();
  final _tts = FlutterTts();
  bool _disposed = false;
  bool _starting = false;
  int _session = 0;
  void _event(String name, Map<String, Object?> data) {
    if (!_disposed) emit(name, data);
  }
  Future<void> startListening({bool onDevice = true}) async {
    if (_starting || _speech.isListening || _disposed) return;
    _starting = true;
    final session = ++_session;
    try {
      await _tts.stop();
      final available = await _speech.initialize(
        onStatus: (status) {
          if (status == 'listening') _event('listeningStarted', {});
          if (status == 'done') _event('listeningStopped', {});
        },
        onError: (error) => _event('speechError', {'code': error.errorMsg}),
      );
      if (_disposed || session != _session) return;
      if (!available) { _event('speechError', {'code': 'permission_or_service_unavailable'}); return; }
      await _speech.listen(
        listenOptions: SpeechListenOptions(
          localeId: onDevice ? 'cmn-Hant-TW' : 'zh-TW',
          onDevice: onDevice, partialResults: true, cancelOnError: true,
          listenFor: const Duration(seconds: 25),
        ),
        onResult: (result) {
          if (_disposed || session != _session) return;
          _event('speechResult', {'text': result.recognizedWords, 'isFinal': result.finalResult});
        },
      );
    } catch (e) { _event('speechError', {'code': 'start_failed', 'message': '$e'}); }
    finally { _starting = false; }
  }
  Future<void> stopListening() async => _speech.stop();
  Future<void> speak(String text) async {
    if (_disposed) return;
    if (text.trim().isEmpty || text.length > 3000) {
      _event('speechError', {'code': 'invalid_answer'}); return;
    }
    ++_session;
    await _speech.cancel();
    await _tts.stop();
    _event('answerText', {'text': text});
    _tts.setStartHandler(() => _event('speakingStarted', {}));
    _tts.setCompletionHandler(() => _event('speakingCompleted', {}));
    _tts.setErrorHandler((e) => _event('speechError', {'code': 'tts_failed', 'message': '$e'}));
    if (await _tts.isLanguageAvailable('zh-TW') != true) {
      _event('speechError', {'code': 'tts_language_unavailable'}); return;
    }
    await _tts.setLanguage('zh-TW');
    await _tts.setSpeechRate(0.45);
    await _tts.speak(text);
  }
  Future<void> stopSpeaking() async {
    await _tts.stop();
    _event('speakingStopped', {});
  }
  Future<void> dispose() async {
    _disposed = true;
    ++_session;
    await _speech.cancel();
    await _tts.stop();
  }
}
