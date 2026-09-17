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
        // à WhatsApp — OU à des dialogues système pertinents.
        val packageActif = rootNode.packageName?.toString() ?: ""
        if (packageActif != "com.whatsapp" && packageActif != "com.whatsapp.w4b" &&
            packageActif != "android" && packageActif != "com.google.android.gms" &&
            packageActif != "com.android.permissioncontroller" && packageActif != "com.samsung.android.permissioncontroller") {
            return
        }

        // -1. Popup Google Play Services "Choose a phone number"
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

        // 0. Permission système "Allow WhatsApp Business to send you notifications?"
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

        // Après ce point, on ne veut plus agir que sur les écrans propres à WhatsApp.
        if (packageActif == "android" || packageActif == "com.google.android.gms" || packageActif == "com.android.permissioncontroller" || packageActif == "com.samsung.android.permissioncontroller") {
            return
        }

        // 1. Clic automatique sur "Accepter et continuer"
        val agreeButtons = findNodesByText(rootNode, listOf("Accepter et continuer", "Agree and continue", "AGREE AND CONTINUE"))
        if (agreeButtons.isNotEmpty()) {
            journaliser("WHATSAPP: écran 'Agree and continue' détecté")
            performClick(agreeButtons.first())
            return
        }

        // 1bis. Écran "Use +XXX for WhatsApp Business?"
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
        journaliser("WHATSAPP DIAGNOSTIC téléphone: phoneFields.size=${phoneFields.size}, userData.phone=\"${userData.phone}\", numeroAUtiliser=\"$numeroAUtiliser\"")
        if (phoneFields.isNotEmpty() && numeroAUtiliser.isNotEmpty()) {
            journaliser("WHATSAPP: champ numéro détecté, remplissage")
            fillTextField(phoneFields.first(), numeroAUtiliser)
            clickNextButton(rootNode)
            return
        }

        // 2bis. Popup de confirmation "Is this the correct number?"
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

        // 2ter. Écran "Choose how to verify"
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

        // 2quater. Écran "To automatically verify with a missed call to your phone"
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

        // 4. Code de vérification SMS
        val codeFields = findFieldsByHint(rootNode, listOf("code de vérification", "verification code", "Code"))
        if (codeFields.isNotEmpty() && ShadyaAgentService.pendingOtpCode.isNotEmpty()) {
            journaliser("WHATSAPP: champ code détecté, remplissage avec le code en mémoire")
            fillTextField(codeFields.first(), ShadyaAgentService.pendingOtpCode)
            ShadyaAgentService.pendingOtpCode = ""
            return
        }

        // 3bis. Écran "Verified"
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

        // 4bis. Écran "Allow access" (Contacts, Media)
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

        // 4ter. Écran de restauration de sauvegarde Google Drive
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

        // 4quater. Écran "Restore a backup"
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

        // 5. Remplissage du nom de profil
        val nameFields = findFieldsByHint(rootNode, listOf("Votre nom", "Your name", "Nom"))
        if (nameFields.isNotEmpty() && userData.firstName.isNotEmpty()) {
            journaliser("WHATSAPP: champ nom de profil détecté, remplissage")
            val nomComplet = if (userData.lastName.isNotEmpty()) "${userData.firstName} ${userData.lastName}" else userData.firstName
            fillTextField(nameFields.first(), nomComplet)
            clickNextButton(rootNode)
            return
        }

        // 5bis. Écran "Create your business profile"
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

        // 5ter. Écran "Select your business category"
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

        // 5quater. Écran "Add your business hours"
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

        // 5quinquies. Écran "Select hours" (confirmation, Next déjà actif)
        val estEcranSelectHours = texteVisibleContient(rootNode, listOf("Select hours"))
        if (estEcranSelectHours) {
            journaliser("WHATSAPP: écran 'Select hours' détecté → clic direct sur Next")
            clickNextButton(rootNode)
            return
        }

        // 5sexies. Écran "Add a profile picture"
        val estEcranPhotoProfil = texteVisibleContient(rootNode, listOf("Add a profile picture"))
        if (estEcranPhotoProfil) {
            val candidatsSkipPhoto = mutableListOf<AccessibilityNodeInfo>()
            rechercherManuel(rootNode, listOf("Skip", "Ignorer"), candidatsSkipPhoto)
            val boutonSkipPhoto = candidatsSkipPhoto.firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Skip".equals(texte, ignoreCase = true) || "Skip".equals(description, ignoreCase = true) ||
                    "Ignorer".equals(texte, ignoreCase = true) || "Ignorer".equals(description, ignoreCase = true)
            }
            if (boutonSkipPhoto != null) {
                journaliser("WHATSAPP: écran 'Add a profile picture' détecté → clic sur Skip")
                performClick(boutonSkipPhoto)
            } else {
                journaliser("WHATSAPP: écran 'Add a profile picture' détecté mais bouton Skip introuvable")
            }
            return
        }

        // 5septies. Écran "More ways to find you"
        val estEcranMoreWaysToFind = texteVisibleContient(rootNode, listOf("More ways to find you"))
        if (estEcranMoreWaysToFind) {
            val candidatsSkipAdresse = mutableListOf<AccessibilityNodeInfo>()
            rechercherManuel(rootNode, listOf("Skip", "Ignorer"), candidatsSkipAdresse)
            val boutonSkipAdresse = candidatsSkipAdresse.firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Skip".equals(texte, ignoreCase = true) || "Skip".equals(description, ignoreCase = true) ||
                    "Ignorer".equals(texte, ignoreCase = true) || "Ignorer".equals(description, ignoreCase = true)
            }
            if (boutonSkipAdresse != null) {
                journaliser("WHATSAPP: écran 'More ways to find you' détecté → clic sur Skip")
                performClick(boutonSkipAdresse)
            } else {
                journaliser("WHATSAPP: écran 'More ways to find you' détecté mais bouton Skip introuvable")
            }
            return
        }

        // 5octies. Écran "Add a business description"
        val estEcranDescription = texteVisibleContient(rootNode, listOf("Add a business description"))
        if (estEcranDescription) {
            val candidatsSkipDescription = mutableListOf<AccessibilityNodeInfo>()
            rechercherManuel(rootNode, listOf("Skip", "Ignorer"), candidatsSkipDescription)
            val boutonSkipDescription = candidatsSkipDescription.firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Skip".equals(texte, ignoreCase = true) || "Skip".equals(description, ignoreCase = true) ||
                    "Ignorer".equals(texte, ignoreCase = true) || "Ignorer".equals(description, ignoreCase = true)
            }
            if (boutonSkipDescription != null) {
                journaliser("WHATSAPP: écran 'Add a business description' détecté → clic sur Skip")
                performClick(boutonSkipDescription)
            } else {
                journaliser("WHATSAPP: écran 'Add a business description' détecté mais bouton Skip introuvable")
            }
            return
        }

        // 5nonies. Écran "Add your email" — champ optionnel, "Next" grisé
        // sans saisie. "Skip" est positionné en bas au centre (et non en
        // haut à droite comme les écrans précédents), mais la recherche
        // fonctionne peu importe l'emplacement.
        val estEcranEmail = texteVisibleContient(rootNode, listOf("Add your email"))
        if (estEcranEmail) {
            val candidatsSkipEmail = mutableListOf<AccessibilityNodeInfo>()
            rechercherManuel(rootNode, listOf("Skip", "Ignorer"), candidatsSkipEmail)
            val boutonSkipEmail = candidatsSkipEmail.firstOrNull { noeud ->
                val texte = noeud.text?.toString()?.trim()
                val description = noeud.contentDescription?.toString()?.trim()
                "Skip".equals(texte, ignoreCase = true) || "Skip".equals(description, ignoreCase = true) ||
                    "Ignorer".equals(texte, ignoreCase = true) || "Ignorer".equals(description, ignoreCase = true)
            }
            if (boutonSkipEmail != null) {
                journaliser("WHATSAPP: écran 'Add your email' détecté → clic sur Skip")
                performClick(boutonSkipEmail)
            } else {
                journaliser("WHATSAPP: écran 'Add your email' détecté mais bouton Skip introuvable")
            }
            return
        }

        // DIAGNOSTIC : aucun des blocs ci-dessus n'a reconnu l'écran actuel.
        journaliserContenuEcranSiNouveau(rootNode)
    }

    // Appelée directement par ShadyaAgentService dès qu'un code de
    // vérification à 5 chiffres est détecté dans une notification SMS ou WhatsApp.
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

    // Détecte la présence d'un indicateur de chargement visible n'importe où à l'écran.
    private fun estEnChargement(rootNode: AccessibilityNodeInfo): Boolean {
        val libellesChargement = listOf("Loading", "Loading...", "Loading…", "Chargement")
        return findNodesByText(rootNode, libellesChargement).any { noeud ->
            val texte = noeud.text?.toString()?.trim()
            val description = noeud.contentDescription?.toString()?.trim()
            libellesChargement.any { it.equals(texte, ignoreCase = true) || it.equals(description, ignoreCase = true) }
        }
    }

    private var chargementDejaSignale = false

    // Recherche manuelle (fiable) au lieu de findNodesByText pour le bouton Next.
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

    // Clique sur le nœud, ou remonte vers le premier ancêtre réellement cliquable.
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

    // Recherche manuelle récursive de texte visible n'importe où dans l'écran —
    // remplace findAccessibilityNodeInfosByText, peu fiable par moments.
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

    // Recherche récursive de TOUS les nœuds "checkable" (boutons radio, cases à cocher).
    private fun trouverToutesLesOptionsCheckables(node: AccessibilityNodeInfo?, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isCheckable) resultat.add(node)
        for (i in 0 until node.childCount) {
            trouverToutesLesOptionsCheckables(node.getChild(i), resultat)
        }
    }

    // Recherche récursive de tous les champs éditables — repli quand la recherche par hint échoue.
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

    // Écrit une ligne horodatée dans crash_log.txt.
    private fun journaliser(message: String) {
        try {
            val fichier = java.io.File(service.filesDir, "crash_log.txt")
            val horodatage = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.FRANCE).format(java.util.Date())
            fichier.appendText("[KOTLIN $horodatage] $message\n")
        } catch (e: Exception) {
            // Le journal est un outil de diagnostic, pas critique au flux.
        }
    }

    // Empreinte du dernier écran journalisé — évite les doublons.
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
