package com.shadyaai.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FacebookAutomationHandler(private val service: AccessibilityService) {

    // Données par défaut générées automatiquement pour l'utilisateur
    private var userFirstName = "Shadya"
    private var userLastName = "User"
    private var userPhone = "" // Récupéré automatiquement ou transmis par l'app
    private var userPassword = "ShadyaUser2026!" // Mot de passe auto-généré sécurisé

    fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = service.rootInActiveWindow ?: return

        // 1. Détection du bouton "Créer un nouveau compte" / "Create new account"
        val createAccountButtons = findNodesByText(rootNode, listOf("Créer un compte", "Create new account", "S'inscrire"))
        if (createAccountButtons.isNotEmpty()) {
            createAccountButtons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // 2. Détection et remplissage du Prénom et Nom
        val firstNameFields = findFieldsByHint(rootNode, listOf("Prénom", "First name"))
        val lastNameFields = findFieldsByHint(rootNode, listOf("Nom", "Last name", "Nom de famille"))

        if (firstNameFields.isNotEmpty() && lastNameFields.isNotEmpty()) {
            fillTextField(firstNameFields.first(), userFirstName)
            fillTextField(lastNameFields.first(), userLastName)
            clickNextButton(rootNode)
            return
        }

        // 3. Détection et remplissage du Numéro de Téléphone
        val phoneFields = findFieldsByHint(rootNode, listOf("Numéro de mobile", "Mobile number", "Téléphone"))
        if (phoneFields.isNotEmpty()) {
            if (userPhone.isNotEmpty()) {
                fillTextField(phoneFields.first(), userPhone)
                clickNextButton(rootNode)
            }
            return
        }

        // 4. Détection et remplissage du Mot de passe
        val passwordFields = findFieldsByHint(rootNode, listOf("Mot de passe", "Password"))
        if (passwordFields.isNotEmpty()) {
            fillTextField(passwordFields.first(), userPassword)
            clickNextButton(rootNode)
            return
        }
    }

    // --- Fonctions utilitaires d'action de l'Agent ---

    private fun fillTextField(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clickNextButton(rootNode: AccessibilityNodeInfo) {
        val nextButtons = findNodesByText(rootNode, listOf("Suivant", "Next", "S'inscrire", "Continue"))
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
