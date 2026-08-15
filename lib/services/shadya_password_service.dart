import 'dart:math';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class ShadyaPasswordService {
  static const _storage = FlutterSecureStorage();

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

  static Future<void> sauvegarderMotDePasse({
    required String telephone,
    required String plateforme,
    required String motDePasse,
  }) async {
    final cle = 'mdp_${plateforme}_$telephone';
    await _storage.write(key: cle, value: motDePasse);
  }

  static Future<String?> recupererMotDePasse({
    required String telephone,
    required String plateforme,
  }) async {
    final cle = 'mdp_${plateforme}_$telephone';
    return await _storage.read(key: cle);
  }

  static String formaterPourLectureVocale(String motDePasse) {
    return motDePasse.split('').join(', ');
  }
}
