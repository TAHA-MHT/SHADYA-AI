package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

data class UserAccountData(
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val password: String = "",
    // Âge dicté par l'utilisateur, utilisé pour calculer précisément
    // l'année de naissance à sélectionner sur l'écran "date of birth"
    // de Facebook (voir FacebookAutomationHandler.ajusterMoletteAnnee).
    val age: String = "",
    // Genre dicté par l'utilisateur ("Male" ou "Female"), utilisé pour
    // sélectionner automatiquement la bonne option sur l'écran
    // "What's your gender?" de Facebook.
    val gender: String = ""
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
                    // Même garde-fou que pour les dialogues système "android" :
                    // sans ce contrôle, tout événement d'accessibilité émis par
                    // Facebook (réouverture manuelle, notification, relance par
                    // le système) relance l'automatisation avec les données de
                    // la dernière tentative, même après la fin du flux Shadya.
                    if (flowActive) {
                        facebookAutomation.userData = pendingUserData
                        facebookAutomation.mode = pendingMode
                        facebookAutomation.handleAccessibilityEvent(it)
                    }
                }
                "com.whatsapp", "com.whatsapp.w4b" -> {
                    // Capture du code de confirmation Facebook lorsqu'il est
                    // envoyé via WhatsApp plutôt que par SMS classique (les
                    // deux canaux sont possibles selon les cas côté Facebook).
                    // Même principe que pour l'app de messagerie SMS : lecture
                    // de la notification, sans permission supplémentaire.
                    if (flowActive && it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                        val texteNotification = texteCompletNotification(it)
                        journaliserService("Notification WhatsApp reçue: \"$texteNotification\"")
                        val code = Regex("\\b\\d{5}\\b").find(texteNotification)?.value
                        if (code != null) {
                            pendingOtpCode = code
                            facebookAutomation.userData = pendingUserData
                            facebookAutomation.mode = pendingMode
                            facebookAutomation.tenterRemplirCodeConfirmation(code)
                        } else {
                            journaliserService("Aucun code à 5 chiffres trouvé dans cette notification WhatsApp")
                        }
                    }
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
                // Capture du code de confirmation Facebook envoyé par SMS,
                // via la NOTIFICATION affichée par l'app de messagerie —
                // et non via les permissions RECEIVE_SMS/READ_SMS, qui sont
                // quasi impossibles à faire accepter sur le Play Store pour
                // une app qui n'est pas l'application SMS par défaut. Cette
                // approche réutilise uniquement le service d'accessibilité
                // déjà nécessaire à Shadya pour ses autres fonctions
                // (dont son futur rôle de launcher), sans permission
                // supplémentaire.
                "com.samsung.android.messaging", "com.google.android.apps.messaging" -> {
                    if (flowActive && it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                        val texteNotification = texteCompletNotification(it)
                        journaliserService("Notification SMS reçue: \"$texteNotification\"")
                        val code = Regex("\\b\\d{5}\\b").find(texteNotification)?.value
                        if (code != null) {
                            pendingOtpCode = code
                            facebookAutomation.userData = pendingUserData
                            facebookAutomation.mode = pendingMode
                            facebookAutomation.tenterRemplirCodeConfirmation(code)
                        } else {
                            journaliserService("Aucun code à 5 chiffres trouvé dans cette notification SMS")
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    // Reconstitue le texte le plus complet possible d'une notification.
    // event.text seul ne suffit pas : quand plusieurs notifications de la
    // même app arrivent rapprochées (ex: plusieurs codes Facebook envoyés
    // coup sur coup), Android les regroupe souvent en un résumé du type
    // "3 nouveaux messages", sans le contenu détaillé de chaque message —
    // c'est exactement ce qui s'est produit lors d'un test réel, empêchant
    // toute extraction de code. L'objet Notification complet, accessible via
    // event.parcelableData, conserve lui le texte du dernier message
    // individuel dans ses extras (EXTRA_TEXT / EXTRA_BIG_TEXT), même quand
    // la notification affichée à l'écran est un résumé groupé.
    private fun texteCompletNotification(event: AccessibilityEvent): String {
        val morceaux = mutableListOf<String>()

        event.text?.forEach { morceaux.add(it.toString()) }

        val notification = event.parcelableData as? Notification
        val extras = notification?.extras
        extras?.getCharSequence(Notification.EXTRA_TEXT)?.let { morceaux.add(it.toString()) }
        extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { morceaux.add(it.toString()) }
        extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { morceaux.add(it.toString()) }

        return morceaux.joinToString(" ")
    }

    // Journal léger côté service, dans le même fichier que celui utilisé par
    // FacebookAutomationHandler — permet de confirmer qu'une notification a
    // bien été captée par le service, et de voir son contenu brut exact,
    // même quand aucun code n'a pu en être extrait.
    private fun journaliserService(message: String) {
        try {
            val fichier = java.io.File(filesDir, "crash_log.txt")
            val horodatage = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.FRANCE).format(java.util.Date())
            fichier.appendText("[SERVICE $horodatage] $message\n")
        } catch (e: Exception) {
            // Le journal est un outil de diagnostic, pas critique au flux.
        }
    }
}

