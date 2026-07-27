import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show compute;
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_ai/firebase_ai.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:intl/intl.dart';
import 'package:vosk_flutter/vosk_flutter.dart' as vosk;
import 'package:tflite_flutter/tflite_flutter.dart' as tfl;
import 'package:path_provider/path_provider.dart';
import 'package:archive/archive.dart';
import 'package:archive/archive_io.dart';
import 'package:file_picker/file_picker.dart';

import 'package:firebase_app_check/firebase_app_check.dart';
import 'firebase_options.dart';
import 'l10n/app_localizations.dart';

/// Écrit une entrée dans le fichier crash_log.txt (append), pour pouvoir
/// consulter l'historique des erreurs via le panneau de debug caché,
/// sans avoir besoin d'adb/Termux.
Future<void> _ecrireCrashLog(String contenu) async {
  try {
    final appDir = await getApplicationSupportDirectory();
    final file = File('${appDir.path}/crash_log.txt');
    final horodatage = DateTime.now().toIso8601String();
    await file.writeAsString(
      '--- ERREUR $horodatage ---\n$contenu\n\n',
      mode: FileMode.append,
      flush: true,
    );
  } catch (_) {
    // Si même l'écriture du log échoue, on ne peut rien faire de plus.
  }
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Capture les erreurs Flutter (widgets, build, etc.)
  FlutterError.onError = (FlutterErrorDetails details) {
    FlutterError.presentError(details);
    _ecrireCrashLog('${details.exceptionAsString()}\n${details.stack}');
  };

  // Capture les erreurs Dart non gérées ailleurs (async, isolates racine, etc.)
  // NOTE IMPORTANTE : si le système Android tue l'app pour manque de mémoire
  // (OOM / low-memory killer), ce handler ne sera JAMAIS appelé, car le
  // processus est tué brutalement avant que Dart ne puisse réagir. Dans ce
  // cas, crash_log.txt restera vide malgré le crash.
  PlatformDispatcher.instance.onError = (error, stack) {
    _ecrireCrashLog('$error\n$stack');
    return true;
  };

  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );
  await FirebaseAppCheck.instance.activate(
    androidProvider: AndroidProvider.debug,
  );

  runApp(const ShadyaApp());
}

/// Extraction en streaming : lit, décompresse et écrit sur le disque par
/// blocs successifs, sans jamais charger le fichier archive entier en
/// mémoire d'un coup.
Future<void> _extraireArchiveIsolate(List<String> params) async {
  final archivePath = params[0];
  final outputDirPath = params[1];

  await Directory(outputDirPath).create(recursive: true);
  await extractFileToDisk(archivePath, outputDirPath);
}

class ShadyaApp extends StatelessWidget {
  const ShadyaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Shadya AI',
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      theme: ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: Colors.white,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFC4E100),
          brightness: Brightness.light,
        ),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFC4E100),
          brightness: Brightness.dark,
        ),
      ),
      home: const VoiceHomeScreen(),
    );
  }
}

class VoiceHomeScreen extends StatefulWidget {
  const VoiceHomeScreen({super.key});

  @override
  State<VoiceHomeScreen> createState() => _VoiceHomeScreenState();
}

class _VoiceHomeScreenState extends State<VoiceHomeScreen> {
  final stt.SpeechToText _speech = stt.SpeechToText();
  final FlutterTts _tts = FlutterTts();

  late final GenerativeModel _model;

  bool _speechEnabled = false;
  bool _isListening = false;
  String _recognizedText = '';
  String? _debugSecretInfo;
  bool _showDebugPanel = false;

  List<Contact> _contacts = [];

  static const String _sherpaModelDirName = 'vosk-model-small-fr-0.22';

  final vosk.VoskFlutterPlugin _vosk = vosk.VoskFlutterPlugin.instance();
  vosk.Model? _voskModel;
  vosk.Recognizer? _voskRecognizer;
  bool _sherpaReady = false;
  bool _sherpaEnCours = false;
  bool _sherpaNecessiteFichier = false;
  String? _sherpaModelPathDetecte;
  String _sherpaStatus = "Préparation de la reconnaissance vocale...";
  String? _tfliteTestResult;

  final List<Map<String, dynamic>> _commandesLocales = [
    {
      'motsCles': ['lumière', 'lumiere'],
      'action': 'allume',
      'reponse': "D'accord, j'allume la lumière.",
    },
    {
      'motsCles': ['lumière', 'lumiere'],
      'action': 'éteins',
      'reponse': "D'accord, j'éteins la lumière.",
    },
    {
      'motsCles': ['climatiseur', 'clim'],
      'action': 'allume',
      'reponse': "D'accord, j'allume le climatiseur.",
    },
    {
      'motsCles': ['climatiseur', 'clim'],
      'action': 'éteins',
      'reponse': "D'accord, j'éteins le climatiseur.",
    },
    {
      'motsCles': ['ventilateur'],
      'action': 'allume',
      'reponse': "D'accord, j'allume le ventilateur.",
    },
    {
      'motsCles': ['ventilateur'],
      'action': 'éteins',
      'reponse': "D'accord, j'éteins le ventilateur.",
    },
    {
      'motsCles': ['télévision', 'television', 'télé', 'tele'],
      'action': 'allume',
      'reponse': "D'accord, j'allume la télévision.",
    },
    {
      'motsCles': ['télévision', 'television', 'télé', 'tele'],
      'action': 'éteins',
      'reponse': "D'accord, j'éteins la télévision.",
    },
    {
      'motsCles': ['radio'],
      'action': 'allume',
      'reponse': "D'accord, j'allume la radio.",
    },
    {
      'motsCles': ['radio'],
      'action': 'éteins',
      'reponse': "D'accord, j'éteins la radio.",
    },
    {
      'motsCles': ['portail'],
      'action': 'ouvre',
      'reponse': "D'accord, j'ouvre le portail.",
    },
    {
      'motsCles': ['portail'],
      'action': 'ferme',
      'reponse': "D'accord, je ferme le portail.",
    },
    {
      'motsCles': ['porte'],
      'action': 'ouvre',
      'reponse': "D'accord, j'ouvre la porte.",
    },
    {
      'motsCles': ['porte'],
      'action': 'ferme',
      'reponse': "D'accord, je ferme la porte.",
    },
    {
      'motsCles': ['chauffe-eau', 'chauffe eau'],
      'action': 'allume',
      'reponse': "D'accord, j'allume le chauffe-eau.",
    },
    {
      'motsCles': ['chauffe-eau', 'chauffe eau'],
      'action': 'éteins',
      'reponse': "D'accord, j'éteins le chauffe-eau.",
    },
    {
      'motsCles': ['générateur', 'generateur'],
      'action': 'allume',
      'reponse': "D'accord, j'allume le générateur.",
    },
    {
      'motsCles': ['générateur', 'generateur'],
      'action': 'éteins',
      'reponse': "D'accord, j'éteins le générateur.",
    },
  ];

  final List<Map<String, dynamic>> _reponsesFixes = [
    {
      'motsCles': ['bonjour', 'salut', 'bonsoir', 'salam', 'salamalik'],
      'reponse': "Bonjour ! Je suis Shadya, comment puis-je t'aider ?",
    },
    {
      'motsCles': ['comment ça va', 'comment vas-tu', 'ça va'],
      'reponse': "Je vais très bien, merci ! Et toi, comment ça va ?",
    },
    {
      'motsCles': ['merci'],
      'reponse': "Avec plaisir !",
    },
    {
      'motsCles': ['au revoir', 'à bientôt', 'a bientot', 'bye'],
      'reponse': "À bientôt !",
    },
    {
      'motsCles': ['qui es-tu', 'qui es tu', 'tu es qui', "c'est quoi shadya"],
      'reponse':
          "Je suis Shadya, ton assistante vocale développée par Peace Technologies.",
    },
    {
      'motsCles': ['que peux-tu faire', 'que sais-tu faire', 'tu sers à quoi'],
      'reponse':
          "Je peux contrôler des appareils chez toi, appeler tes contacts, et répondre à tes questions.",
    },
    {
      'motsCles': ['tu es hors ligne', 'pas de connexion', 'pas internet'],
      'reponse':
          "Je fonctionne même sans connexion pour les commandes de base comme la domotique.",
    },
  ];

  @override
  void initState() {
    super.initState();

    FirebaseAppCheck.instance.getToken(true);

    _model = FirebaseAI.googleAI().generativeModel(
      model: 'gemini-3.5-flash',
      generationConfig: GenerationConfig(
        maxOutputTokens: 800,
      ),
    );

    _setup();
  }

  Future<void> _setup() async {
    await _initAssistant();
    await _loadContacts();
    _initSherpa();
  }

  Future<void> _loadContacts() async {
    if (await FlutterContacts.requestPermission()) {
      final contacts = await FlutterContacts.getContacts(withProperties: true);
      setState(() {
        _contacts = contacts;
      });
    }
  }

  Future<String?> _cheminModeleSiPresent() async {
    final appDir = await getApplicationSupportDirectory();
    final modelDir = Directory('${appDir.path}/$_sherpaModelDirName');
    if (await modelDir.exists()) {
      // Un modèle Vosk contient un dossier "am" (acoustic model) ; on
      // vérifie sa présence pour confirmer une extraction complète.
      final amDir = Directory('${modelDir.path}/am');
      final confDir = Directory('${modelDir.path}/conf');
      if (await amDir.exists() || await confDir.exists()) {
        return modelDir.path;
      }
    }
    return null;
  }

  Future<void> _initSherpa() async {
    final micStatus = await Permission.microphone.request();
    if (!micStatus.isGranted) {
      setState(() {
        _sherpaStatus = "Permission micro refusée.";
      });
      return;
    }

    final modelPathExistant = await _cheminModeleSiPresent();

    if (modelPathExistant != null) {
      // IMPORTANT : on ne charge plus automatiquement le modèle au démarrage.
      // Le chargement natif peut crasher l'app ; en le rendant manuel, on a
      // le temps de consulter le diagnostic (appui long sur le titre) avant
      // de relancer un chargement qui pourrait re-crasher.
      setState(() {
        _sherpaModelPathDetecte = modelPathExistant;
        _sherpaStatus =
            "Modèle détecté sur le disque. Appuie sur le bouton pour le charger (risque de crash si le modèle est corrompu).";
      });
    } else {
      setState(() {
        _sherpaNecessiteFichier = true;
        _sherpaStatus =
            "Télécharge le modèle vocal via Chrome, puis appuie sur le bouton ci-dessous pour le sélectionner.";
      });
    }
  }

  Future<void> _choisirEtExtraireModele() async {
    try {
      setState(() {
        _sherpaEnCours = true;
        _sherpaStatus = "Ouverture du sélecteur de fichier...";
      });

      final result = await FilePicker.pickFiles();

      if (result == null) {
        setState(() {
          _sherpaEnCours = false;
          _sherpaStatus =
              "Aucun fichier sélectionné. Appuie à nouveau sur le bouton pour réessayer.";
        });
        return;
      }

      final xFile = result.files.first.xFile;
      final fichierChoisi = xFile.path;

      setState(() {
        _sherpaStatus =
            "Extraction du modèle vocal (patiente, ça peut prendre une minute)...";
      });

      final appDir = await getApplicationSupportDirectory();
      final List<String> parametres = <String>[fichierChoisi, appDir.path];

      try {
        await compute(_extraireArchiveIsolate, parametres);
      } catch (e, stack) {
        // Erreur catchable pendant l'extraction (pas un OOM tué par le système,
        // celui-là ne remonterait pas ici).
        await _ecrireCrashLog('Erreur extraction: $e\n$stack');
        rethrow;
      }

      final modelPathExistant = await _cheminModeleSiPresent();

      if (modelPathExistant == null) {
        setState(() {
          _sherpaEnCours = false;
          _sherpaStatus =
              "Le fichier sélectionné ne semble pas être le bon modèle. Vérifie que tu as choisi le fichier .zip du modèle Vosk téléchargé, et réessaie.";
        });
        return;
      }

      await _chargerModeleSherpa(modelPathExistant);
    } catch (e) {
      setState(() {
        _sherpaEnCours = false;
        _sherpaStatus = "Erreur lors de la sélection/extraction: $e";
      });
    }
  }

  /// TEST DIAGNOSTIC ISOLÉ : essaie de charger un petit modèle TensorFlow
  /// Lite (bibliothèque native complètement différente de sherpa-onnx et
  /// Vosk) pour vérifier si TOUTE bibliothèque native lourde crashe sur cet
  /// appareil, ou si c'est spécifique aux moteurs de reconnaissance vocale.
  Future<void> _testerTflite() async {
    setState(() {
      _tfliteTestResult = "Sélection du fichier .tflite...";
    });
    try {
      final result = await FilePicker.pickFiles();
      if (result == null) {
        setState(() {
          _tfliteTestResult = "Aucun fichier sélectionné.";
        });
        return;
      }
      final path = result.files.first.xFile.path;
      setState(() {
        _tfliteTestResult = "Chargement de l'interpréteur TFLite...";
      });

      final interpreter = tfl.Interpreter.fromFile(File(path));
      final nombreEntrees = interpreter.getInputTensors().length;
      interpreter.close();

      setState(() {
        _tfliteTestResult =
            "SUCCÈS : le modèle TFLite a été chargé sans crash (entrées: $nombreEntrees). Donc les bibliothèques natives lourdes fonctionnent en général sur cet appareil ; le problème est spécifique aux moteurs de reconnaissance vocale (sherpa-onnx et Vosk).";
      });
    } catch (e, stack) {
      await _ecrireCrashLog('Erreur test TFLite: $e\n$stack');
      setState(() {
        _tfliteTestResult = "Erreur (catchable, pas un crash total) : $e";
      });
    }
  }

  Future<void> _chargerModeleSherpa(String modelPath) async {
    try {
      setState(() {
        _sherpaEnCours = true;
        _sherpaStatus = "Chargement du modèle vocal (Vosk)...";
      });

      _voskModel = await _vosk.createModel(modelPath);
      _voskRecognizer = await _vosk.createRecognizer(
        model: _voskModel!,
        sampleRate: 16000,
      );

      setState(() {
        _sherpaReady = true;
        _sherpaEnCours = false;
        _sherpaNecessiteFichier = false;
        _sherpaStatus = '';
      });
    } catch (e) {
      setState(() {
        _sherpaEnCours = false;
        _sherpaStatus = "Erreur de chargement du modèle: $e";
      });
    }
  }

  /// Affiche un diagnostic combiné : statut sherpa + contenu du fichier
  /// crash_log.txt (s'il existe), directement dans le panneau caché.
  Future<void> _afficherDiagnosticSherpa() async {
    setState(() {
      _showDebugPanel = true;
      _debugSecretInfo = 'Chargement du diagnostic...';
    });

    String crashLogTexte = '(aucun fichier crash_log.txt trouvé)';
    try {
      final appDir = await getApplicationSupportDirectory();
      final crashFile = File('${appDir.path}/crash_log.txt');
      if (await crashFile.exists()) {
        crashLogTexte = await crashFile.readAsString();
        if (crashLogTexte.trim().isEmpty) {
          crashLogTexte = '(fichier crash_log.txt vide)';
        }
      }
    } catch (e) {
      crashLogTexte = 'Erreur lecture crash_log.txt: $e';
    }

    String listeFichiersModele = '(dossier modèle introuvable)';
    try {
      final appDir = await getApplicationSupportDirectory();
      final modelDir = Directory('${appDir.path}/$_sherpaModelDirName');
      if (await modelDir.exists()) {
        final entites = await modelDir.list().toList();
        if (entites.isEmpty) {
          listeFichiersModele = '(dossier vide)';
        } else {
          final lignes = <String>[];
          for (final entite in entites) {
            if (entite is File) {
              final taille = await entite.length();
              final nom = entite.path.split('/').last;
              lignes.add('$nom — $taille octets');
            } else {
              final nom = entite.path.split('/').last;
              lignes.add('$nom/ (dossier)');
            }
          }
          lignes.sort();
          listeFichiersModele = lignes.join('\n');
        }
      }
    } catch (e) {
      listeFichiersModele = 'Erreur listage dossier modèle: $e';
    }

    setState(() {
      _debugSecretInfo =
          'Sherpa prêt: $_sherpaReady\n\nStatut: $_sherpaStatus\n\n--- Fichiers dans $_sherpaModelDirName ---\n$listeFichiersModele\n\n--- crash_log.txt ---\n$crashLogTexte';
    });
  }

  /// Efface le fichier crash_log.txt (utile pour repartir propre avant un
  /// nouveau test).
  Future<void> _effacerCrashLog() async {
    try {
      final appDir = await getApplicationSupportDirectory();
      final crashFile = File('${appDir.path}/crash_log.txt');
      if (await crashFile.exists()) {
        await crashFile.delete();
      }
      setState(() {
        _debugSecretInfo = 'crash_log.txt effacé.';
      });
    } catch (e) {
      setState(() {
        _debugSecretInfo = 'Erreur suppression crash_log.txt: $e';
      });
    }
  }

  Future<void> _fetchDebugSecret() async {
    setState(() {
      _showDebugPanel = true;
      _debugSecretInfo = 'Recherche en cours...';
    });
    try {
      final result = await Process.run('logcat', ['-d']);
      final output = result.stdout.toString();
      final lines = output.split('\n');
      final secretLine = lines.firstWhere(
        (l) => l.toLowerCase().contains('debug secret'),
        orElse: () => '',
      );
      setState(() {
        _debugSecretInfo = secretLine.isEmpty
            ? 'Pas encore trouvé. Ferme et rouvre complètement l\'app, puis réessaie.'
            : secretLine;
      });
    } catch (e) {
      setState(() {
        _debugSecretInfo = 'Erreur lecture logs: $e';
      });
    }
  }

  Future<bool> _estConnecte() async {
    final connectivityResult = await Connectivity().checkConnectivity();
    return !connectivityResult.contains(ConnectivityResult.none);
  }

  String? _chercherCommandeLocale(String texte) {
    final texteMinuscule = texte.toLowerCase();
    for (final commande in _commandesLocales) {
      final motsCles = commande['motsCles'] as List<String>;
      final action = commande['action'] as String;
      final contientMotCle = motsCles.any((mot) => texteMinuscule.contains(mot));
      final contientAction = texteMinuscule.contains(action);
      if (contientMotCle && contientAction) {
        return commande['reponse'] as String;
      }
    }
    return null;
  }

  String? _chercherReponseFixe(String texte) {
    final texteMinuscule = texte.toLowerCase();
    for (final entree in _reponsesFixes) {
      final motsCles = entree['motsCles'] as List<String>;
      final contientMotCle = motsCles.any((mot) => texteMinuscule.contains(mot));
      if (contientMotCle) {
        return entree['reponse'] as String;
      }
    }
    return null;
  }

  String? _chercherCommandeSysteme(String texte) {
    final texteMinuscule = texte.toLowerCase();
    if (texteMinuscule.contains('quelle heure') ||
        texteMinuscule.contains("l'heure")) {
      final heure = DateFormat('HH:mm').format(DateTime.now());
      return "Il est $heure.";
    }
    if (texteMinuscule.contains('quel jour') ||
        texteMinuscule.contains('la date') ||
        texteMinuscule.contains("date d'aujourd'hui")) {
      final date = DateFormat('EEEE d MMMM y', 'fr_FR').format(DateTime.now());
      return "Nous sommes le $date.";
    }
    return null;
  }

  Future<bool> _essayerCommandeContact(String texte) async {
    final texteMinuscule = texte.toLowerCase();
    final motsDeclencheurs = ['appelle', 'appeler', 'ouvre le contact', 'ouvre contact'];

    final estCommandeContact = motsDeclencheurs.any((mot) => texteMinuscule.contains(mot));
    if (!estCommandeContact) return false;

    String nomRecherche = texteMinuscule;
    for (final mot in motsDeclencheurs) {
      nomRecherche = nomRecherche.replaceAll(mot, '');
    }
    nomRecherche = nomRecherche.trim();

    if (nomRecherche.isEmpty) {
      const message = "Dis-moi quel nom tu veux que j'appelle.";
      setState(() {
        _recognizedText = message;
      });
      await _speak(message);
      return true;
    }

    Contact? contactTrouve;
    for (final contact in _contacts) {
      if (contact.displayName.toLowerCase().contains(nomRecherche)) {
        contactTrouve = contact;
        break;
      }
    }

    if (contactTrouve == null || contactTrouve.phones.isEmpty) {
      final message = "Je n'ai pas trouvé de contact nommé $nomRecherche.";
      setState(() {
        _recognizedText = message;
      });
      await _speak(message);
      return true;
    }

    final numero = contactTrouve.phones.first.number;
    final message = "J'ouvre l'appel vers ${contactTrouve.displayName}.";
    setState(() {
      _recognizedText = message;
    });
    await _speak(message);

    final uri = Uri(scheme: 'tel', path: numero);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
    }
    return true;
  }

  Future<void> _initAssistant() async {
    final micStatus = await Permission.microphone.request();
    if (micStatus.isGranted) {
      _speechEnabled = await _speech.initialize(
        onError: (error) => debugPrint('Erreur reconnaissance: $error'),
        onStatus: (status) {
          if (status == 'done' || status == 'notListening') {
            setState(() => _isListening = false);
          }
        },
      );
      setState(() {});
      Future.delayed(const Duration(milliseconds: 500), () async {
        if (mounted) {
          await _speak(AppLocalizations.of(context)!.greeting);
        }
      });
    } else {
      setState(() {});
    }
  }

  Future<void> _speak(String text) async {
    await _tts.setLanguage('fr-FR');
    await _tts.setVolume(1.0);
    await _tts.setSpeechRate(0.5);
    await _tts.speak(text);
  }

  void _analyserEtRepondre(String texteEntendu) async {
    if (texteEntendu.trim().isEmpty) return;

    setState(() {
      _recognizedText = "Shadya réfléchit...";
    });

    final commandeContactTraitee = await _essayerCommandeContact(texteEntendu);
    if (commandeContactTraitee) return;

    final reponseDomotique = _chercherCommandeLocale(texteEntendu);
    if (reponseDomotique != null) {
      setState(() {
        _recognizedText = "Shadya : $reponseDomotique";
      });
      await _speak(reponseDomotique);
      return;
    }

    final reponseFixe = _chercherReponseFixe(texteEntendu);
    if (reponseFixe != null) {
      setState(() {
        _recognizedText = "Shadya : $reponseFixe";
      });
      await _speak(reponseFixe);
      return;
    }

    final reponseSysteme = _chercherCommandeSysteme(texteEntendu);
    if (reponseSysteme != null) {
      setState(() {
        _recognizedText = "Shadya : $reponseSysteme";
      });
      await _speak(reponseSysteme);
      return;
    }

    final connecte = await _estConnecte();

    if (!connecte) {
      const messageHorsLigne =
          "Je n'ai pas de connexion internet, je ne peux pas répondre à cette question maintenant.";
      setState(() {
        _recognizedText = messageHorsLigne;
      });
      await _speak(messageHorsLigne);
      return;
    }

    try {
      final promptInstructions =
          "Tu es Shadya, une assistante vocale chaleureuse et serviable. "
          "Réponds de manière amicale, naturelle et très courte (maximum 2 phrases). "
          "Voici la question de l'utilisateur : $texteEntendu";

      final content = [Content.text(promptInstructions)];
      final response = await _model.generateContent(content);
      final reponseIA = response.text ?? "Je n'ai pas pu formuler de réponse.";

      setState(() {
        _recognizedText = "Shadya : $reponseIA";
      });

      await _speak(reponseIA);
    } catch (e) {
      debugPrint("Erreur Gemini API: $e");

      final erreurTexte = e.toString().toLowerCase();
      String messageErreur;

      if (erreurTexte.contains('quota') || erreurTexte.contains('429')) {
        messageErreur =
            "Je suis très sollicitée en ce moment. Réessaie dans une minute.";
      } else if (erreurTexte.contains('500') ||
          erreurTexte.contains('internal') ||
          erreurTexte.contains('high demand')) {
        messageErreur =
            "Le service est momentanément occupé. Réessaie dans quelques instants.";
      } else {
        messageErreur =
            "Je n'ai pas pu contacter le service en ligne pour cette question.";
      }

      setState(() {
        _recognizedText = messageErreur;
      });
      await _speak(messageErreur);
    }
  }

  void _toggleListening() async {
    if (!_speechEnabled) {
      await _speak(AppLocalizations.of(context)!.microphonePermissionDenied);
      return;
    }
    if (_isListening) {
      await _speech.stop();
      setState(() => _isListening = false);
    } else {
      setState(() {
        _isListening = true;
        _recognizedText = '';
      });
      await _speech.listen(
        onResult: (result) {
          setState(() {
            _recognizedText = result.recognizedWords;
          });

          if (result.finalResult) {
            setState(() => _isListening = false);
            _analyserEtRepondre(result.recognizedWords);
          }
        },
        localeId: 'fr_FR',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final loc = AppLocalizations.of(context)!;
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const SizedBox(height: 24),
              GestureDetector(
                onLongPress: _afficherDiagnosticSherpa,
                child: Text(
                  loc.appTitle,
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
              ),
              const SizedBox(height: 24),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 32),
                child: Text(
                  _isListening
                      ? loc.listeningPrompt
                      : (_recognizedText.isEmpty
                          ? loc.tapToSpeak
                          : _recognizedText),
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              if (_sherpaStatus.isNotEmpty && !_sherpaReady) ...[
                const SizedBox(height: 16),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    _sherpaStatus,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, color: Colors.grey),
                  ),
                ),
              ],
              if (_sherpaNecessiteFichier && !_sherpaEnCours) ...[
                const SizedBox(height: 16),
                ElevatedButton(
                  onPressed: _choisirEtExtraireModele,
                  child: const Text(
                      "Sélectionner le fichier du modèle vocal"),
                ),
              ],
              if (_sherpaModelPathDetecte != null &&
                  !_sherpaEnCours &&
                  !_sherpaReady) ...[
                const SizedBox(height: 16),
                ElevatedButton(
                  onPressed: () =>
                      _chargerModeleSherpa(_sherpaModelPathDetecte!),
                  child: const Text("Charger le modèle vocal"),
                ),
              ],
              const SizedBox(height: 24),
              OutlinedButton(
                onPressed: _testerTflite,
                child: const Text(
                    "TEST DIAGNOSTIC : charger un modèle TFLite"),
              ),
              if (_tfliteTestResult != null) ...[
                const SizedBox(height: 12),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    _tfliteTestResult!,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 12),
                  ),
                ),
              ],
              if (_showDebugPanel) ...[
                const SizedBox(height: 24),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: SelectableText(
                    _debugSecretInfo ?? '',
                    style: const TextStyle(fontSize: 11),
                    textAlign: TextAlign.center,
                  ),
                ),
                const SizedBox(height: 12),
                TextButton(
                  onPressed: _effacerCrashLog,
                  child: const Text(
                    "Effacer le fichier crash_log.txt",
                    style: TextStyle(fontSize: 11),
                  ),
                ),
              ],
              const SizedBox(height: 24),
              GestureDetector(
                onTap: _toggleListening,
                child: Container(
                  width: 140,
                  height: 140,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: _isListening
                        ? Theme.of(context).colorScheme.error
                        : Theme.of(context).colorScheme.primary,
                  ),
                  child: const Icon(
                    Icons.mic,
                    color: Colors.white,
                    size: 64,
                  ),
                ),
              ),
              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }
}
