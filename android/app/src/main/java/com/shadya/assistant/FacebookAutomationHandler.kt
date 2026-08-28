package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FacebookAutomationHandler(private val service: AccessibilityService) {

    var userData: UserAccountData = UserAccountData()
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
        val loginFields = findFieldsByHint(rootNode, listOf(
            "Numéro de mobile ou e-mail", "Mobile number or email", "Mobile number or email address",
            "Téléphone", "Phone"
        ))
        val passwordFields = findFieldsByHint(rootNode, listOf("Mot de passe", "Password"))

        if (loginFields.isNotEmpty() && passwordFields.isNotEmpty() && userData.phone.isNotEmpty() && userData.password.isNotEmpty()) {
            fillTextField(loginFields.first(), userData.phone)
            fillTextField(passwordFields.first(), userData.password)

            val loginButtons = findNodesByText(rootNode, listOf("Connexion", "Log In", "Se connecter", "Log in"))
            if (loginButtons.isNotEmpty()) {
                performClick(loginButtons.first())
            }
            return
        }

        if (loginFields.isNotEmpty() && userData.phone.isNotEmpty() && passwordFields.isEmpty()) {
            fillTextField(loginFields.first(), userData.phone)
            clickNextButton(rootNode)
            return
        }
        if (passwordFields.isNotEmpty() && userData.password.isNotEmpty()) {
            fillTextField(passwordFields.first(), userData.password)
            val loginButtons = findNodesByText(rootNode, listOf("Connexion", "Log In", "Se connecter", "Log in"))
            if (loginButtons.isNotEmpty()) {
                performClick(loginButtons.first())
            }
        }
    }

    private fun handleSignup(rootNode: AccessibilityNodeInfo) {
        // Détection de fin de parcours : présence du fil d'actualité ou de la
        // barre de navigation principale, signe que le compte est créé et que
        // Facebook est arrivé sur l'écran d'accueil. On désactive alors le
        // flux côté service natif pour éviter toute action ultérieure non
        // désirée sur d'autres écrans système ou applications.
        val homeIndicators = findNodesByText(rootNode, listOf(
            "Quoi de neuf", "What's on your mind", "Fil d'actualité", "News Feed", "Home"
        ))
        if (homeIndicators.isNotEmpty()) {
            ShadyaAgentService.deactivateFlow()
            return
        }

        // Écran "Select your name" : Facebook rejette le nom saisi et propose
        // des variantes via des boutons radio (le nom exact varie et ne peut
        // pas être anticipé par une liste de textes candidats). On sélectionne
        // la première suggestion proposée, puis on poursuit avec "Next".
        val demandeSelectionNom = findNodesByText(rootNode, listOf(
            "Please select your name", "Veuillez sélectionner votre nom",
            "Select your name", "Sélectionnez votre nom"
        ))
        if (demandeSelectionNom.isNotEmpty()) {
            val premierBoutonRadio = findFirstRadioButton(rootNode)
            if (premierBoutonRadio != null) {
                performClick(premierBoutonRadio)
                clickNextButton(rootNode)
            }
            return
        }

        val createAccountButtons = findNodesByText(rootNode, listOf(
            "Créer un compte", "Create new account", "S'inscrire",
            "Get started", "GET STARTED", "Get Started"
        ))
        if (createAccountButtons.isNotEmpty()) {
            performClick(createAccountButtons.first())
            return
        }

        // Popup système "Choose an email address to auto-fill your details"
        val skipButtons = findNodesByText(rootNode, listOf("Skip", "Ignorer"))
        val okButtons = findNodesByText(rootNode, listOf("OK"))
        if (skipButtons.isNotEmpty() && okButtons.isNotEmpty()) {
            performClick(skipButtons.first())
            return
        }

        // Écran "What's your date of birth?" — la date affichée par défaut est
        // celle du jour même (âge 0 an), que Facebook rejette systématiquement
        // en renvoyant sur ce même écran, provoquant une boucle infinie. On
        // fait donc défiler la molette de l'année vers le passé avant de
        // valider, pour obtenir un âge plausible (~20 ans) et adulte.
        val setDateButtons = findNodesByText(rootNode, listOf("SET"))
        val cancelButtons = findNodesByText(rootNode, listOf("CANCEL"))
        if (setDateButtons.isNotEmpty() && cancelButtons.isNotEmpty()) {
            val moletteAnnee = findYearPicker(rootNode)
            if (moletteAnnee != null) {
                repeat(20) {
                    moletteAnnee.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
            }
            performClick(setDateButtons.first())
            return
        }

        val firstNameFields = findFieldsByHint(rootNode, listOf("Prénom", "First name"))
        val lastNameFields = findFieldsByHint(rootNode, listOf("Nom", "Last name", "Nom de famille", "Surname"))

        if (firstNameFields.isNotEmpty() && lastNameFields.isNotEmpty() && userData.firstName.isNotEmpty()) {
            fillTextField(firstNameFields.first(), userData.firstName)
            fillTextField(lastNameFields.first(), userData.lastName)
            clickNextButton(rootNode)
            return
        }

        val phoneFields = findFieldsByHint(rootNode, listOf(
            "Numéro de mobile", "Mobile number", "Téléphone", "Phone number"
        ))
        if (phoneFields.isNotEmpty() && userData.phone.isNotEmpty()) {
            fillTextField(phoneFields.first(), userData.phone)
            clickNextButton(rootNode)
            return
        }

        val passwordFields = findFieldsByHint(rootNode, listOf("Mot de passe", "Password", "New password"))
        if (passwordFields.isNotEmpty() && userData.password.isNotEmpty()) {
            fillTextField(passwordFields.first(), userData.password)
            clickNextButton(rootNode)
            return
        }

        // Fallback : si aucun écran connu ne correspond, tente quand même
        // de cliquer sur un bouton "suivant" générique (utile pour les écrans
        // non explicitement gérés, comme la validation de la date de naissance).
        clickNextButton(rootNode)
    }

    // Clique sur le nœud, ou remonte vers le premier parent cliquable si le nœud
    // lui-même ne l'est pas (cas fréquent : le texte est dans un enfant non cliquable).
    private fun performClick(node: AccessibilityNodeInfo) {
        var courant: AccessibilityNodeInfo? = node
        while (courant != null) {
            if (courant.isClickable) {
                courant.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            courant = courant.parent
        }
    }

    private fun fillTextField(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clickNextButton(rootNode: AccessibilityNodeInfo) {
        val nextButtons = findNodesByText(rootNode, listOf(
            "Suivant", "Next", "S'inscrire", "Continue", "Sign up", "Sign Up"
        ))
        if (nextButtons.isNotEmpty()) {
            performClick(nextButtons.first())
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

    // Recherche récursive de la molette (NumberPicker) affichant l'année dans
    // le sélecteur de date — reconnue au fait que son texte, ou celui de l'un
    // de ses enfants, est une suite de 4 chiffres (ex: "2026").
    private fun findYearPicker(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className == "android.widget.NumberPicker") {
            val texteNoeud = node.text?.toString()?.trim() ?: ""
            if (Regex("^\\d{4}$").matches(texteNoeud)) return node
            for (i in 0 until node.childCount) {
                val texteEnfant = node.getChild(i)?.text?.toString()?.trim() ?: ""
                if (Regex("^\\d{4}$").matches(texteEnfant)) return node
            }
        }
        for (i in 0 until node.childCount) {
            val trouve = findYearPicker(node.getChild(i))
            if (trouve != null) return trouve
        }
        return null
    }

    // Recherche récursive du premier bouton radio dans l'arborescence —
    // utilisé pour les écrans de choix (ex: sélection d'un nom suggéré),
    // où le bon élément ne peut pas être identifié par son texte puisqu'il
    // varie à chaque tentative (variantes générées dynamiquement par Facebook).
    private fun findFirstRadioButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className == "android.widget.RadioButton") return node
        for (i in 0 until node.childCount) {
            val trouve = findFirstRadioButton(node.getChild(i))
            if (trouve != null) return trouve
        }
        return null
    }
}

