package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ShadyaAgentService : AccessibilityService() {

    private lateinit var facebookAutomation: FacebookAutomationHandler

    override fun onServiceConnected() {
        super.onServiceConnected()
        facebookAutomation = FacebookAutomationHandler(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            val packageName = it.packageName?.toString() ?: ""
            
            if (packageName == "com.facebook.katana" || packageName == "com.facebook.lite") {
                facebookAutomation.handleAccessibilityEvent(it)
            }
        }
    }

    override fun onInterrupt() {
        // Gestion de l'interruption du service
    }
}
