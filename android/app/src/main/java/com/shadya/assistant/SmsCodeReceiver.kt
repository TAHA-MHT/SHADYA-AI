package com.shadya.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.regex.Pattern

class SmsCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val messageBody = sms.messageBody ?: continue
                
                // Détecter un code à 5 ou 6 chiffres dans le texte du SMS
                val pattern = Pattern.compile("\\b\\d{5,6}\\b")
                val matcher = pattern.matcher(messageBody)
                
                if (matcher.find()) {
                    val otpCode = matcher.group(0)
                    // TODO: Transmettre le code pour la validation automatique
                }
            }
        }
    }
}
