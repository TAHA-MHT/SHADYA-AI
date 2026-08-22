package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
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
        var pendingUserData: UserAccountData = UserAccountData()
        var pendingMode: String = "signup"
        var pendingOtpCode: String = ""

        // Indique si un flux d'automatisation est réellement en cours.
        // Sans ce garde-fou, la branche "android" (dialogues système)
        // s'appliquerait à TOUT événement système du téléphone, indéfiniment,
        // ce qui provoquait la réouverture intempestive de Facebook.
        var flowActive: Boolean = false

        private val handler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        // Active le flux et programme une coupure automatique de sécurité après
        // 5 minutes, au cas où le flux ne serait jamais explicitement clôturé.
        fun activateFlow() {
            flowActive = true
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = Runnable { flowActive = false }
            handler.postDelayed(timeoutRunnable!!, 5 * 60 * 1000L)
        }

        fun deactivateFlow() {
            flowActive = false
            timeoutRunnable?.let { handler.removeCallbacks(it) }
        }
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
                    // Ne traite les dialogues système que si un flux est
                    // explicitement actif — sinon, ignore (comportement par défaut).
                    if (flowActive) {
                        facebookAutomation.userData = pendingUserData
                        facebookAutomation.mode = pendingMode
                        facebookAutomation.handleAccessibilityEvent(it)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}
}
