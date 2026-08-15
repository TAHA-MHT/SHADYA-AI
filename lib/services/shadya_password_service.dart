import 'package:flutter/services.dart';
import 'shadya_password_service.dart';

class ShadyaAgentBridge {
  static const platform = MethodChannel('com.shadyaai.app/agent');

  static Future<String> creerCompteAvecMotDePasseAuto({
    required String prenom,
    required String nom,
    required String telephone,
    required String plateforme, // ex: "facebook"
  }) async {
    // 1. Vérifie si un mot de passe existe déjà (compte déjà créé avant)
    String? motDePasseExistant = await ShadyaPasswordService.recupererMotDePasse(
      telephone: telephone,
      plateforme: plateforme,
    );

    final motDePasse = motDePasseExistant ??
        ShadyaPasswordService.genererMotDePasseSecurise();

    // 2. Sauvegarde si c'est une première création
    if (motDePasseExistant == null) {
      await ShadyaPasswordService.sauvegarderMotDePasse(
        telephone: telephone,
        plateforme: plateforme,
        motDePasse: motDePasse,
      );
    }

    // 3. Transmet au service Kotlin pour remplissage automatique
    try {
      await platform.invokeMethod('setUserAccountData', {
        'firstName': prenom,
        'lastName': nom,
        'phone': telephone,
        'password': motDePasse,
      });
    } on PlatformException catch (e) {
      print("Erreur transmission données compte: ${e.message}");
    }

    // Retourne le mot de passe pour que Shadya le lise à voix haute UNE fois
    return motDePasseExistant == null ? motDePasse : "";
  }
}
