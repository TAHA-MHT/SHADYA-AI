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

        // Garde-fou : n'agit que si la fenêtre active appartient réellement
        // à WhatsApp. Sans cette vérification, un événement système déclenché
        // à un autre moment (par exemple en fermant l'application) pouvait
        // faire agir ce code sur une fenêtre totalement différente.
        val packageActif = rootNode.packageName?.toString() ?: ""
        if (packageActif != "com.whatsapp" && packageActif != "com.whatsapp.w4b") {
            return
        }

        // 1. Clic automatique sur "Accepter et continuer"
        val agreeButtons = findNodesByText(rootNode, listOf("Accepter et continuer", "Agree and continue", "AGREE AND CONTINUE"))
        if (agreeButtons.isNotEmpty()) {
            journaliser("WHATSAPP: écran 'Agree and continue' détecté")
            performClick(agreeButtons.first())
            return
        }

        // 1bis. Écran "Use +XXX for WhatsApp Business?" — WhatsApp Business
        // détecte automatiquement le numéro de la carte SIM (celui déjà lié
        // au WhatsApp normal existant, le cas échéant) et propose de migrer
        // l'historique de discussions vers Business. RÈGLE DE SÉCURITÉ :
        // ne JAMAIS cliquer sur le bouton contenant ce numéro pré-rempli
        // (texte "Use +...") car cela fusionnerait/migrerait le compte
        // WhatsApp existant de l'utilisateur, avec perte de son historique
        // de discussions. On clique systématiquement sur "Use a different
        // number" pour repartir sur un numéro dédié à WhatsApp Business.
        val boutonNumeroDifferent = findNodesByText(rootNode, listOf(
            "Use a different number", "Utiliser un autre numéro"
        )).firstOrNull { noeud ->
            val texte = noeud.text?.toString()?.trim()
            val description = noeud.contentDescription?.toString()?.trim()
            texte == "Use a different number" || description == "Use a different number" ||
                texte == "Utiliser un autre numéro" || description == "Utiliser un autre numéro"
        }
        if (boutonNumeroDifferent != null) {
            journaliser("WHATSAPP: écran de migration de numéro détecté → clic sur 'Use a different number' (jamais sur le numéro pré-rempli, pour éviter toute fusion avec un compte existant)")
            performClick(boutonNumeroDifferent)
            return
        }

        // 2. Remplissage automatique du numéro de téléphone
        val phoneFields = findFieldsByHint(rootNode, listOf("numéro de téléphone", "phone number", "Phone number"))
        val numeroAUtiliser = userData.phone.ifEmpty { getPhoneNumberFromSim() }
        if (phoneFields.isNotEmpty() && numeroAUtiliser.isNotEmpty()) {
            journaliser("WHATSAPP: champ numéro détecté, remplissage")
            fillTextField(phoneFields.first(), numeroAUtiliser)
            clickNextButton(rootNode)
            return
        }

        // 3. Validation automatique des boîtes de dialogue "OK" / "Oui"
        // — retiré : ce texte étant très courant, il pouvait correspondre
        // par coïncidence à un écran transitoire sans rapport (par exemple
        // en fermant WhatsApp via un double appui sur Retour), déclenchant
        // un clic non désiré à ce moment précis. Si ce type de dialogue doit
        // être géré à nouveau, prévoir une condition plus spécifique (ex :
        // présence simultanée d'un second élément propre à l'écran attendu).

        // 4. Code de vérification SMS — rempli automatiquement par SmsCodeReceiver
        // (voir ShadyaAgentService.pendingOtpCode), on se contente ici de le saisir si présent
        val codeFields = findFieldsByHint(rootNode, listOf("code de vérification", "verification code", "Code"))
        if (codeFields.isNotEmpty() && ShadyaAgentService.pendingOtpCode.isNotEmpty()) {
            journaliser("WHATSAPP: champ code détecté, remplissage avec le code en mémoire")
            fillTextField(codeFields.first(), ShadyaAgentService.pendingOtpCode)
            ShadyaAgentService.pendingOtpCode = ""
            return
        }

        // 5. Remplissage du nom de profil (première configuration du compte)
        val nameFields = findFieldsByHint(rootNode, listOf("Votre nom", "Your name", "Nom"))
        if (nameFields.isNotEmpty() && userData.firstName.isNotEmpty()) {
            journaliser("WHATSAPP: champ nom de profil détecté, remplissage")
            val nomComplet = if (userData.lastName.isNotEmpty()) "${userData.firstName} ${userData.lastName}" else userData.firstName
            fillTextField(nameFields.first(), nomComplet)
            clickNextButton(rootNode)
            return
        }

        // DIAGNOSTIC : aucun des blocs ci-dessus n'a reconnu l'écran actuel.
        // Contrairement à FacebookAutomationHandler, ce gestionnaire n'a
        // pas de filet de sécurité générique (pas de clic automatique sur
        // "Next" en dernier recours) — un écran non reconnu ici reste donc
        // silencieux, sans laisser aucune trace exploitable. On journalise
        // désormais tout le contenu textuel de l'écran dans ce cas, une
        // seule fois par écran réellement différent, pour ne plus jamais
        // rester aveugle face à un blocage.
        journaliserContenuEcranSiNouveau(rootNode)
    }

    // Appelée directement par ShadyaAgentService dès qu'un code de
    // vérification à 5 chiffres est détecté dans une notification SMS ou
    // WhatsApp — permet de remplir le champ immédiatement, sans attendre le
    // prochain événement d'accessibilité naturel. Symétrique de la méthode
    // équivalente dans FacebookAutomationHandler, aiguillée correctement
    // via ShadyaAgentService.pendingFlowTarget.
    fun tenterRemplirCodeConfirmation(code: String) {
        val rootNode = service.rootInActiveWindow ?: return
        val codeFields = findFieldsByHint(rootNode, listOf("code de vérification", "verification code", "Code"))
        if (codeFields.isNotEmpty()) {
            journaliser("WHATSAPP: code reçu par notification ($code) → remplissage immédiat du champ")
            fillTextField(codeFields.first(), code)
            ShadyaAgentService.pendingOtpCode = ""
        } else {
            journaliser("WHATSAPP: code reçu ($code) mais champ introuvable à cet instant — mémorisé pour le prochain écran")
        }
    }

    private fun fillTextField(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    // Détecte la présence d'un indicateur de chargement visible n'importe où
    // à l'écran. Même logique que dans FacebookAutomationHandler : suspendre
    // tout clic automatique tant qu'une requête réseau est déjà en cours,
    // pour éviter de soumettre plusieurs fois la même action (numéro,
    // code...) pendant qu'une réponse serveur est encore en attente.
    private fun estEnChargement(rootNode: AccessibilityNodeInfo): Boolean {
        val libellesChargement = listOf("Loading", "Loading...", "Loading…", "Chargement")
        return findNodesByText(rootNode, libellesChargement).any { noeud ->
            val texte = noeud.text?.toString()?.trim()
            val description = noeud.contentDescription?.toString()?.trim()
            libellesChargement.any { it.equals(texte, ignoreCase = true) || it.equals(description, ignoreCase = true) }
        }
    }

    private var chargementDejaSignale = false

    private fun clickNextButton(rootNode: AccessibilityNodeInfo) {
        if (estEnChargement(rootNode)) {
            if (!chargementDejaSignale) {
                journaliser("WHATSAPP CHARGEMENT: indicateur détecté → clic suspendu, attente de la réponse serveur")
                chargementDejaSignale = true
            }
            return
        }
        chargementDejaSignale = false

        val libellesRecherches = listOf("Suivant", "Next", "SUIVANT")
        val candidats = findNodesByText(rootNode, libellesRecherches)
        val boutonExact = candidats.firstOrNull { noeud ->
            val texte = noeud.text?.toString()?.trim()
            val description = noeud.contentDescription?.toString()?.trim()
            libellesRecherches.any { it.equals(texte, ignoreCase = true) || it.equals(description, ignoreCase = true) }
        }
        if (boutonExact != null) {
            performClick(boutonExact)
        }
    }

    // Clique sur le nœud, ou remonte vers le premier ancêtre réellement
    // cliquable si le nœud lui-même ne l'est pas. Vérifie à la fois le
    // drapeau isClickable ET la présence effective de l'action ACTION_CLICK
    // dans la liste des actions supportées — même correction que celle
    // appliquée dans FacebookAutomationHandler après avoir découvert que
    // certains boutons n'exposent pas isClickable=true tout en supportant
    // réellement le clic, ce qui faisait échouer silencieusement l'action
    // (aucune erreur, mais aucun effet non plus).
    private fun performClick(node: AccessibilityNodeInfo) {
        var courant: AccessibilityNodeInfo? = node
        while (courant != null) {
            val supporteClic = courant.isClickable ||
                courant.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (supporteClic) {
                val reussi = courant.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                journaliser("WHATSAPP performClick sur classe=${courant.className}, texte=\"${courant.text}\", desc=\"${courant.contentDescription}\": réussi=$reussi")
                if (reussi) return
            }
            courant = courant.parent
        }
        journaliser("WHATSAPP performClick: aucun ancêtre cliquable trouvé pour \"${node.text}\"")
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

    // Écrit une ligne horodatée dans le même fichier crash_log.txt que celui
    // déjà utilisé par FacebookAutomationHandler (accessible via l'appui
    // long sur le titre "Shadya AI") — ce fichier n'avait jusqu'ici AUCUNE
    // journalisation, rendant tout diagnostic impossible en cas de blocage.
    private fun journaliser(message: String) {
        try {
            val fichier = java.io.File(service.filesDir, "crash_log.txt")
            val horodatage = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.FRANCE).format(java.util.Date())
            fichier.appendText("[KOTLIN $horodatage] $message\n")
        } catch (e: Exception) {
            // Le journal est un outil de diagnostic, pas critique au flux.
        }
    }

    // Empreinte du dernier écran dont le contenu complet a été journalisé —
    // évite de ré-écrire le même dump à chaque événement d'accessibilité
    // reçu sur un même écran figé.
    private var derniereEmpreinteEcranJournalisee: Int? = null

    private fun journaliserContenuEcranSiNouveau(rootNode: AccessibilityNodeInfo) {
        val lignes = mutableListOf<String>()
        collecterContenuTexte(rootNode, lignes)
        val empreinte = lignes.joinToString("|").hashCode()
        if (empreinte == derniereEmpreinteEcranJournalisee) return
        derniereEmpreinteEcranJournalisee = empreinte

        journaliser("=== WHATSAPP DUMP ÉCRAN NON RECONNU (${lignes.size} nœud(s) avec texte) ===")
        for (ligne in lignes) {
            journaliser(ligne)
        }
        journaliser("=== FIN DUMP ===")
    }

    private fun collecterContenuTexte(node: AccessibilityNodeInfo?, resultat: MutableList<String>) {
        if (node == null) return
        val texte = node.text?.toString()
        val description = node.contentDescription?.toString()
        if (!texte.isNullOrBlank() || !description.isNullOrBlank()) {
            val zone = android.graphics.Rect()
            node.getBoundsInScreen(zone)
            resultat.add("classe=${node.className}, clicable=${node.isClickable}, checkable=${node.isCheckable}, checked=${node.isChecked}, texte=\"$texte\", desc=\"$description\", bornes=(${zone.left},${zone.top},${zone.right},${zone.bottom})")
        }
        for (i in 0 until node.childCount) {
            collecterContenuTexte(node.getChild(i), resultat)
        }
    }
}
