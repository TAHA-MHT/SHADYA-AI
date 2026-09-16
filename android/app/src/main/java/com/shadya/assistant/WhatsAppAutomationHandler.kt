package com.shadya.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAutomationHandler(private val service: AccessibilityService) {

    var userData: UserAccountData = UserAccountData()

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

        val packageActif = rootNode.packageName?.toString() ?: ""
        if (packageActif != "com.whatsapp" && packageActif != "com.whatsapp.w4b" &&
            packageActif != "android" && packageActif != "com.google.android.gms" &&
            packageActif != "com.android.permissioncontroller" && packageActif != "com.samsung.android.permissioncontroller") {
            return
        }

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

        if (packageActif == "android" || packageActif == "com.google.android.gms" || packageActif == "com.android.permissioncontroller" || packageActif == "com.samsung.android.permissioncontroller") {
            return
        }

        val agreeButtons = findNodesByText(rootNode, listOf("Accepter et continuer", "Agree and continue", "AGREE AND CONTINUE"))
        if (agreeButtons.isNotEmpty()) {
            journaliser("WHATSAPP: écran 'Agree and continue' détecté")
            performClick(agreeButtons.first())
            return
        }

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

        val phoneFields = findFieldsByHint(rootNode, listOf("numéro de téléphone", "phone number", "Phone number"))
        val numeroAUtiliser = userData.phone.ifEmpty { getPhoneNumberFromSim() }
        journaliser("WHATSAPP DIAGNOSTIC téléphone: phoneFields.size=${phoneFields.size}, userData.phone=\"${userData.phone}\", numeroAUtiliser=\"$numeroAUtiliser\"")
        if (phoneFields.isNotEmpty() && numeroAUtiliser.isNotEmpty()) {
            journaliser("WHATSAPP: champ numéro détecté, remplissage")
            fillTextField(phoneFields.first(), numeroAUtiliser)
            clickNextButton(rootNode)
            return
        }

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

        val estEcranChoixVerification = findNodesByText(rootNode, listOf("Choose how to verify")).isNotEmpty()
        if (estEcranChoixVerification) {
            val optionsCheckables = mutableListOf<AccessibilityNodeInfo>()
            trouverToutesLesOptionsCheckables(rootNode, optionsCheckables)

            val texteReceiveSms = findNodesByText(rootNode, listOf("Receive SMS")).firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Receive SMS".equals(texte, ignoreCase = true) || "Receive SMS".equals(description, ignoreCase = true)
            }
            val optionSms = if (texteReceiveSms != null && optionsCheckables.isNotEmpty()) {
                val zoneTexte = android.graphics.Rect()
                texteReceiveSms.getBoundsInScreen(zoneTexte)
                val centreYTexte = (zoneTexte.top + zoneTexte.bottom) / 2
                optionsCheckables.minByOrNull { option ->
                    val zoneOption = android.graphics.Rect()
                    option.getBoundsInScreen(zoneOption)
                    val centreYOption = (zoneOption.top + zoneOption.bottom) / 2
                    kotlin.math.abs(centreYOption - centreYTexte)
                }
            } else {
                null
            }

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
                    val zone = android.graphics.Rect()
                    option.getBoundsInScreen(zone)
                    journaliser("  checkable: classe=${option.className}, checked=${option.isChecked}, texte=\"${option.text}\", desc=\"${option.contentDescription}\", bornes=(${zone.left},${zone.top},${zone.right},${zone.bottom})")
                }
            }
            return
        }

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

        val codeFields = findFieldsByHint(rootNode, listOf("code de vérification", "verification code", "Code"))
        if (codeFields.isNotEmpty() && ShadyaAgentService.pendingOtpCode.isNotEmpty()) {
            journaliser("WHATSAPP: champ code détecté, remplissage avec le code en mémoire")
            fillTextField(codeFields.first(), ShadyaAgentService.pendingOtpCode)
            ShadyaAgentService.pendingOtpCode = ""
            return
        }

        val estEcranVerified = texteVisibleContient(rootNode, listOf(
            "Your number has been securely verified", "numéro a été vérifié en toute sécurité"
        ))
        if (estEcranVerified) {
            val boutonContinuerVerified = findNodesByText(rootNode, listOf("Continue", "Continuer")).firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Continue".equals(texte, ignoreCase = true) || "Continue".equals(description, ignoreCase = true) ||
                    "Continuer".equals(texte, ignoreCase = true) || "Continuer".equals(description, ignoreCase = true)
            }
            if (boutonContinuerVerified != null) {
                journaliser("WHATSAPP: écran 'Verified' détecté → clic sur Continue")
                performClick(boutonContinuerVerified)
            } else {
                journaliser("WHATSAPP: écran 'Verified' détecté mais bouton Continue introuvable")
            }
            return
        }

        val estEcranAllowAccess = findNodesByText(rootNode, listOf("Allow access")).isNotEmpty()
        if (estEcranAllowAccess) {
            val boutonSkip = findNodesByText(rootNode, listOf("Skip", "Ignorer")).firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Skip".equals(texte, ignoreCase = true) || "Skip".equals(description, ignoreCase = true) ||
                    "Ignorer".equals(texte, ignoreCase = true) || "Ignorer".equals(description, ignoreCase = true)
            }
            if (boutonSkip != null) {
                journaliser("WHATSAPP: écran 'Allow access' détecté → clic sur Skip")
                performClick(boutonSkip)
            } else {
                journaliser("WHATSAPP: écran 'Allow access' détecté mais bouton Skip introuvable")
            }
            return
        }

        val estEcranBackupGoogle = findNodesByText(rootNode, listOf(
            "backed up to Google storage", "check your Google account for backups"
        )).isNotEmpty()
        if (estEcranBackupGoogle) {
            val boutonSkipBackup = findNodesByText(rootNode, listOf("Skip")).firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Skip".equals(texte, ignoreCase = true) || "Skip".equals(description, ignoreCase = true)
            }
            if (boutonSkipBackup != null) {
                journaliser("WHATSAPP: écran de restauration de sauvegarde Google détecté → clic sur Skip")
                performClick(boutonSkipBackup)
            } else {
                journaliser("WHATSAPP: écran de restauration détecté mais bouton Skip introuvable")
            }
            return
        }

        val estEcranRestoreBackup = texteVisibleContient(rootNode, listOf("Restore a backup"))
        if (estEcranRestoreBackup) {
            val candidatsCancel = mutableListOf<AccessibilityNodeInfo>()
            rechercherManuel(rootNode, listOf("Cancel", "Annuler"), candidatsCancel)
            val boutonCancel = candidatsCancel.firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Cancel".equals(texte, ignoreCase = true) || "Cancel".equals(description, ignoreCase = true) ||
                    "Annuler".equals(texte, ignoreCase = true) || "Annuler".equals(description, ignoreCase = true)
            }
            if (boutonCancel != null) {
                journaliser("WHATSAPP: écran 'Restore a backup' détecté → clic sur Cancel")
                performClick(boutonCancel)
            } else {
                journaliser("WHATSAPP: écran 'Restore a backup' détecté mais bouton Cancel introuvable")
            }
            return
        }

        val nameFields = findFieldsByHint(rootNode, listOf("Votre nom", "Your name", "Nom"))
        if (nameFields.isNotEmpty() && userData.firstName.isNotEmpty()) {
            journaliser("WHATSAPP: champ nom de profil détecté, remplissage")
            val nomComplet = if (userData.lastName.isNotEmpty()) "${userData.firstName} ${userData.lastName}" else userData.firstName
            fillTextField(nameFields.first(), nomComplet)
            clickNextButton(rootNode)
            return
        }

        val estEcranBusinessProfile = texteVisibleContient(rootNode, listOf("Create your business profile"))
        if (estEcranBusinessProfile) {
            var champBusinessName = findFieldsByHint(rootNode, listOf("Business name", "Nom de l'entreprise")).firstOrNull()
            if (champBusinessName == null) {
                val tousLesChamps = mutableListOf<AccessibilityNodeInfo>()
                collecterChampsEditables(rootNode, tousLesChamps)
                champBusinessName = tousLesChamps.firstOrNull()
            }
            if (champBusinessName != null && userData.firstName.isNotEmpty()) {
                journaliser("WHATSAPP: champ 'Business name' détecté, remplissage")
                fillTextField(champBusinessName, userData.firstName)
                clickNextButton(rootNode)
            } else {
                journaliser("WHATSAPP: écran 'Create your business profile' détecté mais champ introuvable (trouvé=${champBusinessName != null}) ou prénom vide")
            }
            return
        }

        // 5ter. Écran "Select your business category" — sélection d'une
        // catégorie d'entreprise. MODIFIÉ : recherche manuelle
        // (rechercherManuel) au lieu de findNodesByText pour le bouton
        // "Other business", cette dernière s'étant révélée peu fiable ici
        // aussi (bouton pourtant visible dans le dump mais jamais trouvé).
        val estEcranCategorie = texteVisibleContient(rootNode, listOf("Select your business category"))
        if (estEcranCategorie) {
            val candidatsCategorie = mutableListOf<AccessibilityNodeInfo>()
            rechercherManuel(rootNode, listOf("Other business"), candidatsCategorie)
            val boutonCategorie = candidatsCategorie.firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Other business".equals(texte, ignoreCase = true) || "Other business".equals(description, ignoreCase = true)
            }
            if (boutonCategorie != null) {
                journaliser("WHATSAPP: écran de catégorie détecté → sélection de 'Other business'")
                performClick(boutonCategorie)
                clickNextButton(rootNode)
            } else {
                journaliser("WHATSAPP: écran de catégorie détecté mais 'Other business' introuvable")
            }
            return
        }

        // 5quater. Écran "Add your business hours" — sélection d'un mode
        // d'horaires (obligatoire, "Next" grisé tant qu'aucune option n'est
        // choisie). On sélectionne systématiquement "Always open" — le
        // choix le plus simple, sans configuration d'horaires détaillée à
        // saisir en plus.
        val estEcranHoraires = texteVisibleContient(rootNode, listOf("Add your business hours"))
        if (estEcranHoraires) {
            val candidatsHoraires = mutableListOf<AccessibilityNodeInfo>()
            rechercherManuel(rootNode, listOf("Always open"), candidatsHoraires)
            val optionAlwaysOpen = candidatsHoraires.firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Always open".equals(texte, ignoreCase = true) || "Always open".equals(description, ignoreCase = true)
            }
            if (optionAlwaysOpen != null) {
                journaliser("WHATSAPP: écran des horaires détecté → sélection de 'Always open'")
                performClick(optionAlwaysOpen)
                clickNextButton(rootNode)
            } else {
                journaliser("WHATSAPP: écran des horaires détecté mais option 'Always open' introuvable")
            }
            return
        }

        // 5quinquies. Écran "Select hours" — confirmation des horaires
        // détaillés jour par jour, pré-rempli par défaut sur "Open 24
        // hours" pour chaque jour suite au choix "Always open" précédent.
        // Le bouton "Next" est déjà actif sans action supplémentaire
        // requise ici — on clique directement dessus.
        val estEcranSelectHours = texteVisibleContient(rootNode, listOf("Select hours"))
        if (estEcranSelectHours) {
            journaliser("WHATSAPP: écran 'Select hours' détecté → clic direct sur Next")
            clickNextButton(rootNode)
            return
        }

        journaliserContenuEcranSiNouveau(rootNode)
    }

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
        val candidats = mutableListOf<AccessibilityNodeInfo>()
        rechercherManuel(rootNode, libellesRecherches, candidats)
        val boutonExact = candidats.firstOrNull { noeud ->
            val texte = noeud.text?.toString()?.trim()
            val description = noeud.contentDescription?.toString()?.trim()
            libellesRecherches.any { it.equals(texte, ignoreCase = true) || it.equals(description, ignoreCase = true) }
        }
        if (boutonExact != null) {
            journaliser("WHATSAPP: bouton Next/Suivant trouvé (recherche manuelle) → clic")
            performClick(boutonExact)
        } else {
            journaliser("WHATSAPP: clickNextButton appelé mais aucun bouton Next/Suivant trouvé")
        }
    }

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

    private fun texteVisibleContient(node: AccessibilityNodeInfo?, motsCles: List<String>): Boolean {
        if (node == null) return false
        val texte = node.text?.toString() ?: ""
        val description = node.contentDescription?.toString() ?: ""
        if (motsCles.any { texte.contains(it, ignoreCase = true) || description.contains(it, ignoreCase = true) }) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (texteVisibleContient(node.getChild(i), motsCles)) return true
        }
        return false
    }

    private fun texteEtDescription(node: AccessibilityNodeInfo): String {
        return ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).trim()
    }

    private fun trouverToutesLesOptionsCheckables(node: AccessibilityNodeInfo?, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isCheckable) resultat.add(node)
        for (i in 0 until node.childCount) {
            trouverToutesLesOptionsCheckables(node.getChild(i), resultat)
        }
    }

    private fun collecterChampsEditables(node: AccessibilityNodeInfo?, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable || node.className == "android.widget.EditText") resultat.add(node)
        for (i in 0 until node.childCount) {
            collecterChampsEditables(node.getChild(i), resultat)
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

    private fun journaliser(message: String) {
        try {
            val fichier = java.io.File(service.filesDir, "crash_log.txt")
            val horodatage = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.FRANCE).format(java.util.Date())
            fichier.appendText("[KOTLIN $horodatage] $message\n")
        } catch (e: Exception) {
        }
    }

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

