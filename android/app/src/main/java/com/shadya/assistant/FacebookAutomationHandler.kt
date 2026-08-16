package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FacebookAutomationHandler(private val service: AccessibilityService) {

    // Mis à jour par ShadyaAgentService juste avant chaque tentative,
    // à partir de ce que l'utilisateur a dicté vocalement (ou récupéré du stockage sécurisé).
    var userData: UserAccountData = UserAccountData()

    // "signup" = créer un nouveau compte, "login" = se reconnecter à un compte existant
    var mode: String = "signup"

    fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = service.rootInActiveWindow ?: return

        if (mode == "login") {
            handleLogin(rootNode)
        } else {
            handleSignup(rootNode)
        }
    }

    private fun handleLogin(rootNode: AccessibilityNodeInfo) {
        // Champ identifiant (numéro de téléphone ou email)
        val loginFields = findFieldsByHint(rootNode, listOf("Numéro de mobile ou e-mail", "Mobile number or email", "Téléphone"))
        val passwordFields = findFieldsByHint(rootNode, listOf("Mot de passe", "Password"))

        if (loginFields.isNotEmpty() && passwordFields.isNotEmpty() && userData.phone.isNotEmpty() && userData.password.isNotEmpty()) {
            fillTextField(loginFields.first(), userData.phone)
            fillTextField(passwordFields.first(), userData.password)

            val loginButtons = findNodesByText(rootNode, listOf("Connexion", "Log In", "Se connecter"))
            if (loginButtons.isNotEmpty()) {
                loginButtons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return
        }

        // Si un seul champ à la fois (certaines versions de Facebook séparent identifiant/mdp en 2 écrans)
        if (loginFields.isNotEmpty() && userData.phone.isNotEmpty() && passwordFields.isEmpty()) {
            fillTextField(loginFields.first(), userData.phone)
            clickNextButton(rootNode)
            return
        }
        if (passwordFields.isNotEmpty() && userData.password.isNotEmpty()) {
            fillTextField(passwordFields.first(), userData.password)
            val loginButtons = findNodesByText(rootNode, listOf("Connexion", "Log In", "Se connecter"))
            if (loginButtons.isNotEmpty()) {
                loginButtons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun handleSignup(rootNode: AccessibilityNodeInfo) {
        val createAccountButtons = findNodesByText(rootNode, listOf("Créer un compte", "Create new account", "S'inscrire", "Get started", "GET STARTED"))
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
