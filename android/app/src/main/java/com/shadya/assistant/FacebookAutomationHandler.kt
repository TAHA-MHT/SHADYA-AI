package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FacebookAutomationHandler(private val service: AccessibilityService) {

    var userData: UserAccountData = UserAccountData()
    var mode: String = "signup"

    // Année cible en cours de calcul pour l'écran "date de naissance" —
    // conservée entre plusieurs appels successifs (un défilement par appel),
    // et réinitialisée une fois la date validée.
    private var anneeCibleEnCours: Int? = null

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
            val anneeActuelle = lireAnneeAffichee()
            if (anneeActuelle == null) {
                // Impossible de lire l'année affichée : on valide telle
                // quelle plutôt que de bloquer indéfiniment sur cet écran.
                performClick(setDateButtons.first())
                return
            }

            if (anneeCibleEnCours == null) {
                val ageVoulu = userData.age.toIntOrNull() ?: 20
                anneeCibleEnCours = anneeActuelle - ageVoulu
            }

            if (anneeActuelle <= anneeCibleEnCours!!) {
                anneeCibleEnCours = null
                performClick(setDateButtons.first())
                return
            }

            // Un seul geste de glissement par appel : on laisse l'interface
            // se rafraîchir avant de relire la valeur au prochain événement
            // d'accessibilité. L'action de défilement standard s'est avérée
            // trop grossière sur ce composant (elle saute des dizaines
            // d'années par appel, quel que soit le nombre d'appels) ; on
            // simule donc un vrai geste de glissement du doigt, calibré sur
            // l'espacement réel entre deux années visibles à l'écran, pour
            // avancer d'exactement une unité à la fois.
            swipeUneAnnee(rootNode, versLePasse = true)
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

    // Recherche récursive d'un nœud dont le texte affiché correspond à une
    // année plausible (1900-2099). Approche volontairement générique : elle
    // ne suppose aucun type de composant précis (ex: NumberPicker), car
    // certaines applications (dont Facebook) utilisent leur propre sélecteur
    // de date personnalisé plutôt que le composant standard Android.
    private fun findNodeAvecAnnee(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val texte = node.text?.toString()?.trim() ?: ""
        if (Regex("^(19|20)\\d{2}$").matches(texte)) return node
        for (i in 0 until node.childCount) {
            val trouve = findNodeAvecAnnee(node.getChild(i))
            if (trouve != null) return trouve
        }
        return null
    }

    // Recherche récursive de TOUS les nœuds affichant une année plausible
    // (utilisé pour repérer simultanément l'année du dessus, celle du
    // milieu et celle du dessous, afin de mesurer l'espacement réel entre
    // deux rangées consécutives du sélecteur).
    private fun findToutesLesAnnees(node: AccessibilityNodeInfo?, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val texte = node.text?.toString()?.trim() ?: ""
        if (Regex("^(19|20)\\d{2}$").matches(texte)) resultat.add(node)
        for (i in 0 until node.childCount) {
            findToutesLesAnnees(node.getChild(i), resultat)
        }
    }

    // Simule un glissement du doigt d'exactement une rangée (une année),
    // calibré sur l'espacement réel mesuré entre les années actuellement
    // visibles à l'écran plutôt que sur une distance fixe supposée.
    private fun swipeUneAnnee(rootNode: AccessibilityNodeInfo, versLePasse: Boolean): Boolean {
        val noeudsAnnees = mutableListOf<AccessibilityNodeInfo>()
        findToutesLesAnnees(rootNode, noeudsAnnees)
        if (noeudsAnnees.isEmpty()) return false

        val zonesEcran = noeudsAnnees.map { noeud ->
            Rect().also { noeud.getBoundsInScreen(it) }
        }
        val centresY = zonesEcran.map { it.centerY() }.sorted()

        // Espacement moyen entre deux rangées consécutives (ex : l'année du
        // dessus et l'année du milieu). À défaut de pouvoir le mesurer (une
        // seule année détectée), on retombe sur une valeur approximative.
        val ecarts = centresY.zipWithNext { a, b -> b - a }
        val ecartMoyen = if (ecarts.isNotEmpty()) ecarts.average().toInt() else 70

        val centerX = zonesEcran.first().centerX().toFloat()
        val centerY = zonesEcran.first().centerY().toFloat()
        val yDepart = centerY
        val yArrivee = if (versLePasse) centerY + ecartMoyen else centerY - ecartMoyen

        val chemin = Path().apply {
            moveTo(centerX, yDepart)
            lineTo(centerX, yArrivee)
        }
        val geste = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(chemin, 0, 120))
            .build()

        return service.dispatchGesture(geste, null, null)
    }

    // Lit l'année actuellement affichée en repartant de la fenêtre active à
    // chaque appel (plutôt que de garder une référence de nœud, susceptible
    // de devenir invalide après un défilement).
    private fun lireAnneeAffichee(): Int? {
        val racine = service.rootInActiveWindow ?: return null
        val noeud = findNodeAvecAnnee(racine) ?: return null
        return noeud.text?.toString()?.trim()?.toIntOrNull()
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
