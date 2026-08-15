package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAutomationHandler(private val service: AccessibilityService) {

    // Mis à jour par ShadyaAgentService juste avant chaque tentative,
    // à partir de ce que l'utilisateur a dicté vocalement.
    var userData: UserAccountData = UserAccountData()

    // Récupération automatique du numéro SIM (ou valeur d'attente si non lisible)
    private fun getPhoneNumberFromSim(): String {
        return try {
            val telephonyManager = service.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.line1Number ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = service.rootInActiveWindow ?: return

        // 1. Clic automatique sur "Accepter et continuer"
        val agreeButtons = findNodesByText(rootNode, listOf("Accepter et continuer", "Agree and continue", "AGREE AND CONTINUE"))
        if (agreeButtons.isNotEmpty()) {
            agreeButtons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // 2. Remplissage automatique du numéro de téléphone
        val phoneFields = findFieldsByHint(rootNode, listOf("numéro de téléphone", "phone number", "Phone number"))
        val numeroAUtiliser = userData.phone.ifEmpty { getPhoneNumberFromSim() }
        if (phoneFields.isNotEmpty() && numeroAUtiliser.isNotEmpty()) {
            fillTextField(phoneFields.first(), numeroAUtiliser)
            clickNextButton(rootNode)
            return
        }

        // 3. Validation automatique des boîtes de dialogue "OK" / "Oui"
        val confirmButtons = findNodesByText(rootNode, listOf("OK", "Oui", "Yes"))
        if (confirmButtons.isNotEmpty()) {
            confirmButtons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // 4. Code de vérification SMS — rempli automatiquement par SmsCodeReceiver
        // (voir ShadyaAgentService.pendingOtpCode), on se contente ici de le saisir si présent
        val codeFields = findFieldsByHint(rootNode, listOf("code de vérification", "verification code", "Code"))
        if (codeFields.isNotEmpty() && ShadyaAgentService.pendingOtpCode.isNotEmpty()) {
            fillTextField(codeFields.first(), ShadyaAgentService.pendingOtpCode)
            return
        }

        // 5. Remplissage du nom de profil (première configuration du compte)
        val nameFields = findFieldsByHint(rootNode, listOf("Votre nom", "Your name", "Nom"))
        if (nameFields.isNotEmpty() && userData.firstName.isNotEmpty()) {
            val nomComplet = if (userData.lastName.isNotEmpty()) "${userData.firstName} ${userData.lastName}" else userData.firstName
            fillTextField(nameFields.first(), nomComplet)
            clickNextButton(rootNode)
            return
        }
    }

    private fun fillTextField(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clickNextButton(rootNode: AccessibilityNodeInfo) {
        val nextButtons = findNodesByText(rootNode, listOf("Suivant", "Next", "SUIVANT"))
        if (nextButtons.isNotEmpty()) {
            nextButtons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun findNodesByText(rootNode: AccessibilityNodeInfo, texts: List<String>): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        for (text in texts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                result.addAll(nodes)
            }
        }
        return result
    }

    private fun findFieldsByHint(rootNode: AccessibilityNodeInfo, hints: List<String>): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        for (hint in hints) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(hint)
            if (!nodes.isNullOrEmpty()) {
                result.addAll(nodes.filter { it.isEditable })
            }
        }
        return result
    }
}
