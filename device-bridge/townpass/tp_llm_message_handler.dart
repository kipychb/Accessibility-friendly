import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:llamadart/llamadart.dart';
import 'package:path_provider/path_provider.dart';
import 'package:town_pass/util/web_message_handler/tp_web_message_handler.dart';

/// 裝置端 LLM 橋接：讓微服務網頁在手機上執行 Gemma（不連網、不呼叫雲端模型）。
///
/// 網頁 → App：`{"name":"llm","data":{"action":"...", ...}}`
///   action：status | load | generate（需帶 id、system、prompt，可帶 maxTokens）
///
/// App → 網頁：`{"name":"llm","data":{"event":"...", ...}}`
///   event：status(ready,loaded,model) | loaded(ms) | result(id,text,ms) | error(id,code,message)
///
/// 模型：google/gemma-4-E4B-it-qat-q4_0-gguf（Apache-2.0），GGUF q4_0
/// 推論框架：llama.cpp，經 llamadart 套件（MIT），在 CPU 上執行。
/// 模型檔需事先放到 App 的 files 目錄（見 device-bridge/townpass/README.md）。
/// 這裡只負責推論；提示詞與輸出檢查由網頁端的 gemma-parser.js 處理。
class LlmMessageHandler extends TPWebMessageHandler {
  @override
  String get name => 'llm';

  @override
  Future<void> handle({
    required Object? message,
    required WebUri? sourceOrigin,
    required bool isMainFrame,
    required Function(WebMessage replyWebMessage)? onReply,
  }) async {
    final bridge = LlmBridge.instance;
    bridge.emitter = (data) => onReply?.call(replyWebMessage(data: data));
    if (!isMainFrame || message is! Map) {
      bridge.emitError(null, 'invalid_command', 'LLM 命令格式錯誤');
      return;
    }
    switch (message['action']) {
      case 'status':
        await bridge.status();
      case 'load':
        await bridge.load();
      case 'generate':
        await bridge.generate(
          id: message['id'],
          system: message['system'],
          prompt: message['prompt'],
          maxTokens: message['maxTokens'],
          timeoutMs: message['timeoutMs'],
          grammar: message['grammar'],
        );
      default:
        bridge.emitError(null, 'invalid_command', '不支援的 LLM 命令');
    }
  }
}

class LlmBridge {
  LlmBridge._();

  static final LlmBridge instance = LlmBridge._();

  static const String modelFileName = 'gemma-4-E4B_q4_0-it.gguf';
  static const int maxPromptLength = 12000;

  void Function(Map<String, Object?> data)? emitter;

  LlamaEngine? _engine;
  Future<LlamaEngine?>? _loading;
  Future<void> _queue = Future.value(); // 一次只跑一個推論

  void _emit(String event, [Map<String, Object?> extra = const {}]) {
    debugPrint('LLM_BRIDGE: $event ${extra.map((k, v) => MapEntry(k, v is String && v.length > 120 ? '${v.substring(0, 120)}…' : v))}');
    emitter?.call({'event': event, ...extra});
  }

  void emitError(Object? id, String code, String message) => _emit('error', {'id': id, 'code': code, 'message': message});

  Future<String> _modelPath() async => '${(await getApplicationSupportDirectory()).path}/$modelFileName';

  Future<void> status() async {
    final path = await _modelPath();
    _emit('status', {'ready': File(path).existsSync(), 'loaded': _engine != null, 'model': modelFileName});
  }

  /// 載入模型（第一次約需數十秒）；重複呼叫會共用同一次載入。
  Future<LlamaEngine?> load() {
    if (_engine != null) return Future.value(_engine);
    return _loading ??= _doLoad().whenComplete(() => _loading = null);
  }

  Future<LlamaEngine?> _doLoad() async {
    final path = await _modelPath();
    if (!File(path).existsSync()) {
      emitError(null, 'model_missing', '手機上尚未安裝 Gemma 模型，已改用一般查詢');
      return null;
    }
    final started = DateTime.now();
    try {
      _engine = await LlamaEngine.load(
        LlamaModel(ModelSource.path(path)),
        // 系統提示含 55 項服務與別名（約 3,300 字），需較大上下文；
        // 相同的提示前綴會被重複使用，只有第一次需要完整計算。
        params: const ModelParams(contextSize: 6144, device: ComputeDevice.cpu),
      );
      _emit('loaded', {'ms': DateTime.now().difference(started).inMilliseconds});
      return _engine;
    } catch (e) {
      debugPrint('LLM_BRIDGE: load failed $e');
      emitError(null, 'load_failed', 'Gemma 模型載入失敗，已改用一般查詢');
      return null;
    }
  }

  Future<void> generate({
    Object? id,
    Object? system,
    Object? prompt,
    Object? maxTokens,
    Object? timeoutMs,
    Object? grammar,
  }) {
    // 預熱（第一次計算系統提示）可能要數分鐘，允許網頁指定較長的逾時，上限 10 分鐘
    final timeout = Duration(milliseconds: timeoutMs is int ? timeoutMs.clamp(5000, 600000) : 40000);
    // GBNF 文法：限制 Gemma 只能輸出指定格式的 JSON（服務 ID 只能從清單中選）
    final gbnf = grammar is String && grammar.length < 20000 ? grammar : null;
    final run = _queue.then((_) => _generate(id, system, prompt, maxTokens, timeout, gbnf));
    _queue = run.catchError((_) {});
    return run;
  }

  Future<void> _generate(
    Object? id,
    Object? system,
    Object? prompt,
    Object? maxTokens,
    Duration timeout,
    String? grammar,
  ) async {
    if (prompt is! String || prompt.trim().isEmpty || prompt.length > maxPromptLength) {
      emitError(id, 'invalid_prompt', '查詢內容為空或過長');
      return;
    }
    final engine = await load();
    if (engine == null) return;
    final started = DateTime.now();
    try {
      final completion = await engine
          .create(
            [
              if (system is String && system.trim().isNotEmpty)
                LlamaChatMessage.fromText(role: LlamaChatRole.system, text: system),
              LlamaChatMessage.fromText(role: LlamaChatRole.user, text: prompt),
            ],
            params: GenerationParams(
              maxTokens: maxTokens is int ? maxTokens.clamp(16, 512) : 128,
              temp: 0, // 分類任務：固定輸出，不要隨機
              // 關閉 Gemma 4 的思考段落：省時間，也避免 token 額度在思考時用完、正文變空白
              thinkingBudget: const ThinkingBudget(maxTokens: 0),
              grammar: grammar,
            ),
          )
          .collect()
          .timeout(timeout);
      var text = completion.text.trim();
      // 保險：若正文仍為空，從思考內容中取出 JSON 物件
      if (text.isEmpty) {
        final match = RegExp(r'\{[^{}]*\}').allMatches(completion.thinking).lastOrNull;
        text = match?.group(0) ?? '';
      }
      debugPrint('LLM_BRIDGE: finish=${completion.finishReason} thinkingChars=${completion.thinking.length}');
      _emit('result', {'id': id, 'text': text, 'ms': DateTime.now().difference(started).inMilliseconds});
    } on TimeoutException {
      // timeout 不會自動停止 llama.cpp，要明確取消，否則下一次推論會因「仍在執行」而失敗
      engine.cancelGeneration();
      emitError(id, 'timeout', 'Gemma 回應逾時，已改用一般查詢');
    } catch (e) {
      debugPrint('LLM_BRIDGE: generate failed $e');
      emitError(id, 'generate_failed', 'Gemma 推論失敗，已改用一般查詢');
    }
  }
}
