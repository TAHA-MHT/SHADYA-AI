package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ShadyaAgentService : AccessibilityService() {

    companion object {
        // État de la création de compte
        var isCreatingAccount = false
        var userPhoneNumber = ""
        var userFirstName = ""
        var userLastName = ""
        var generatedPassword = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isCreatingAccount) return

        val rootNode = rootInActiveWindow ?: return
        val packageName = event?.packageName?.toString() ?: ""

        // Cibler l'application Facebook
        if (packageName.contains("facebook")) {
            executeFacebookRegistrationStep(rootNode)
        }
    }

    private fun executeFacebookRegistrationStep(rootNode: AccessibilityNodeInfo) {
        // 1. Détecter et remplir le champ "Prénom"
        val firstNameFields = rootNode.findAccessibilityNodeInfosByText("Prénom")
        for (node in firstNameFields) {
            if (node.isEditable && userFirstName.isNotEmpty()) {
                inputText(node, userFirstName)
            }
        }

        // 2. Détecter et remplir le champ "Nom"
        val lastNameFields = rootNode.findAccessibilityNodeInfosByText("Nom")
        for (node in lastNameFields) {
            if (node.isEditable && userLastName.isNotEmpty()) {
                inputText(node, userLastName)
            }
        }

        // 3. Détecter et remplir le champ "Numéro de mobile"
        val phoneFields = rootNode.findAccessibilityNodeInfosByText("Numéro de mobile")
        for (node in phoneFields) {
            if (node.isEditable && userPhoneNumber.isNotEmpty()) {
                inputText(node, userPhoneNumber)
            }
        }

        // 4. Détecter et remplir le Mot de Passe généré par Shadya
        val passwordFields = rootNode.findAccessibilityNodeInfosByText("Mot de passe")
        for (node in passwordFields) {
            if (node.isEditable && generatedPassword.isNotEmpty()) {
                inputText(node, generatedPassword)
            }
        }

        // 5. Appuyer automatiquement sur les boutons d'action ("S'inscrire", "Suivant", "Valider")
        val actionButtons = rootNode.findAccessibilityNodeInfosByText("S'inscrire")
            .ifEmpty { rootNode.findAccessibilityNodeInfosByText("Suivant") }

        for (button in actionButtons) {
            if (button.isClickable) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    // Fonction utilitaire pour injecter du texte dans un champ
    private fun inputText(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun onInterrupt() {}
}
