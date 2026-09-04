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

    // Empreinte du dernier écran dont le contenu complet a été journalisé,
    // utilisée pour éviter de ré-écrire le même dump à chaque événement
    // d'accessibilité (qui peuvent arriver plusieurs fois par seconde sur
    // un même écran figé) : on ne journalise le contenu détaillé qu'une
    // seule fois par écran réellement différent.
    private var derniereEmpreinteEcranJournalisee: Int? = null

    // Instant (millisecondes) où l'écran "Enter the confirmation code" a été
    // vu pour la première fois lors de cette tentative — sert à mesurer le
    // temps d'attente du SMS/WhatsApp, indépendamment du nombre d'événements
    // d'accessibilité reçus entre-temps. Réinitialisé dès qu'on quitte cet
    // écran (voir réinitialisation en tête de handleSignup).
    private var horodatageDebutAttenteCode: Long? = null

    // Délai maximal d'attente du code avant de cliquer automatiquement sur
    // "I didn't receive the code" (qui relance l'envoi ou bascule de canal
    // côté Facebook). 90 secondes plutôt que 45 : le SMS peut légitimement
    // prendre plus d'une minute selon la charge de l'opérateur, et déclencher
    // le clic de secours trop tôt gênerait un envoi simplement un peu lent.
    private val delaiMaxAttenteCodeMs = 90_000L

    fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = service.rootInActiveWindow ?: return

        if (mode == "login") {
            handleLogin(rootNode)
        } else {
            handleSignup(rootNode)
        }
    }

    // Appelée directement par ShadyaAgentService dès qu'un code de
    // confirmation à 5 chiffres est détecté dans une notification SMS —
    // permet de remplir le champ immédiatement, sans attendre le prochain
    // événement d'accessibilité naturel de Facebook (qui pourrait tarder si
    // l'écran ne change pas). Si le champ n'est pas encore trouvable à cet
    // instant précis (Facebook pas encore sur cet écran, fenêtre en transition),
    // le code reste mémorisé dans ShadyaAgentService.pendingOtpCode et sera
    // utilisé par la détection normale dans handleSignup dès que l'écran de
    // confirmation apparaîtra.
    fun tenterRemplirCodeConfirmation(code: String) {
        val rootNode = service.rootInActiveWindow ?: return
        val champsCode = findFieldsByHint(rootNode, listOf("Confirmation code", "Code de confirmation"))
        if (champsCode.isNotEmpty()) {
            journaliser("OTP: code reçu par notification ($code) → remplissage immédiat du champ de confirmation")
            fillTextField(champsCode.first(), code)
            clickNextButton(rootNode)
            ShadyaAgentService.pendingOtpCode = ""
            horodatageDebutAttenteCode = null
        } else {
            journaliser("OTP: code reçu ($code) mais champ de confirmation introuvable à cet instant — mémorisé pour le prochain écran")
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
        // Filtre sur le texte EXACT (pas seulement "contient"), car la
        // recherche par texte est insensible à la casse et aux sous-chaînes :
        // chercher "SET" trouvait aussi bien le vrai bouton que le titre de
        // la fenêtre "Set date" (qui contient également "set"). Le code
        // cliquait alors sur ce titre, non cliquable, expliquant pourquoi
        // le clic échouait silencieusement en boucle malgré une cible
        // correctement atteinte.
        val setDateButtons = findNodesByText(rootNode, listOf("SET")).filter { it.text?.toString()?.trim() == "SET" }
        val cancelButtons = findNodesByText(rootNode, listOf("CANCEL")).filter { it.text?.toString()?.trim() == "CANCEL" }
        val estEcranDateNaissance = setDateButtons.isNotEmpty() && cancelButtons.isNotEmpty()
        if (!estEcranDateNaissance) {
            anneeCibleEnCours = null
            nombreSwipesEffectues = 0
        }

        // Réinitialise le chronomètre d'attente du code de confirmation dès
        // qu'on n'est plus sur cet écran — évite qu'un temps d'attente
        // calculé lors d'une tentative précédente (compte abandonné, ou déjà
        // validé) fausse le calcul lors d'une prochaine tentative.
        val estEcranCodeConfirmation = findFieldsByHint(rootNode, listOf("Confirmation code", "Code de confirmation")).isNotEmpty()
        if (!estEcranCodeConfirmation) {
            horodatageDebutAttenteCode = null
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

        // Écran "What's your gender?" : détection basée sur les nœuds
        // "checkable" eux-mêmes (bouton radio ou équivalent), et non plus
        // sur une recherche de texte classique. Deux tentatives précédentes
        // ont échoué : d'abord via le titre complet de la question, puis
        // via une recherche exacte du texte "Male"/"Female" — dans les deux
        // cas, ZÉRO résultat, alors même que ces mots sont visiblement
        // affichés à l'écran. Cela indique que Facebook utilise probablement
        // une interface moderne (Jetpack Compose) qui fusionne le texte de
        // l'option ("Male") avec son conteneur parent en un seul nœud
        // d'accessibilité — le texte "Male" n'existe alors nulle part comme
        // nœud séparé, seulement comme partie du texte/contentDescription du
        // nœud "checkable" parent lui-même. On parcourt donc directement
        // tous les nœuds checkable de l'écran et on regarde leur texte ET
        // leur contentDescription combinés.
        val optionsCheckables = mutableListOf<AccessibilityNodeInfo>()
        trouverToutesLesOptionsCheckables(rootNode, optionsCheckables)
        val optionFemale = optionsCheckables.firstOrNull { texteEtDescription(it).contains("Female", ignoreCase = true) }
        val optionMale = optionsCheckables.firstOrNull {
            texteEtDescription(it).contains("Male", ignoreCase = true) &&
                !texteEtDescription(it).contains("Female", ignoreCase = true)
        }
        val estEcranGenre = optionMale != null && optionFemale != null

        if (estEcranGenre) {
            val genreCible = if (userData.gender.equals("Female", ignoreCase = true)) "Female" else "Male"
            val optionGenreNode = if (genreCible == "Female") optionFemale!! else optionMale!!

            journaliser("GENRE[$genreCible]: noeud checkable trouvé, classe=${optionGenreNode.className}, texte=\"${optionGenreNode.text}\", desc=\"${optionGenreNode.contentDescription}\", checked=${optionGenreNode.isChecked}")

            // Plutôt que de cliquer une seule fois puis d'espérer que la
            // sélection a pris effet, on vérifie l'état réel (isChecked)
            // avant de continuer. Si ce n'est pas encore coché, on reclique
            // — l'événement d'accessibilité suivant revérifiera l'état,
            // garantissant qu'on ne passe à "Next" que lorsque l'option est
            // effectivement sélectionnée, quel que soit le nombre de
            // tentatives nécessaires.
            if (optionGenreNode.isChecked) {
                journaliser("GENRE[$genreCible]: déjà sélectionné → clic Next")
                clickNextButton(rootNode)
            } else {
                journaliser("GENRE[$genreCible]: pas encore sélectionné → clic direct sur le nœud checkable")
                performClick(optionGenreNode)
            }
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

        // Écran "What's your email address?" : Facebook propose cet écran
        // avec une alternative "Sign up with mobile number" — on privilégie
        // systématiquement cette bascule vers le numéro de téléphone plutôt
        // que de remplir un email (déjà disponible et fiable dans
        // userData.phone, alors qu'un email généré risquerait d'être rejeté
        // par Facebook). Sans cette détection, l'écran tombait dans le filet
        // de sécurité générique en fin de fonction, qui recliquait "Next" en
        // boucle sur le bouton désactivé (champ email vide obligatoire),
        // provoquant un défilement erratique du clavier à l'écran.
        val champsEmail = findFieldsByHint(rootNode, listOf("Email address", "Adresse e-mail", "E-mail address"))
        if (champsEmail.isNotEmpty()) {
            val boutonMobile = findNodesByText(rootNode, listOf(
                "Sign up with mobile number", "S'inscrire avec un numéro de mobile",
                "S'inscrire avec un numéro de téléphone"
            ))
            if (boutonMobile.isNotEmpty() && userData.phone.isNotEmpty()) {
                journaliser("EMAIL: écran détecté → bascule vers inscription par numéro de mobile")
                performClick(boutonMobile.first())
            } else {
                journaliser("EMAIL: écran détecté mais aucun bouton mobile disponible et/ou aucun numéro de téléphone en mémoire — en attente, aucune action pour éviter le clic aveugle sur Next")
            }
            return
        }

        // Écran "Enter the confirmation code" : le code à 5 chiffres envoyé
        // par SMS/WhatsApp est normalement déjà rempli par
        // tenterRemplirCodeConfirmation (déclenchée dès la réception de la
        // notification, voir ShadyaAgentService). Ce bloc couvre le cas où
        // l'écran de confirmation apparaît APRÈS que le code a déjà été
        // capturé (ou avant, cas le plus fréquent, où le code n'est pas
        // encore arrivé — on journalise alors une simple attente plutôt que
        // de spammer "Next" sur un champ vide).
        //
        // Sécurité anti-blocage : si aucun code n'arrive du tout après
        // délaiMaxAttenteCodeMs (réseau lent, notification bloquée par
        // l'utilisateur, SMS jamais envoyé), on clique automatiquement sur
        // "I didn't receive the code" pour relancer l'envoi ou basculer de
        // canal côté Facebook, plutôt que de rester bloqué indéfiniment.
        val champsCodeConfirmation = findFieldsByHint(rootNode, listOf("Confirmation code", "Code de confirmation"))
        if (champsCodeConfirmation.isNotEmpty()) {
            if (horodatageDebutAttenteCode == null) {
                horodatageDebutAttenteCode = System.currentTimeMillis()
                journaliser("OTP: écran de confirmation détecté, début du chronométrage d'attente")
            }

            val codeMemorise = ShadyaAgentService.pendingOtpCode
            if (codeMemorise.isNotEmpty()) {
                journaliser("OTP: champ de confirmation détecté, code déjà en mémoire ($codeMemorise) → remplissage")
                fillTextField(champsCodeConfirmation.first(), codeMemorise)
                clickNextButton(rootNode)
                ShadyaAgentService.pendingOtpCode = ""
                horodatageDebutAttenteCode = null
                return
            }

            val tempsEcouleMs = System.currentTimeMillis() - horodatageDebutAttenteCode!!
            if (tempsEcouleMs >= delaiMaxAttenteCodeMs) {
                val boutonPasRecu = findNodesByText(rootNode, listOf(
                    "I didn't receive the code", "Je n'ai pas reçu le code", "Je n'ai pas reçu de code"
                ))
                if (boutonPasRecu.isNotEmpty()) {
                    journaliser("OTP: aucun code reçu après ${tempsEcouleMs}ms → clic sur 'I didn't receive the code'")
                    performClick(boutonPasRecu.first())
                    // Redémarre le chronométrage pour laisser une chance au
                    // nouvel envoi (SMS ou canal alternatif) d'arriver avant
                    // de recliquer une seconde fois sur le bouton de secours.
                    horodatageDebutAttenteCode = System.currentTimeMillis()
                } else {
                    journaliser("OTP: délai de ${tempsEcouleMs}ms dépassé mais bouton de secours introuvable — nouvelle tentative au prochain événement")
                }
            } else {
                journaliser("OTP: champ de confirmation détecté, en attente du code (${tempsEcouleMs}ms écoulées sur $delaiMaxAttenteCodeMs)")
            }
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

            // Un seul geste de glissement par appel, portant sur une seule
            // année à la fois : le regroupement par lots (jusqu'à 10 années
            // en un geste) donnait une distance qui variait de façon
            // imprévisible selon la taille du lot (parfois trop courte,
            // parfois trop longue), empêchant de tomber précisément sur la
            // bonne année. Un geste par année, vérifié individuellement à
            // chaque étape, est plus lent mais garantit d'atteindre
            // exactement la cible.
            val versLePasse = difference > 0
            val anneesParGeste = 1

            ajustementEnCours = true
            nombreSwipesEffectues++
            swipeAnnees(rootNode, anneesParGeste, versLePasse = versLePasse)
            handlerAnnee.postDelayed({ ajustementEnCours = false }, 200L)
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
        //
        // DIAGNOSTIC : avant de cliquer à l'aveugle, on journalise le
        // contenu textuel complet de l'écran — une seule fois par écran
        // réellement différent (voir derniereEmpreinteEcranJournalisee).
        // Objectif : ne plus jamais deviner à l'aveugle la structure d'un
        // écran non reconnu (genre, ou tout autre écran futur du parcours) ;
        // le prochain blocage sera immédiatement diagnosticable à partir de
        // ce dump, sans aller-retour supplémentaire.
        val packageActif = rootNode.packageName?.toString() ?: ""
        if (packageActif == "com.facebook.katana" || packageActif == "com.facebook.lite") {
            journaliserContenuEcranSiNouveau(rootNode)
            clickNextButton(rootNode)
        }
    }

    // Concatène le texte et la contentDescription d'un nœud, séparés par un
    // espace, pour permettre une recherche unique qui couvre les deux
    // propriétés — utile face à des interfaces (Jetpack Compose notamment)
    // où le libellé visible peut se trouver dans l'une ou l'autre selon la
    // façon dont le composant a été construit.
    private fun texteEtDescription(node: AccessibilityNodeInfo): String {
        return ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).trim()
    }

    // Recherche récursive de TOUS les nœuds "checkable" (boutons radio,
    // cases à cocher, ou équivalents modernes) de l'arborescence — utilisé
    // pour détecter les écrans à choix (comme "What's your gender?") sans
    // dépendre d'une recherche de texte classique, qui peut échouer si le
    // libellé est fusionné avec son conteneur par le framework d'interface.
    private fun trouverToutesLesOptionsCheckables(node: AccessibilityNodeInfo?, resultat: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isCheckable) resultat.add(node)
        for (i in 0 until node.childCount) {
            trouverToutesLesOptionsCheckables(node.getChild(i), resultat)
        }
    }

    // DIAGNOSTIC : parcourt tout l'arbre d'accessibilité et journalise
    // chaque nœud portant un texte ou une contentDescription non vide
    // (classe, cliquable, checkable, checked, texte, description, bornes).
    // Ne journalise qu'une seule fois par écran réellement différent grâce
    // à une empreinte calculée sur l'ensemble des textes trouvés, pour
    // éviter de saturer le journal quand le même écran reçoit plusieurs
    // événements d'accessibilité rapprochés.
    private fun journaliserContenuEcranSiNouveau(rootNode: AccessibilityNodeInfo) {
        val lignes = mutableListOf<String>()
        collecterContenuTexte(rootNode, lignes)
        val empreinte = lignes.joinToString("|").hashCode()
        if (empreinte == derniereEmpreinteEcranJournalisee) return
        derniereEmpreinteEcranJournalisee = empreinte

        journaliser("=== DUMP ÉCRAN NON RECONNU (${lignes.size} nœud(s) avec texte) ===")
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
            val zone = Rect()
            node.getBoundsInScreen(zone)
            resultat.add("classe=${node.className}, clicable=${node.isClickable}, checkable=${node.isCheckable}, checked=${node.isChecked}, texte=\"$texte\", desc=\"$description\", bornes=(${zone.left},${zone.top},${zone.right},${zone.bottom})")
        }
        for (i in 0 until node.childCount) {
            collecterContenuTexte(node.getChild(i), resultat)
        }
    }

    // Clique sur le nœud, ou remonte vers le premier parent cliquable si le nœud
    // lui-même ne l'est pas (cas fréquent : le texte est dans un enfant non cliquable).
    // Clique sur le nœud, ou remonte vers le premier parent réellement
    // cliquable si le nœud lui-même ne l'est pas. Vérifie à la fois le
    // drapeau isClickable ET la présence effective de l'action ACTION_CLICK
    // dans la liste des actions supportées : certaines interfaces modernes
    // n'exposent pas isClickable=true sur leurs boutons tout en supportant
    // réellement le clic, ce qui faisait échouer silencieusement le clic
    // sur le bouton "SET" du sélecteur de date (aucune erreur, mais aucun
    // effet non plus, provoquant une boucle infinie de tentatives).
    private fun performClick(node: AccessibilityNodeInfo) {
        var courant: AccessibilityNodeInfo? = node
        while (courant != null) {
            val supporteClic = courant.isClickable ||
                courant.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (supporteClic) {
                val reussi = courant.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                journaliser("performClick sur classe=${courant.className}, texte=\"${courant.text}\", desc=\"${courant.contentDescription}\": réussi=$reussi")
                if (reussi) return
            }
            courant = courant.parent
        }
        journaliser("performClick: aucun ancêtre cliquable trouvé pour \"${node.text}\"")
    }

    private fun fillTextField(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clickNextButton(rootNode: AccessibilityNodeInfo) {
        val nextButtons = findNodesByText(rootNode, listOf(
            "Suivant", "Next", "S'inscrire", "Continue", "Sign up", "Sign Up",
            "I agree", "J'accepte"
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
        val dureeGeste = (130L * nombreAnnees).coerceAtMost(4000L)

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

    // DIAGNOSTIC : throttle pour éviter d'inonder le journal, puisque le
    // fallback ci-dessous peut être appelé plusieurs fois par seconde.
    private var derniereFoisDumpInconnu = 0L

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

