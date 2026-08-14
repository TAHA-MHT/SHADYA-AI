package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

data class UserAccountData(
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val password: String = ""
)

class ShadyaAgentService : AccessibilityService() {

    private lateinit var facebookAutomation: FacebookAutomationHandler
    private lateinit var whatsAppAutomation: WhatsAppAutomationHandler

    companion object {
        // Rempli dynamiquement par MainActivity via MethodChannel,
        // juste avant que Shadya n'ouvre l'app cible pour créer le compte.
        var pendingUserData: UserAccountData = UserAccountData()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        facebookAutomation = FacebookAutomationHandler(this)
        whatsAppAutomation = WhatsAppAutomationHandler(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            val packageName = it.packageName?.toString() ?: ""

            when (packageName) {
                // Détection de Facebook & Facebook Lite
                "com.facebook.katana", "com.facebook.lite" -> {
                    facebookAutomation.userData = pendingUserData
                    facebookAutomation.handleAccessibilityEvent(it)
                }
                // Détection de WhatsApp & WhatsApp Business
                "com.whatsapp", "com.whatsapp.w4b" -> {
                    whatsAppAutomation.handleAccessibilityEvent(it)
                }
            }
        }
    }

    override fun onInterrupt() {
        // Interruption du service
    }
}
