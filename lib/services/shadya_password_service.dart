import 'dart:math';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class ShadyaPasswordService {
  static const _storage = FlutterSecureStorage();

  // Génère un mot de passe fort : 12 caractères, majuscules, minuscules, chiffres, symbole
  static String genererMotDePasseSecurise() {
    const majuscules = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
    const minuscules = 'abcdefghijkmnpqrstuvwxyz';
    const chiffres = '23456789';
    const symboles = '!@#%*';

    final random = Random.secure();

    String tirer(String source, int n) =>
        List.generate(n, (_) => source[random.nextInt(source.length)]).join();

    final motDePasse = (tirer(majuscules, 3) +
            tirer(minuscules, 5) +
            tirer(chiffres, 3) +
            tirer(symboles, 1))
        .split('')
      ..shuffle(random);

    return motDePasse.join();
  }

  // Stocke le mot de passe, lié au numéro de téléphone + plateforme (facebook, whatsapp...)
  static Future<void> sauvegarderMotDePasse({
    required String telephone,
    required String plateforme,
    required String motDePasse,
  }) async {
    final cle = 'mdp_${plateforme}_$telephone';
    await _storage.write(key: cle, value: motDePasse);
  }

  // Récupère le mot de passe déjà généré pour une connexion future (login automatique)
  static Future<String?> recupererMotDePasse({
    required String telephone,
    required String plateforme,
  }) async {
    final cle = 'mdp_${plateforme}_$telephone';
    return await _storage.read(key: cle);
  }

  // Lecture syllabée pour restitution vocale (plus facile à suivre à l'oral)
  static String formaterPourLectureVocale(String motDePasse) {
    return motDePasse.split('').join(', ');
  }
}
