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
                    val age = call.argument<String>("age") ?: ""
                    val gender = call.argument<String>("gender") ?: ""
                    val mode = call.argument<String>("mode") ?: "signup"

                    ShadyaAgentService.pendingUserData = UserAccountData(
                        firstName = firstName,
                        lastName = lastName,
                        phone = phone,
                        password = password,
                        age = age,
                        gender = gender
                    )
                    ShadyaAgentService.pendingMode = mode
                    result.success(true)
                }
                "activateFlow" -> {
                    ShadyaAgentService.activateFlow()
                    result.success(true)
                }
                "deactivateFlow" -> {
                    ShadyaAgentService.deactivateFlow()
                    result.success(true)
                }
                // Lance une application installée directement par son nom de
                // package, via l'intent de lancement standard fourni par
                // Android (PackageManager.getLaunchIntentForPackage) — plutôt
                // que de dépendre d'un schéma d'URL personnalisé (ex:
                // "whatsapp://"), qui n'est pas toujours défini par l'app
                // cible pour une ouverture "à vide" (sans destination
                // précise). C'est ce qui faisait échouer l'ouverture de
                // WhatsApp côté Dart (canLaunchUrl renvoyait false, provoquant
                // un repli silencieux vers le navigateur web à la place de
                // l'application), alors que le package est pourtant bien
                // installé et déjà déclaré dans les <queries> du manifeste.
                "lancerApplication" -> {
                    val nomPackage = call.argument<String>("packageName") ?: ""
                    if (nomPackage.isEmpty()) {
                        result.success(false)
                    } else {
                        try {
                            val intentLancement = packageManager.getLaunchIntentForPackage(nomPackage)
                            if (intentLancement != null) {
                                startActivity(intentLancement)
                                result.success(true)
                            } else {
                                result.success(false)
                            }
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}

