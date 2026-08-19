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
        // Rempli dynamiquement par MainActivity via MethodChannel
        var pendingUserData: UserAccountData = UserAccountData()
        // "signup" ou "login" — dit à Shadya quel écran elle doit gérer (Facebook)
        var pendingMode: String = "signup"
        // Rempli automatiquement par SmsCodeReceiver dès qu'un code OTP est détecté
        var pendingOtpCode: String = ""
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
                "com.facebook.katana", "com.facebook.lite" -> {
                    facebookAutomation.userData = pendingUserData
                    facebookAutomation.mode = pendingMode
                    facebookAutomation.handleAccessibilityEvent(it)
                }
                "com.whatsapp", "com.whatsapp.w4b" -> {
                    whatsAppAutomation.userData = pendingUserData
                    whatsAppAutomation.handleAccessibilityEvent(it)
                }
                "android" -> {
                    // Boîtes de dialogue système (ex: sélecteur de date natif)
                    // affichées par-dessus Facebook — on les traite comme faisant
                    // partie du flux Facebook en cours.
                    facebookAutomation.userData = pendingUserData
                    facebookAutomation.mode = pendingMode
                    facebookAutomation.handleAccessibilityEvent(it)
                }
            }
        }
    }

    override fun onInterrupt() {}
}
