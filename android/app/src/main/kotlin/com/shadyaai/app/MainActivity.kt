package com.shadyaai.app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.shadya.assistant.ShadyaAgentService
import com.shadya.assistant.UserAccountData

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.shadyaai.app/agent"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setUserAccountData" -> {
                    val firstName = call.argument<String>("firstName") ?: ""
                    val lastName = call.argument<String>("lastName") ?: ""
                    val phone = call.argument<String>("phone") ?: ""
                    val password = call.argument<String>("password") ?: ""
                    val mode = call.argument<String>("mode") ?: "signup"

                    ShadyaAgentService.pendingUserData = UserAccountData(
                        firstName = firstName,
                        lastName = lastName,
                        phone = phone,
                        password = password
                    )
                    ShadyaAgentService.pendingMode = mode
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }
}
