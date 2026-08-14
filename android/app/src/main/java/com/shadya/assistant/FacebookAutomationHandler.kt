package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FacebookAutomationHandler(private val service: AccessibilityService) {

    // Mis à jour par ShadyaAgentService juste avant chaque tentative,
    // à partir de ce que l'utilisateur a dicté vocalement.
    var userData: UserAccountData = UserAccountData()

    fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = service.rootInActiveWindow ?: return

        val createAccountButtons = findNodesByText(rootNode, listOf("Créer un compte", "Create new account", "S'inscrire"))
        if (createAccountButtons.isNotEmpty()) {
            createAccountButtons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        val firstNameFields = findFieldsByHint(rootNode, listOf("Prénom", "First name"))
        val lastNameFields = findFieldsByHint(rootNode, listOf("Nom", "Last name", "Nom de famille"))

        if (firstNameFields.isNotEmpty() && lastNameFields.isNotEmpty() && userData.firstName.isNotEmpty()) {
            fillTextField(firstNameFields.first(), userData.firstName)
            fillTextField(lastNameFields.first(), userData.lastName)
            clickNextButton(rootNode)
            return
        }

        val phoneFields = findFieldsByHint(rootNode, listOf("Numéro de mobile", "Mobile number", "Téléphone"))
        if (phoneFields.isNotEmpty() && userData.phone.isNotEmpty()) {
            fillTextField(phoneFields.first(), userData.phone)
            clickNextButton(rootNode)
            return
        }

        val passwordFields = findFieldsByHint(rootNode, listOf("Mot de passe", "Password"))
        if (passwordFields.isNotEmpty() && userData.password.isNotEmpty()) {
            fillTextField(passwordFields.first(), userData.password)
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
