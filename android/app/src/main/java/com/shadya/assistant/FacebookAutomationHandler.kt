package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar

class FacebookAutomationHandler(private val service: AccessibilityService) {

    var userData: UserAccountData = UserAccountData()
    var mode: String = "signup"

    // Année cible en cours de calcul pour l'écran "date de naissance" —
    // conservée entre plusieurs appels successifs (un défilement par appel),
    // et réinitialisée une fois la date validée.
    private var anneeCibleEnCours: Int? = null

    // Sécurité supplémentaire : nombre de gestes déjà effectués pour la
    // session en cours. Même avec un calibrage imparfait (risque de sauter
    // plus d'une année par geste sur certains appareils), ce compteur
    // garantit qu'on ne reste jamais bloqué indéfiniment sur cet écran —
    // au-delà d'un certain nombre de tentatives, on valide tel quel plutôt
    // que de continuer à essayer sans fin.
    private var nombreSwipesEffectues: Int = 0

    // Verrou temporel : Android envoie souvent plusieurs événements
    // d'accessibilité très rapprochés pour un seul changement d'écran. Sans
    // ce verrou, chacun de ces événements déclenchait son propre geste de
    // glissement avant que l'écran précédent n'ait eu le temps de se
    // rafraîchir, provoquant un saut de plusieurs dizaines d'années d'un
    // coup. Le verrou garantit qu'un seul geste part à la fois, avec une
    // pause pour laisser l'affichage se stabiliser avant le suivant.
    private var ajustementEnCours = false
    private val handlerAnnee = Handler(Looper.getMainLooper())

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
        // Détection de l'écran "date de naissance" faite en tout premier,
        // pour pouvoir réinitialiser la cible d'année mémorisée dès qu'on
        // n'est PAS sur cet écran. Sans cette réinitialisation, une cible
        // calculée lors d'un essai précédent resté inachevé (abandon en
        // cours de route, redémarrage du parcours) restait figée en mémoire
        // et polluait tous les essais suivants avec une valeur erronée,
        // expliquant les atterrissages répétés sur la même année incorrecte
        // malgré les corrections successives du mécanisme de défilement.
        val setDateButtons = findNodesByText(rootNode, listOf("SET"))
        val cancelButtons = findNodesByText(rootNode, listOf("CANCEL"))
        val estEcranDateNaissance = setDateButtons.isNotEmpty() && cancelButtons.isNotEmpty()
        if (!estEcranDateNaissance) {
            anneeCibleEnCours = null
            nombreSwipesEffectues = 0
        }

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
        if (estEcranDateNaissance) {
            // Si un geste précédent est encore "en pause de stabilisation",
            // on ignore cet événement plutôt que d'en déclencher un autre
            // par-dessus — c'est le cœur de la correction du saut massif.
            if (ajustementEnCours) return

            val anneeActuelle = lireAnneeAffichee()
            journaliser("Lecture année=$anneeActuelle, cibleActuelle=$anneeCibleEnCours")

            if (anneeActuelle == null) {
                journaliser("Année illisible → clic SET par défaut")
                performClick(setDateButtons.first())
                return
            }

            if (anneeCibleEnCours == null) {
                // On utilise l'année réelle du système (indépendante de ce
                // que Facebook affiche) plutôt que la valeur lue à l'écran :
                // après un refus de Facebook (âge jugé invalide), la fenêtre
                // se rouvre parfois sans revenir à la date du jour, gardant
                // l'ancienne valeur rejetée comme point de départ — se baser
                // sur cet affichage aurait alors faussé tout le calcul.
                val anneeReelle = Calendar.getInstance().get(Calendar.YEAR)
                val ageVoulu = userData.age.toIntOrNull() ?: 20
                anneeCibleEnCours = anneeReelle - ageVoulu
                journaliser("CIBLE CALCULÉE = $anneeCibleEnCours (année système réelle=$anneeReelle, age=$ageVoulu, userData.age brut=\"${userData.age}\")")
            }

            val difference = anneeActuelle - anneeCibleEnCours!!
            if (difference == 0) {
                journaliser("Cible atteinte exactement ($anneeActuelle) → clic SET")
                anneeCibleEnCours = null
                nombreSwipesEffectues = 0
                performClick(setDateButtons.first())
                return
            }

            if (nombreSwipesEffectues >= 80) {
                journaliser("Sécurité: trop de tentatives ($nombreSwipesEffectues), on valide tel quel")
                anneeCibleEnCours = null
                nombreSwipesEffectues = 0
                performClick(setDateButtons.first())
                return
            }

            // Un seul geste de glissement par appel : on laisse l'interface
            // se rafraîchir avant de relire la valeur au prochain événement
            // d'accessibilité. Le geste peut désormais avancer dans les deux
            // sens (vers le passé ou vers le présent), au cas où la valeur
            // de départ serait déjà, après un rejet de Facebook, plus
            // ancienne que la cible visée.
            val versLePasse = difference > 0
            val anneesParGeste = kotlin.math.abs(difference).coerceAtMost(10)

            ajustementEnCours = true
            nombreSwipesEffectues++
            swipeAnnees(rootNode, anneesParGeste, versLePasse = versLePasse)
            handlerAnnee.postDelayed({ ajustementEnCours = false }, 350L + anneesParGeste * 350L)
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
        // de cliquer sur un bouton "suivant" générique — mais UNIQUEMENT si
        // la fenêtre active appartient réellement à Facebook. Sans cette
        // vérification, un événement système déclenché pendant que le flux
        // reste actif par erreur (ex: en quittant une autre application)
        // pouvait faire cliquer ce filet de sécurité sur un bouton "Next"/
        // "Continue" d'une app totalement différente, avec le risque de
        // ramener Facebook au premier plan de façon inattendue.
        val packageActif = rootNode.packageName?.toString() ?: ""
        if (packageActif == "com.facebook.katana" || packageActif == "com.facebook.lite") {
            clickNextButton(rootNode)
        }
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
    // année plausible (1900-2099), en ignorant tout nœud qui n'est pas
    // réellement visible à l'écran (bornes de largeur/hauteur nulles) —
    // ce type de composant (liste à défilement recyclée) conserve souvent
    // des éléments hors écran en mémoire technique, avec un texte resté
    // figé sur une ancienne valeur, ce qui faussait la lecture.
    private fun findNodeAvecAnnee(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val texte = node.text?.toString()?.trim() ?: ""
        if (Regex("^(19|20)\\d{2}$").matches(texte) && estReellementVisible(node)) return node
        for (i in 0 until node.childCount) {
            val trouve = findNodeAvecAnnee(node.getChild(i))
            if (trouve != null) return trouve
        }
        return null
    }

    // Recherche récursive de TOUS les nœuds affichant une année plausible et
    // réellement visible à l'écran (voir remarque ci-dessus) — utilisé pour
    // repérer simultanément l'année du dessus, celle du milieu et celle du
    // dessous, afin de mesurer l'espacement réel entre deux rangées.
    private fun findToutesLesAnnees(node: AccessibilityNodeInfo?, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val texte = node.text?.toString()?.trim() ?: ""
        if (Regex("^(19|20)\\d{2}$").matches(texte) && estReellementVisible(node)) resultat.add(node)
        for (i in 0 until node.childCount) {
            findToutesLesAnnees(node.getChild(i), resultat)
        }
    }

    // Vérifie qu'un nœud occupe réellement une zone visible à l'écran
    // (largeur et hauteur non nulles), pour écarter les éléments hors écran
    // ou recyclés en mémoire par les listes à défilement.
    private fun estReellementVisible(node: AccessibilityNodeInfo): Boolean {
        val zone = Rect()
        node.getBoundsInScreen(zone)
        return zone.width() > 0 && zone.height() > 0
    }

    // Simule un glissement du doigt couvrant plusieurs rangées (années) en
    // un seul geste continu, calibré sur l'espacement réel mesuré entre les
    // années actuellement visibles à l'écran, avec une durée proportionnelle
    // au nombre d'années parcourues pour conserver une vitesse mesurée et
    // éviter l'effet d'élan ("fling") qui faisait sauter plus d'une rangée
    // par geste lorsque celui-ci était trop rapide.
    private fun swipeAnnees(rootNode: AccessibilityNodeInfo, nombreAnnees: Int, versLePasse: Boolean): Boolean {
        val noeudsAnnees = mutableListOf<AccessibilityNodeInfo>()
        findToutesLesAnnees(rootNode, noeudsAnnees)
        journaliser("Swipe: ${noeudsAnnees.size} nœud(s) année trouvé(s), textes=${noeudsAnnees.map { it.text }}, nombreAnnees=$nombreAnnees")
        if (noeudsAnnees.isEmpty()) {
            journaliser("Swipe annulé: aucun nœud année visible trouvé")
            return false
        }

        val zonesEcran = noeudsAnnees.map { noeud ->
            Rect().also { noeud.getBoundsInScreen(it) }
        }
        journaliser("Swipe: bornes écran=${zonesEcran.map { "(${it.left},${it.top},${it.right},${it.bottom})" }}")
        val centresY = zonesEcran.map { it.centerY() }.sorted()

        // Espacement moyen entre deux rangées consécutives (ex : l'année du
        // dessus et l'année du milieu). À défaut de pouvoir le mesurer (une
        // seule année détectée), on retombe sur une valeur approximative.
        val ecarts = centresY.zipWithNext { a, b -> b - a }
        val ecartMoyen = if (ecarts.isNotEmpty()) ecarts.average().toInt() else 70

        // Correction empirique : les mesures réelles (journal de diagnostic)
        // montrent qu'un glissement calibré sur l'espacement mesuré entre
        // deux rangées fait systématiquement avancer de deux années au lieu
        // d'une seule, quelle que soit la vitesse du geste. On divise donc
        // la distance par deux pour obtenir le déplacement d'une seule année.
        val distanceParAnnee = ecartMoyen / 2
        val distanceTotale = distanceParAnnee * nombreAnnees
        val dureeGeste = (350L * nombreAnnees).coerceAtMost(4000L)

        val centerX = zonesEcran.first().centerX().toFloat()
        val centerY = zonesEcran.first().centerY().toFloat()
        val yDepart = centerY
        val yArrivee = if (versLePasse) centerY + distanceTotale else centerY - distanceTotale
        journaliser("Swipe: ecartMoyen=$ecartMoyen, distanceTotale=$distanceTotale, dureeGeste=$dureeGeste, centerX=$centerX, yDepart=$yDepart, yArrivee=$yArrivee")

        val chemin = Path().apply {
            moveTo(centerX, yDepart)
            lineTo(centerX, yArrivee)
        }
        val geste = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(chemin, 0, dureeGeste))
            .build()

        val resultat = service.dispatchGesture(geste, null, null)
        journaliser("Swipe: dispatchGesture a retourné $resultat")
        return resultat
    }

    // Écrit une ligne horodatée dans le même fichier crash_log.txt que celui
    // déjà utilisé côté Dart (accessible via l'appui long sur le titre de
    // l'app) — permet de voir les vraies valeurs lues et calculées à chaque
    // étape, au lieu de deviner à partir du seul résultat visuel final.
    private fun journaliser(message: String) {
        try {
            val fichier = java.io.File(service.filesDir, "crash_log.txt")
            val horodatage = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.FRANCE).format(java.util.Date())
            fichier.appendText("[KOTLIN $horodatage] $message\n")
        } catch (e: Exception) {
            // Le journal est un outil de diagnostic, pas critique au flux.
        }
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
