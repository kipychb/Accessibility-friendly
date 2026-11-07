package tw.taipei.hackathon.voice_probe

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        MethodChannel(engine.dartExecutor.binaryMessenger, "voice_probe/diagnostics")
            .setMethodCallHandler { call, result ->
                if (call.method != "support" && call.method != "download") { result.notImplemented(); return@setMethodCallHandler }
                val offline = call.argument<Boolean>("offline") ?: false
                if (Build.VERSION.SDK_INT < 33) {
                    result.success(mapOf("status" to "API below 33")); return@setMethodCallHandler
                }
                if (offline && !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    result.success(mapOf("status" to "No on-device recognizer")); return@setMethodCallHandler
                }
                val recognizer = if (offline) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    else SpeechRecognizer.createSpeechRecognizer(this)
                val handler = Handler(Looper.getMainLooper())
                var finished = false
                fun finish(value: Map<String, Any>) {
                    if (finished) return
                    finished = true
                    Log.i("VOICE_SUPPORT", value.toString())
                    result.success(value)
                    recognizer.destroy()
                }
                handler.postDelayed({ finish(mapOf("status" to "query timeout", "offline" to offline)) }, if (call.method == "download") 120000 else 12000)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (offline) "cmn-Hant-TW" else "zh-TW")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                }
                try {
                    if (call.method == "download") {
                        if (Build.VERSION.SDK_INT < 34) { finish(mapOf("status" to "Download callbacks require API 34")); return@setMethodCallHandler }
                        recognizer.triggerModelDownload(intent, mainExecutor, object : ModelDownloadListener {
                            override fun onProgress(p: Int) { Log.i("VOICE_SUPPORT", "download progress=$p") }
                            override fun onSuccess() { finish(mapOf("status" to "download success")) }
                            override fun onScheduled() { finish(mapOf("status" to "download scheduled")) }
                            override fun onError(error: Int) { finish(mapOf("status" to "download error", "code" to error)) }
                        })
                        return@setMethodCallHandler
                    }
                    recognizer.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(s: RecognitionSupport) {
                            finish(mapOf("status" to "ok", "offline" to offline,
                                "defaultOnlineEngine" to (Settings.Secure.getString(contentResolver, "voice_recognition_service") ?: "unknown"),
                                "installedOnDevice" to s.installedOnDeviceLanguages,
                                "pendingOnDevice" to s.pendingOnDeviceLanguages,
                                "supportedOnDevice" to s.supportedOnDeviceLanguages,
                                "online" to s.onlineLanguages))
                        }
                        override fun onError(error: Int) {
                            finish(mapOf("status" to "error", "code" to error, "offline" to offline))
                        }
                    })
                } catch (e: Exception) { finish(mapOf("status" to "exception", "message" to e.toString())) }
            }
    }
}
