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
        // à WhatsApp — OU à des dialogues système pertinents ("android" pour
        // les popups système classiques comme l'autorisation de
        // notifications, "com.google.android.gms" pour le sélecteur de
        // numéro de téléphone proposé par Google Play Services). Sans cet
        // élargissement, un événement système déclenché à un autre moment
        // (par exemple en fermant l'application) pouvait faire agir ce code
        // sur une fenêtre totalement différente — d'où les vérifications
        // supplémentaires ci-dessous, ciblées sur des textes exacts attendus.
        val packageActif = rootNode.packageName?.toString() ?: ""
        if (packageActif != "com.whatsapp" && packageActif != "com.whatsapp.w4b" &&
            packageActif != "android" && packageActif != "com.google.android.gms") {
            return
        }

        // -1. Popup Google Play Services "Choose a phone number" — propose
        // le numéro associé à la carte SIM, qui peut être DIFFÉRENT du
        // numéro dicté par l'utilisateur et destiné à WhatsApp Business. On
        // ne sélectionne JAMAIS ce numéro suggéré (risque de créer le compte
        // avec le mauvais numéro) : on ferme systématiquement la popup via
        // son bouton de fermeture, pour laisser le numéro dicté être saisi
        // normalement dans le champ WhatsApp juste en dessous.
        val estPopupChoixNumeroGoogle = findNodesByText(rootNode, listOf("Choose a phone number", "Choisir un numéro de téléphone")).isNotEmpty()
        if (estPopupChoixNumeroGoogle) {
            val boutonFermer = findNodesByText(rootNode, listOf("Close", "Fermer")).firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Close".equals(texte, ignoreCase = true) || "Close".equals(description, ignoreCase = true) ||
                    "Fermer".equals(texte, ignoreCase = true) || "Fermer".equals(description, ignoreCase = true)
            }
            if (boutonFermer != null) {
                journaliser("WHATSAPP: popup Google 'Choose a phone number' détectée → fermeture (jamais de sélection du numéro suggéré)")
                performClick(boutonFermer)
            } else {
                journaliser("WHATSAPP: popup Google 'Choose a phone number' détectée mais bouton de fermeture introuvable")
            }
            return
        }

        // 0. Permission système "Allow WhatsApp Business to send you
        // notifications?" — cruciale pour que le mécanisme de capture du
        // code de vérification par notification (voir ShadyaAgentService)
        // fonctionne : sans cette autorisation, les notifications
        // n'exposeraient aucun texte exploitable. Combinaison du bouton
        // "Allow" avec la présence du mot "notifications" à l'écran, pour
        // éviter tout clic hasardeux sur un dialogue système sans rapport.
        val demandeNotifications = findNodesByText(rootNode, listOf("notifications")).isNotEmpty()
        if (demandeNotifications) {
            val boutonAutoriser = findNodesByText(rootNode, listOf("Allow", "Autoriser")).firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Allow".equals(texte, ignoreCase = true) || "Allow".equals(description, ignoreCase = true) ||
                    "Autoriser".equals(texte, ignoreCase = true) || "Autoriser".equals(description, ignoreCase = true)
            }
            if (boutonAutoriser != null) {
                journaliser("WHATSAPP: demande d'autorisation des notifications détectée → clic sur Allow")
                performClick(boutonAutoriser)
                return
            }
        }

        // Après ce point, on ne veut plus agir que sur les écrans propres à
        // WhatsApp lui-même (pas sur d'autres dialogues système sans rapport).
        if (packageActif == "android" || packageActif == "com.google.android.gms") {
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
            "Use a different number".equals(texte, ignoreCase = true) ||
                "Use a different number".equals(description, ignoreCase = true) ||
                "Utiliser un autre numéro".equals(texte, ignoreCase = true) ||
                "Utiliser un autre numéro".equals(description, ignoreCase = true)
        }
        if (boutonNumeroDifferent != null) {
            journaliser("WHATSAPP: écran de migration de numéro détecté → clic sur 'Use a different number' (jamais sur le numéro pré-rempli, pour éviter toute fusion avec un compte existant)")
            performClick(boutonNumeroDifferent)
            return
        }

        // 2. Remplissage automatique du numéro de téléphone
        val phoneFields = findFieldsByHint(rootNode, listOf("numéro de téléphone", "phone number", "Phone number"))
        val numeroAUtiliser = userData.phone.ifEmpty { getPhoneNumberFromSim() }
        // DIAGNOSTIC : le remplissage échouait silencieusement sur l'écran
        // "Enter your phone number" malgré un champ apparemment détectable
        // (visible dans le dump général) — cette ligne isole précisément
        // lequel des deux prérequis (champ trouvé / numéro disponible)
        // fait défaut, plutôt que de continuer à deviner à l'aveugle.
        journaliser("WHATSAPP DIAGNOSTIC téléphone: phoneFields.size=${phoneFields.size}, userData.phone=\"${userData.phone}\", numeroAUtiliser=\"$numeroAUtiliser\"")
        if (phoneFields.isNotEmpty() && numeroAUtiliser.isNotEmpty()) {
            journaliser("WHATSAPP: champ numéro détecté, remplissage")
            fillTextField(phoneFields.first(), numeroAUtiliser)
            clickNextButton(rootNode)
            return
        }

        // 2bis. Popup de confirmation "Is this the correct number?" —
        // affichée par WhatsApp juste après la saisie, avec le numéro
        // reformaté (ex: "+235 61 49 48 49"). On clique sur "Yes" pour
        // confirmer, jamais sur "Edit" (qui rouvrirait la saisie manuelle).
        val boutonOuiNumero = findNodesByText(rootNode, listOf("Yes", "Oui")).firstOrNull { noeud ->
            val texte = noeud.text?.toString()?.trim()
            val description = noeud.contentDescription?.toString()?.trim()
            "Yes".equals(texte, ignoreCase = true) || "Yes".equals(description, ignoreCase = true) ||
                "Oui".equals(texte, ignoreCase = true) || "Oui".equals(description, ignoreCase = true)
        }
        val estConfirmationNumero = findNodesByText(rootNode, listOf("Is this the correct number", "correct number")).isNotEmpty()
        if (estConfirmationNumero && boutonOuiNumero != null) {
            journaliser("WHATSAPP: confirmation du numéro détectée → clic sur Yes")
            performClick(boutonOuiNumero)
            return
        }

        // 2ter. Écran "Choose how to verify" — WhatsApp propose "Missed
        // call" (sélectionné par défaut), "Receive SMS" et "Voice call".
        // Vérifié AVANT le bloc suivant (détection de l'écran d'appel
        // manqué) car cet écran contient aussi le mot "missed call" dans le
        // libellé de l'option, ce qui déclencherait sinon le mauvais bloc.
        // On sélectionne systématiquement "Receive SMS" (jamais Missed call
        // ni Voice call), pour rester cohérent avec le mécanisme de capture
        // du code déjà en place, qui lit le texte des notifications
        // SMS/WhatsApp — vérifié via son état coché avant de cliquer sur
        // Continue, même principe que la sélection du genre sur Facebook.
        val estEcranChoixVerification = findNodesByText(rootNode, listOf("Choose how to verify")).isNotEmpty()
        if (estEcranChoixVerification) {
            val optionsCheckables = mutableListOf<AccessibilityNodeInfo>()
            trouverToutesLesOptionsCheckables(rootNode, optionsCheckables)
            val optionSms = optionsCheckables.firstOrNull { texteEtDescription(it).contains("Receive SMS", ignoreCase = true) }

            if (optionSms != null) {
                if (optionSms.isChecked) {
                    val boutonContinuer = findNodesByText(rootNode, listOf("Continue", "Continuer")).firstOrNull { noeud ->
                        val texte = noeud.text?.toString()?.trim()
                        val description = noeud.contentDescription?.toString()?.trim()
                        "Continue".equals(texte, ignoreCase = true) || "Continue".equals(description, ignoreCase = true) ||
                            "Continuer".equals(texte, ignoreCase = true) || "Continuer".equals(description, ignoreCase = true)
                    }
                    if (boutonContinuer != null) {
                        journaliser("WHATSAPP: option 'Receive SMS' déjà sélectionnée → clic sur Continue")
                        performClick(boutonContinuer)
                    }
                } else {
                    journaliser("WHATSAPP: écran de choix de vérification détecté → sélection de 'Receive SMS' (checkable direct)")
                    performClick(optionSms)
                }
            } else {
                journaliser("WHATSAPP: écran de choix de vérification détecté mais option 'Receive SMS' introuvable — ${optionsCheckables.size} élément(s) checkable trouvé(s) au total:")
                for (option in optionsCheckables) {
                    journaliser("  checkable: classe=${option.className}, checked=${option.isChecked}, texte=\"${option.text}\", desc=\"${option.contentDescription}\"")
                }
            }
            return
        }

        // 2quater. Écran "To automatically verify with a missed call to your
        // phone" — WhatsApp propose une vérification par appel manqué
        // automatique en alternative au code par SMS/WhatsApp. On ne
        // l'utilise PAS : elle demanderait des permissions supplémentaires
        // (gestion d'appel, journal d'appels) et ne produit aucun code à
        // saisir, incompatible avec le mécanisme de capture déjà en place.
        // On clique systématiquement sur "Verify another way" pour rester
        // sur la vérification par code, jamais sur "Continue".
        val estEcranAppelManque = findNodesByText(rootNode, listOf("missed call", "appel manqué")).isNotEmpty()
        if (estEcranAppelManque) {
            val boutonAutreMethode = findNodesByText(rootNode, listOf(
                "Verify another way", "Vérifier autrement"
            )).firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Verify another way".equals(texte, ignoreCase = true) || "Verify another way".equals(description, ignoreCase = true) ||
                    "Vérifier autrement".equals(texte, ignoreCase = true) || "Vérifier autrement".equals(description, ignoreCase = true)
            }
            if (boutonAutreMethode != null) {
                journaliser("WHATSAPP: écran de vérification par appel manqué détecté → clic sur 'Verify another way' (on reste sur la vérification par code)")
                performClick(boutonAutreMethode)
            } else {
                journaliser("WHATSAPP: écran de vérification par appel manqué détecté mais bouton 'Verify another way' introuvable")
            }
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

    // Le filtre isEditable() s'est révélé peu fiable sur certains champs
    // WhatsApp (observé en conditions réelles : un vrai champ "Phone number",
    // bien de classe android.widget.EditText, n'était pas reconnu comme
    // éditable et se retrouvait donc exclu, empêchant tout remplissage). On
    // accepte désormais aussi tout nœud de classe EditText, même si le
    // drapeau isEditable n'est pas positionné correctement par l'app.
    // Parcours manuel de l'arborescence, identique dans son principe à
    // celui utilisé par le diagnostic (collecterContenuTexte) — la méthode
    // native rootNode.findAccessibilityNodeInfosByText() s'est révélée
    // incapable de trouver un champ pourtant bien présent et confirmé par
    // le diagnostic (texte="Phone number", editable=true) : très
    // probablement parce que ce texte est un simple indice de saisie
    // (hint), qu'Android traite différemment selon la méthode de recherche
    // utilisée. Le parcours manuel, qui lit directement node.text sans
    // passer par cette API native, contourne ce problème.
    // Concatène le texte et la contentDescription d'un nœud — permet de
    // vérifier les deux propriétés en une seule fois. Même utilité que dans
    // FacebookAutomationHandler pour la détection du genre : certains
    // éléments checkable (boutons radio) portent leur libellé directement
    // sur eux-mêmes (souvent via contentDescription), sans qu'un texte
    // séparé en soit un descendant ou un ancêtre accessible.
    private fun texteEtDescription(node: AccessibilityNodeInfo): String {
        return ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).trim()
    }

    // Recherche récursive de TOUS les nœuds "checkable" (boutons radio,
    // cases à cocher) de l'arborescence — utilisé pour détecter directement
    // l'option voulue sans dépendre d'une remontée depuis un nœud texte
    // séparé, qui a échoué en conditions réelles sur l'écran "Choose how to
    // verify" de WhatsApp (le texte "Receive SMS" n'avait aucun ancêtre
    // checkable dans les 6 niveaux testés).
    private fun trouverToutesLesOptionsCheckables(node: AccessibilityNodeInfo?, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isCheckable) resultat.add(node)
        for (i in 0 until node.childCount) {
            trouverToutesLesOptionsCheckables(node.getChild(i), resultat)
        }
    }

    private fun rechercherManuel(node: AccessibilityNodeInfo?, motsCles: List<String>, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val texte = node.text?.toString() ?: ""
        val description = node.contentDescription?.toString() ?: ""
        if (motsCles.any { texte.contains(it, ignoreCase = true) || description.contains(it, ignoreCase = true) }) {
            resultat.add(node)
        }
        for (i in 0 until node.childCount) {
            rechercherManuel(node.getChild(i), motsCles, resultat)
        }
    }

    private fun findFieldsByHint(rootNode: AccessibilityNodeInfo, hints: List<String>): List<AccessibilityNodeInfo> {
        val trouves = mutableListOf<AccessibilityNodeInfo>()
        rechercherManuel(rootNode, hints, trouves)
        return trouves.filter { it.isEditable || it.className == "android.widget.EditText" }
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
            resultat.add("classe=${node.className}, clicable=${node.isClickable}, checkable=${node.isCheckable}, checked=${node.isChecked}, editable=${node.isEditable}, texte=\"$texte\", desc=\"$description\", bornes=(${zone.left},${zone.top},${zone.right},${zone.bottom})")
        }
        for (i in 0 until node.childCount) {
            collecterContenuTexte(node.getChild(i), resultat)
        }
    }
}
