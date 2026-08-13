import 'dart:async';
import 'dart:convert';
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
import 'package:http/http.dart' as http;
import 'package:vosk_flutter_2/vosk_flutter_2.dart' as vosk;
import 'package:tflite_flutter/tflite_flutter.dart' as tfl;
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';
import 'package:archive/archive_io.dart';
import 'package:file_picker/file_picker.dart';

import 'package:firebase_app_check/firebase_app_check.dart';
import 'firebase_options.dart';
import 'l10n/app_localizations.dart';
import 'services/device_registry.dart';
import 'services/tuya_service.dart';

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
/// blocs successifs, sans jamais charger le fichier .tar.bz2 entier (398 Mo)
/// en mémoire d'un coup.
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
  vosk.SpeechService? _voskSpeechService;
  bool _sherpaReady = false;
  bool _sherpaEnCours = false;
  bool _sherpaNecessiteFichier = false;
  String? _sherpaModelPathDetecte;
  String _sherpaStatus = "Préparation de la reconnaissance vocale...";
  String? _tfliteTestResult;

  final String _villeParDefaut = "N'Djamena";

  Timer? _minuteurActif;

  final String _urlServeurSTT = '';
  AudioRecorder? _recorder;
  bool _enregistrementServeurEnCours = false;

  late final Map<String, int> _dictionnaireNombres = _construireDictionnaireNombres();

  final TuyaService _tuyaService = TuyaService();

  final Map<String, DeviceType> _correspondanceAppareils = {
    'lumière': DeviceType.light,
    'lumiere': DeviceType.light,
    'ventilateur': DeviceType.fan,
    'climatiseur': DeviceType.airConditioner,
    'clim': DeviceType.airConditioner,
  };

  Future<String?> _essayerCommandeTuya(String texte) async {
    final texteMinuscule = texte.toLowerCase();

    DeviceType? appareilTrouve;
    for (final entree in _correspondanceAppareils.entries) {
      if (texteMinuscule.contains(entree.key)) {
        appareilTrouve = entree.value;
        break;
      }
    }
    if (appareilTrouve == null) return null;

    final estAllumage = texteMinuscule.contains('allume');
    final estExtinction =
        texteMinuscule.contains('éteins') || texteMinuscule.contains('eteins');
    if (!estAllumage && !estExtinction) return null;

    final appareil = DeviceRegistry.devices[appareilTrouve];
    if (appareil == null || appareil.deviceId.isEmpty) {
      return null;
    }

    try {
      final bool succes = estAllumage
          ? await _tuyaService.turnOn(appareil.deviceId)
          : await _tuyaService.turnOff(appareil.deviceId);

      if (succes) {
        return estAllumage
            ? "D'accord, j'ai allumé l'appareil."
            : "D'accord, j'ai éteint l'appareil.";
      } else {
        return "Je n'ai pas pu contacter l'appareil, réessaie.";
      }
    } catch (e) {
      return "Il y a un problème de connexion avec l'appareil.";
    }
  }

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
          "Je peux contrôler des appareils chez toi, appeler tes contacts, calculer, te donner la météo, lancer un minuteur, et répondre à tes questions.",
    },
    {
      'motsCles': ['tu es hors ligne', 'pas de connexion', 'pas internet'],
      'reponse':
          "Je fonctionne même sans connexion pour les commandes de base comme la domotique, les calculs et les minuteurs.",
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

  @override
  void dispose() {
    _minuteurActif?.cancel();
    _recorder?.dispose();
    super.dispose();
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

  String _extraireTextePartiel(String jsonBrut) {
    final match = RegExp(r'"partial"\s*:\s*"([^"]*)"').firstMatch(jsonBrut);
    return match?.group(1) ?? '';
  }

  String _extraireTexteFinal(String jsonBrut) {
    final match = RegExp(r'"text"\s*:\s*"([^"]*)"').firstMatch(jsonBrut);
    return match?.group(1) ?? '';
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
      _voskSpeechService = await _vosk.initSpeechService(_voskRecognizer!);

      _voskSpeechService!.onPartial().listen((partial) {
        final texte = _extraireTextePartiel(partial);
        if (texte.isNotEmpty) {
          setState(() {
            _recognizedText = texte;
          });
        }
      });

      _voskSpeechService!.onResult().listen((result) {
        final texte = _extraireTexteFinal(result);
        if (texte.isNotEmpty) {
          _voskSpeechService!.stop();
          setState(() => _isListening = false);
          _analyserEtRepondre(texte);
        }
      });

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

  /// Ouvre une application externe avec url_launcher
  Future<bool> _essayerOuvrirApplication(String texte) async {
    final texteMinuscule = texte.toLowerCase().trim();

    final estCommandeOuverture = RegExp(r'\b(ouvre|lancer|lance|démarre|demarre)\b').hasMatch(texteMinuscule);
    if (!estCommandeOuverture) return false;

    final appsSchemes = <String, String>{
      'whatsapp': 'whatsapp://',
      'facebook': 'fb://',
      'youtube': 'https://www.youtube.com',
      'instagram': 'instagram://',
      'chrome': 'https://www.google.com',
    };

    for (final entry in appsSchemes.entries) {
      if (texteMinuscule.contains(entry.key)) {
        final nomApp = entry.key;
        final uri = Uri.parse(entry.value);

        await _speak("J'ouvre $nomApp");

        try {
          if (await canLaunchUrl(uri)) {
            await launchUrl(uri, mode: LaunchMode.externalApplication);
            return true;
          } else {
            final fallbackUri = Uri.parse('https://www.$nomApp.com');
            await launchUrl(fallbackUri, mode: LaunchMode.externalApplication);
            return true;
          }
        } catch (e) {
          debugPrint("Erreur ouverture app: $e");
        }
      }
    }

    return false;
  }

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

  Future<bool> _estConnecte() async {
    final connectivityResult = await Connectivity().checkConnectivity();
    return !connectivityResult.contains(ConnectivityResult.none);
  }

  Map<String, int> _construireDictionnaireNombres() {
    final dict = <String, int>{};
    const unites = [
      'zéro', 'un', 'deux', 'trois', 'quatre', 'cinq', 'six', 'sept', 'huit', 'neuf'
    ];
    const onzeADixNeuf = [
      'dix', 'onze', 'douze', 'treize', 'quatorze', 'quinze', 'seize',
      'dix sept', 'dix huit', 'dix neuf'
    ];

    for (int i = 0; i < 10; i++) {
      dict[unites[i]] = i;
    }
    for (int i = 0; i < 10; i++) {
      dict[onzeADixNeuf[i]] = 10 + i;
    }

    const dizaines = {20: 'vingt', 30: 'trente', 40: 'quarante', 50: 'cinquante', 60: 'soixante'};
    dizaines.forEach((valeur, mot) {
      dict[mot] = valeur;
      for (int u = 1; u < 10; u++) {
        final liaison = (u == 1) ? 'et un' : unites[u];
        dict['$mot $liaison'] = valeur + u;
      }
    });

    dict['soixante dix'] = 70;
    dict['soixante et onze'] = 71;
    for (int u = 2; u < 10; u++) {
      dict['soixante ${onzeADixNeuf[u]}'] = 70 + u;
    }

    dict['quatre vingt'] = 80;
    dict['quatre vingts'] = 80;
    for (int u = 1; u < 10; u++) {
      dict['quatre vingt ${unites[u]}'] = 80 + u;
    }

    for (int u = 0; u < 10; u++) {
      dict['quatre vingt ${onzeADixNeuf[u]}'] = 90 + u;
    }

    return dict;
  }

  int? _extraireNombreDepuisMots(String texteBrut) {
    var texte = texteBrut.toLowerCase().replaceAll('-', ' ');
    texte = texte.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (texte.isEmpty) return null;
    return _parserMillions(texte);
  }

  int? _parserMillions(String texte) {
    final direct = int.tryParse(texte.replaceAll(' ', ''));
    if (direct != null) return direct;
    if (texte.isEmpty) return null;

    final mots = texte.split(' ');
    final idx = mots.indexWhere((m) => m == 'million' || m == 'millions');
    if (idx != -1) {
      final avant = mots.sublist(0, idx).join(' ').trim();
      final apres = mots.sublist(idx + 1).join(' ').trim();
      final multiplicateur = avant.isEmpty ? 1 : (_parserMilliers(avant) ?? 1);
      final reste = apres.isEmpty ? 0 : (_parserMilliers(apres) ?? 0);
      return multiplicateur * 1000000 + reste;
    }

    return _parserMilliers(texte);
  }

  int? _parserMilliers(String texte) {
    final direct = int.tryParse(texte.replaceAll(' ', ''));
    if (direct != null) return direct;
    if (texte.isEmpty) return null;

    final mots = texte.split(' ');
    final idx = mots.indexWhere((m) => m == 'mille');
    if (idx != -1) {
      final avant = mots.sublist(0, idx).join(' ').trim();
      final apres = mots.sublist(idx + 1).join(' ').trim();
      final multiplicateur = avant.isEmpty ? 1 : (_parserCentaines(avant) ?? 1);
      final reste = apres.isEmpty ? 0 : (_parserCentaines(apres) ?? 0);
      return multiplicateur * 1000 + reste;
    }

    return _parserCentaines(texte);
  }

  int? _parserCentaines(String texte) {
    final direct = int.tryParse(texte.replaceAll(' ', ''));
    if (direct != null) return direct;
    if (texte.isEmpty) return null;

    final centMatch = RegExp(r'^(.*?)\s*cents?\s*(.*)$').firstMatch(texte);
    if (centMatch != null) {
      final avant = centMatch.group(1)!.trim();
      final apres = centMatch.group(2)!.trim();
      final multiplicateur = avant.isEmpty ? 1 : (_dictionnaireNombres[avant] ?? 1);
      final reste = apres.isEmpty
          ? 0
          : (_dictionnaireNombres[apres] ?? int.tryParse(apres) ?? 0);
      return multiplicateur * 100 + reste;
    }

    return _dictionnaireNombres[texte];
  }

  int? _extraireNombreDansSegment(String segment, {required bool depuisLaFin}) {
    final mots = segment
        .trim()
        .split(RegExp(r'\s+'))
        .where((m) => m.isNotEmpty)
        .toList();
    if (mots.isEmpty) return null;

    final tailleMax = mots.length < 4 ? mots.length : 4;
    for (int taille = tailleMax; taille >= 1; taille--) {
      final sousMots = depuisLaFin
          ? mots.sublist(mots.length - taille)
          : mots.sublist(0, taille);
      var candidat = sousMots.join(' ');
      candidat = candidat.replaceFirst(RegExp(r"^(de|d')\s+"), '').trim();
      final n = _extraireNombreDepuisMots(candidat);
      if (n != null) return n;
    }
    return null;
  }

  String? _chercherCalcul(String texte) {
    final texteMin = texte.toLowerCase();

    const motsDeclencheurs = ['combien font', 'combien fait', 'combien ça fait', "c'est combien"];
    final aOperateur = RegExp(r'\b(plus|moins|fois|multiplié par|divisé par|divise par)\b').hasMatch(texteMin);
    final estCalcul = motsDeclencheurs.any((m) => texteMin.contains(m)) || aOperateur;
    if (!estCalcul) return null;

    const operateurs = {
      'multiplié par': '×',
      'divisé par': '÷',
      'divise par': '÷',
      'fois': '×',
      'plus': '+',
      'moins': '−',
    };

    for (final entree in operateurs.entries) {
      final motOperateur = entree.key;
      final indexOp = texteMin.indexOf(' $motOperateur ');
      if (indexOp == -1) continue;

      var partieGauche = texteMin.substring(0, indexOp);
      for (final mot in motsDeclencheurs) {
        partieGauche = partieGauche.replaceAll(mot, '');
      }
      partieGauche = partieGauche.trim();

      final partieDroite = texteMin.substring(indexOp + motOperateur.length + 2).trim();

      final nombre1 = _extraireNombreDansSegment(partieGauche, depuisLaFin: true);
      final nombre2 = _extraireNombreDansSegment(partieDroite, depuisLaFin: false);
      if (nombre1 == null || nombre2 == null) continue;

      double resultat;
      switch (entree.value) {
        case '+':
          resultat = (nombre1 + nombre2).toDouble();
          break;
        case '−':
          resultat = (nombre1 - nombre2).toDouble();
          break;
        case '×':
          resultat = (nombre1 * nombre2).toDouble();
          break;
        case '÷':
          if (nombre2 == 0) return "Je ne peux pas diviser par zéro.";
          resultat = nombre1 / nombre2;
          break;
        default:
          continue;
      }

      final resultatFormate = resultat == resultat.roundToDouble()
          ? resultat.toInt().toString()
          : resultat.toStringAsFixed(2);

      return "$nombre1 ${entree.value} $nombre2, ça fait $resultatFormate.";
    }

    return null;
  }

  bool _estCommandeMinuteur(String texte) {
    final t = texte.toLowerCase();
    return t.contains('minuteur') ||
        t.contains('minuterie') ||
        (t.contains('lance') && (t.contains('minute') || t.contains('seconde')));
  }

  String _demarrerMinuteur(String texte) {
    final t = texte.toLowerCase().replaceAll('-', ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
    final mots = t.split(' ');

    int? valeurAvantMot(bool Function(String) estMotUnite) {
      final idx = mots.indexWhere(estMotUnite);
      if (idx <= 0) return null;
      final segmentAvant = mots.sublist(0, idx).join(' ');
      return _extraireNombreDansSegment(segmentAvant, depuisLaFin: true);
    }

    int totalSecondes = 0;
    final minutesVal = valeurAvantMot((m) => m == 'minute' || m == 'minutes');
    if (minutesVal != null) totalSecondes += minutesVal * 60;
    final secondesVal = valeurAvantMot((m) => m == 'seconde' || m == 'secondes');
    if (secondesVal != null) totalSecondes += secondesVal;

    if (totalSecondes <= 0) {
      return "Je n'ai pas compris la durée du minuteur. Essaie par exemple : lance un minuteur de cinq minutes.";
    }

    _minuteurActif?.cancel();
    final dureeCapturee = totalSecondes;
    _minuteurActif = Timer(Duration(seconds: dureeCapturee), () {
      if (mounted) {
        setState(() {
          _recognizedText = "⏰ Le minuteur est terminé.";
        });
        _speak("Le minuteur est terminé.");
      }
    });

    final dureeTexte = totalSecondes >= 60
        ? "${totalSecondes ~/ 60} minute(s)"
            "${totalSecondes % 60 > 0 ? ' et ${totalSecondes % 60} seconde(s)' : ''}"
        : "$totalSecondes seconde(s)";

    return "D'accord, minuteur lancé pour $dureeTexte.";
  }

  String _descriptionMeteo(int code) {
    if (code == 0) return "ciel dégagé";
    if (code <= 3) return "partiellement nuageux";
    if (code <= 48) return "brumeux";
    if (code <= 57) return "bruine";
    if (code <= 67) return "pluie";
    if (code <= 77) return "neige";
    if (code <= 82) return "averses";
    if (code <= 99) return "orageux";
    return "conditions variables";
  }

  Future<String> _obtenirMeteo(String texte) async {
    var ville = _villeParDefaut;
    final matchVille = RegExp(r'(?:météo|meteo).*?(?:à|a|de|pour)\s+([a-zà-ÿ\s\-]+)$')
        .firstMatch(texte.toLowerCase());
    if (matchVille != null) {
      ville = matchVille.group(1)!.trim();
    }

    try {
      final geoUrl = Uri.parse(
          'https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encodeComponent(ville)}&count=1&language=fr&format=json');
      final geoResponse = await http.get(geoUrl).timeout(const Duration(seconds: 8));
      final geoData = jsonDecode(geoResponse.body) as Map<String, dynamic>;

      final resultats = geoData['results'] as List<dynamic>?;
      if (resultats == null || resultats.isEmpty) {
        return "Je n'ai pas trouvé la ville \"$ville\" pour la météo.";
      }

      final lieu = resultats.first as Map<String, dynamic>;
      final lat = lieu['latitude'];
      final lon = lieu['longitude'];
      final nomVilleTrouvee = lieu['name'];

      final meteoUrl = Uri.parse(
          'https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true');
      final meteoResponse = await http.get(meteoUrl).timeout(const Duration(seconds: 8));
      final meteoData = jsonDecode(meteoResponse.body) as Map<String, dynamic>;

      final tempsActuel = meteoData['current_weather'] as Map<String, dynamic>;
      final temperature = tempsActuel['temperature'];
      final codeWeather = (tempsActuel['weathercode'] as num).toInt();

      final description = _descriptionMeteo(codeWeather);

      return "À $nomVilleTrouvee, il fait actuellement $temperature degrés, $description.";
    } catch (e) {
      return "Je n'ai pas pu récupérer la météo pour le moment.";
    }
  }

  bool _estCommandeRecherche(String texte) {
    final t = texte.toLowerCase();
    return t.contains('cherche moi') ||
        t.contains('cherche-moi') ||
        t.contains('recherche') ||
        (t.contains('cherche') && !t.contains('chercheur'));
  }

  Future<String> _rechercheGemini(String texte) async {
    try {
      final prompt =
          "L'utilisateur te demande de faire une recherche d'information sur : \"$texte\". "
          "Réponds avec les informations les plus précises et utiles que tu connais, "
          "de façon claire et concise (3-4 phrases maximum). "
          "Si tu n'es pas certain de l'information la plus récente sur ce sujet, précise-le brièvement.";
      final content = [Content.text(prompt)];
      final response = await _model.generateContent(content);
      return response.text ?? "Je n'ai pas trouvé d'information sur ce sujet.";
    } catch (e, stack) {
      debugPrint("Erreur recherche Gemini: $e");
      await _ecrireCrashLog('Erreur recherche Gemini: $e\n$stack');
      return "Je n'ai pas pu effectuer la recherche pour le moment.";
    }
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

    // Intercepte les ouvertures d'applications avant tout le reste
    final appOuverte = await _essayerOuvrirApplication(texteEntendu);
    if (appOuverte) {
      return;
    }

    setState(() {
      _recognizedText = "Shadya réfléchit...";
    });

    final commandeContactTraitee = await _essayerCommandeContact(texteEntendu);
    if (commandeContactTraitee) return;

    final reponseTuya = await _essayerCommandeTuya(texteEntendu);
    if (reponseTuya != null) {
      setState(() {
        _recognizedText = "Shadya : $reponseTuya";
      });
      await _speak(reponseTuya);
      return;
    }

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

    final resultatCalcul = _chercherCalcul(texteEntendu);
    if (resultatCalcul != null) {
      setState(() {
        _recognizedText = "Shadya : $resultatCalcul";
      });
      await _speak(resultatCalcul);
      return;
    }

    if (_estCommandeMinuteur(texteEntendu)) {
      final resultatMinuteur = _demarrerMinuteur(texteEntendu);
      setState(() {
        _recognizedText = "Shadya : $resultatMinuteur";
      });
      await _speak(resultatMinuteur);
      return;
    }

    final texteMinuscule = texteEntendu.toLowerCase();
    final estMeteo = texteMinuscule.contains('météo') ||
        texteMinuscule.contains('meteo') ||
        texteMinuscule.contains('quel temps');
    if (estMeteo) {
      final connecteMeteo = await _estConnecte();
      if (!connecteMeteo) {
        const messageMeteo =
            "La météo nécessite une connexion internet, et je n'en ai pas actuellement.";
        setState(() {
          _recognizedText = messageMeteo;
        });
        await _speak(messageMeteo);
        return;
      }
      final resultatMeteo = await _obtenirMeteo(texteEntendu);
      setState(() {
        _recognizedText = "Shadya : $resultatMeteo";
      });
      await _speak(resultatMeteo);
      return;
    }

    if (_estCommandeRecherche(texteEntendu)) {
      final connecteRecherche = await _estConnecte();
      if (!connecteRecherche) {
        const messageRecherche =
            "La recherche nécessite une connexion internet, et je n'en ai pas actuellement.";
        setState(() {
          _recognizedText = messageRecherche;
        });
        await _speak(messageRecherche);
        return;
      }
      setState(() {
        _recognizedText = "Shadya recherche...";
      });
      final resultatRecherche = await _rechercheGemini(texteEntendu);
      setState(() {
        _recognizedText = "Shadya : $resultatRecherche";
      });
      await _speak(resultatRecherche);
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

  Future<bool> _serveurJoignable() async {
    if (_urlServeurSTT.trim().isEmpty) return false;
    try {
      final response = await http
          .get(Uri.parse('$_urlServeurSTT/status'))
          .timeout(const Duration(seconds: 3));
      return response.statusCode == 200;
    } catch (_) {
      return false;
    }
  }

  Future<void> _demarrerEnregistrementServeur() async {
    _recorder ??= AudioRecorder();

    final permissionOk = await _recorder!.hasPermission();
    if (!permissionOk) {
      setState(() {
        _recognizedText = "Permission micro refusée pour l'enregistrement.";
      });
      return;
    }

    final tempDir = await getTemporaryDirectory();
    final chemin = '${tempDir.path}/shadya_question.wav';

    await _recorder!.start(
      const RecordConfig(
        encoder: AudioEncoder.wav,
        sampleRate: 16000,
        numChannels: 1,
      ),
      path: chemin,
    );

    setState(() {
      _enregistrementServeurEnCours = true;
      _isListening = true;
      _recognizedText = '';
    });
  }

  Future<void> _arreterEtEnvoyerAuServeur() async {
    if (_recorder == null) return;

    final chemin = await _recorder!.stop();
    setState(() {
      _enregistrementServeurEnCours = false;
      _isListening = false;
      _recognizedText = "Envoi au serveur...";
    });

    if (chemin == null) return;

    try {
      final uri = Uri.parse('$_urlServeurSTT/reconnaitre');
      final requete = http.MultipartRequest('POST', uri);
      requete.files.add(await http.MultipartFile.fromPath('audio', chemin));

      final reponseFlux = await requete.send().timeout(const Duration(seconds: 15));
      final reponse = await http.Response.fromStream(reponseFlux);

      if (reponse.statusCode == 200) {
        final data = jsonDecode(reponse.body) as Map<String, dynamic>;
        final texte = (data['text'] as String?) ?? '';
        if (texte.isNotEmpty) {
          _analyserEtRepondre(texte);
          return;
        }
      }

      setState(() {
        _recognizedText =
            "Le serveur n'a pas pu reconnaître la phrase. Réessaie.";
      });
    } catch (e) {
      setState(() {
        _recognizedText =
            "Impossible de contacter le serveur. Vérifie qu'il est bien allumé et sur le même réseau.";
      });
    }
  }

  void _toggleListening() async {
    if (_urlServeurSTT.trim().isNotEmpty) {
      if (_enregistrementServeurEnCours) {
        await _arreterEtEnvoyerAuServeur();
        return;
      }
      final joignable = await _serveurJoignable();
      if (joignable) {
        await _demarrerEnregistrementServeur();
        return;
      }
      setState(() {
        _recognizedText =
            "Serveur injoignable, reconnaissance locale utilisée à la place.";
      });
    }

    if (_sherpaReady && _voskSpeechService != null) {
      if (_isListening) {
        await _voskSpeechService!.stop();
        setState(() => _isListening = false);
      } else {
        setState(() {
          _isListening = true;
          _recognizedText = '';
        });
        await _voskSpeechService!.start();
      }
      return;
    }

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
