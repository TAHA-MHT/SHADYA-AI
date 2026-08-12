package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ShadyaAgentService : AccessibilityService() {

    private lateinit var facebookAutomation: FacebookAutomationHandler
    private lateinit var whatsAppAutomation: WhatsAppAutomationHandler

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
