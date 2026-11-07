import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_to_text.dart';

void main() => runApp(const MaterialApp(home: VoicePage()));

class VoicePage extends StatefulWidget {
  const VoicePage({super.key});
  @override
  State<VoicePage> createState() => _VoicePageState();
}

class _VoicePageState extends State<VoicePage> with WidgetsBindingObserver {
  final speech = SpeechToText();
  final tts = FlutterTts();
  static const answer = '您好，這是語音測試';
  String words = '', caption = '', status = '準備就緒';
  bool busy = false, offline = false;
  int generation = 0;
  double? soundLevel;
  int soundSamples = 0;
  int resultCount = 0;
  bool downloading = false;
  String downloadStatus = '';
  Future<void> downloadChinese() async {
    if (downloading || busy || speech.isListening) return;
    setState(() {
      downloading = true;
      downloadStatus = '正在要求下載臺灣中文辨識模型…';
    });
    try {
      final result = await diagnosticChannel.invokeMethod<Object>('download', {
        'offline': true,
      });
      if (mounted) setState(() => downloadStatus = '$result');
      await checkSupport();
    } catch (e) {
      if (mounted) setState(() => downloadStatus = '下載失敗：$e');
    } finally {
      if (mounted) setState(() => downloading = false);
    }
  }

  String diagnostics = '語言支援檢查中';
  static const diagnosticChannel = MethodChannel('voice_probe/diagnostics');

  Future<void> checkSupport() async {
    final reports = <String>[];
    for (final deviceOnly in [false, true]) {
      try {
        final report = await diagnosticChannel.invokeMethod<Object>('support', {
          'offline': deviceOnly,
        });
        reports.add('${deviceOnly ? "裝置端" : "一般"}：$report');
        debugPrint('VOICE_SUPPORT_DART: ${reports.last}');
      } catch (e) {
        reports.add('能力查詢失敗：$e');
      }
    }
    if (mounted) setState(() => diagnostics = reports.join('\n'));
  }

  void update(String s) {
    if (mounted) setState(() => status = s);
    debugPrint('VOICE_PROBE: $s');
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    checkSupport();
    tts.setStartHandler(() => update('正在朗讀'));
    tts.setCompletionHandler(() => update('朗讀完成'));
    tts.setErrorHandler((e) => update('朗讀錯誤：$e'));
  }

  Future<void> listen() async {
    if (busy) return;
    setState(() => busy = true);
    final token = ++generation;
    try {
      await tts.stop();
      await speech.cancel();
      final ready = await speech.initialize(
        onStatus: (s) {
          debugPrint('VOICE_STATUS: $s generation=$generation');
          if (s == 'done' || s == 'notListening') update('收音結束，可重新測試');
        },
        onError: (e) => update(
          e.errorMsg == 'error_speech_timeout'
              ? '這次未偵測到語音。請重新按開始，聽到提示音後立即說話。'
              : e.errorMsg == 'error_language_not_supported' ||
                    e.errorMsg == 'error_language_unavailable'
              ? (offline
                    ? '裝置端引擎目前不支援或未備妥繁體中文。請關閉「要求裝置端辨識」後再測試一般辨識。'
                    : '目前語音引擎不支援或未備妥繁體中文，這不是麥克風權限錯誤。')
              : '辨識錯誤：${e.errorMsg}。請檢查語音服務。',
        ),
      );
      if (!mounted || token != generation) return;
      if (!ready) {
        update('辨識無法啟用，請檢查 App 麥克風權限與語音服務。');
        return;
      }
      setState(() {
        words = '';
        caption = '';
        soundLevel = null;
        soundSamples = 0;
        resultCount = 0;
      });
      update(offline ? '正在聆聽中文（裝置端）' : '正在聆聽中文（一般模式）');
      await speech.listen(
        onSoundLevelChange: (level) {
          if (!mounted || token != generation) return;
          setState(() {
            soundLevel = level;
            soundSamples++;
          });
          if (soundSamples % 10 == 0) debugPrint('VOICE_PROBE_LEVEL: $level');
        },
        listenOptions: SpeechListenOptions(
          localeId: offline ? 'cmn-Hant-TW' : 'zh_TW',
          listenFor: const Duration(seconds: 25),
          pauseFor: const Duration(seconds: 8),
          partialResults: true,
          onDevice: offline,
          cancelOnError: true,
        ),
        onResult: (r) {
          debugPrint(
            'VOICE_RESULT: final=${r.finalResult} length=${r.recognizedWords.length} accepted=${mounted && token == generation} token=$token generation=$generation',
          );
          if (!mounted || token != generation) return;
          setState(() {
            resultCount++;
            words = r.recognizedWords;
          });
          if (r.finalResult && words.trim().isNotEmpty) speak();
        },
      );
    } catch (e) {
      update('辨識無法開始：$e');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> speak() async {
    final token = ++generation;
    try {
      await speech.cancel();
      await tts.stop();
      if (!mounted || token != generation) return;
      setState(() => caption = answer);
      final available = await tts.isLanguageAvailable('zh-TW');
      if (!mounted || token != generation) return;
      if (available != true) {
        update('尚無可用繁體中文語音，請閱讀字幕。');
        return;
      }
      await tts.setLanguage('zh-TW');
      await tts.setSpeechRate(0.45);
      if (!mounted || token != generation) return;
      if (await tts.speak(answer) != 1) update('朗讀未成功啟動');
    } catch (e) {
      update('朗讀失敗：$e');
    }
  }

  Future<void> stop() async {
    ++generation;
    await speech.cancel();
    await tts.stop();
    update('已停止，可重新測試');
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.paused) stop();
  }

  @override
  void dispose() {
    ++generation;
    WidgetsBinding.instance.removeObserver(this);
    speech.cancel();
    tts.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('中文語音測試')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const Text('只測試語音，不查詢市政資料'),
          const SizedBox(height: 16),
          Semantics(
            liveRegion: true,
            child: Text(status, style: const TextStyle(fontSize: 20)),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: busy || downloading ? null : listen,
            icon: const Icon(Icons.mic, size: 36),
            label: const Padding(
              padding: EdgeInsets.all(20),
              child: Text('開始說話', style: TextStyle(fontSize: 24)),
            ),
          ),
          const SizedBox(height: 24),
          const Text('辨識文字'),
          Text('辨識結果回呼：$resultCount 次'),
          Text(
            soundLevel == null
                ? '音量偵測：尚無回報'
                : '音量回報：${soundLevel!.toStringAsFixed(1)}（$soundSamples 次）',
          ),
          SelectableText(
            words.isEmpty ? '尚未辨識' : words,
            style: const TextStyle(fontSize: 24),
          ),
          const Divider(height: 32),
          const Text('回答字幕'),
          SelectableText(
            caption.isEmpty ? '等待測試' : caption,
            style: const TextStyle(fontSize: 28),
          ),
          const SizedBox(height: 24),
          OutlinedButton(
            onPressed: busy ? null : speak,
            child: const Padding(
              padding: EdgeInsets.all(14),
              child: Text('朗讀固定測試答案'),
            ),
          ),
          OutlinedButton(
            onPressed: stop,
            child: const Padding(
              padding: EdgeInsets.all(14),
              child: Text('停止收音與朗讀'),
            ),
          ),
          SwitchListTile(
            title: const Text('要求裝置端辨識'),
            subtitle: const Text('中文離線能力仍需斷網實測'),
            value: offline,
            onChanged: busy || speech.isListening
                ? null
                : (v) => setState(() => offline = v),
          ),
          const Text('一般辨識可能由系統語音服務連網處理。請只用測試句，不輸入個人資料。本程式未串接 LLM。'),
          OutlinedButton(
            onPressed: downloading || busy || speech.isListening
                ? null
                : downloadChinese,
            child: const Text('下載臺灣中文離線辨識模型'),
          ),
          Text(downloadStatus),
          ExpansionTile(
            title: const Text('診斷結果（語言支援）'),
            children: [SelectableText(diagnostics)],
          ),
        ],
      ),
    ),
  );
}
