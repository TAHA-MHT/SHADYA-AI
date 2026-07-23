import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_ai/firebase_ai.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:intl/intl.dart';
import 'package:vosk_flutter/vosk_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:firebase_app_check/firebase_app_check.dart';
import 'firebase_options.dart';
import 'l10n/app_localizations.dart';

Future<void> main() async {
  runZonedGuarded<Future<void>>(() async {
    WidgetsFlutterBinding.ensureInitialized();

    FlutterError.onError = (FlutterErrorDetails details) {
      _sauvegarderErreurFatale('FlutterError: ${details.exceptionAsString()}');
    };

    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );
    await FirebaseAppCheck.instance.activate(
      androidProvider: AndroidProvider.debug,
    );

    runApp(const ShadyaApp());
  }, (error, stackTrace) {
    _sauvegarderErreurFatale('Erreur non gérée: $error');
  });
}

Future<void> _sauvegarderErreurFatale(String message) async {
  try {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('derniere_erreur_fatale', message);
    await prefs.setString(
      'derniere_erreur_date',
      DateTime.now().toIso8601String(),
    );
  } catch (_) {
    // Si même ça échoue, on ne peut rien faire de plus.
  }
}

Future<void> _sauvegarderEtape(String etape) async {
  try {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('derniere_etape', etape);
  } catch (_) {}
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
  final FlutterTts _tts = FlutterTts();

  late final GenerativeModel _model;

  // Vosk
  final VoskFlutterPlugin _vosk = VoskFlutterPlugin.instance();
  Model? _voskModel;
  Recognizer? _recognizer;
  SpeechService? _speechService;
  bool _voskReady = false;
  String _voskStatus = "Préparation de la reconnaissance vocale...";

  static const String _voskModelName = 'vosk-model-small-fr-0.22';
  static const String _voskModelUrl =
      'https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip';
  static const int _voskSampleRate = 16000;

  bool _isListening = false;
  String _recognizedText = '';
  String? _debugSecretInfo;
  bool _showDebugPanel = false;

  List<Contact> _contacts = [];

  // Commandes domotique locales (offline)
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

  // Réponses fixes locales : salutations, remerciements, FAQ sur l'app
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
    await _loadContacts();
    await _initVosk();
  }

  Future<void> _loadContacts() async {
    if (await FlutterContacts.requestPermission()) {
      final contacts = await FlutterContacts.getContacts(withProperties: true);
      setState(() {
        _contacts = contacts;
      });
    }
  }

  Future<void> _initVosk() async {
    await _sauvegarderEtape('Début _initVosk');

    final micStatus = await Permission.microphone.request();
    if (!micStatus.isGranted) {
      setState(() {
        _voskStatus = "Permission micro refusée.";
      });
      return;
    }
    await _sauvegarderEtape('Permission micro accordée');

    final modelLoader = ModelLoader();
    String? modelPath;

    try {
      await _sauvegarderEtape('Avant modelLoader.modelPath()');
      modelPath = await modelLoader.modelPath(_voskModelName);
      await _sauvegarderEtape('Après modelLoader.modelPath() : $modelPath');
    } catch (_) {
      modelPath = null;
      await _sauvegarderEtape('modelPath() a échoué, pas encore en cache');
    }

    if (modelPath == null) {
      final connecte = await _estConnecte();
      if (!connecte) {
        setState(() {
          _voskStatus =
              "Connecte-toi à internet une première fois pour activer la reconnaissance vocale.";
        });
        return;
      }

      try {
        setState(() {
          _voskStatus = "Téléchargement du modèle vocal (une seule fois)...";
        });
        await _sauvegarderEtape('Avant loadFromNetwork');
        modelPath = await modelLoader.loadFromNetwork(_voskModelUrl);
        await _sauvegarderEtape('Après loadFromNetwork : $modelPath');
      } catch (e) {
        await _sauvegarderEtape('loadFromNetwork a échoué: $e');
        setState(() {
          _voskStatus = "Erreur de téléchargement du modèle vocal: $e";
        });
        return;
      }
    }

    try {
      setState(() {
        _voskStatus = "Chargement du modèle vocal...";
      });

      await _sauvegarderEtape('Avant vosk.createModel');
      final model = await _vosk.createModel(modelPath);
      await _sauvegarderEtape('Après vosk.createModel');

      await _sauvegarderEtape('Avant vosk.createRecognizer');
      final recognizer = await _vosk.createRecognizer(
        model: model,
        sampleRate: _voskSampleRate,
      );
      await _sauvegarderEtape('Après vosk.createRecognizer');

      setState(() {
        _voskModel = model;
        _recognizer = recognizer;
        _voskReady = true;
        _voskStatus = '';
      });

      await _sauvegarderEtape('Vosk prêt avec succès');

      Future.delayed(const Duration(milliseconds: 500), () async {
        if (mounted) {
          await _speak(AppLocalizations.of(context)!.greeting);
        }
      });
    } catch (e) {
      await _sauvegarderEtape('createModel/createRecognizer a échoué: $e');
      setState(() {
        _voskStatus = "Erreur d'initialisation vocale: $e";
      });
    }
  }

  Future<void> _afficherDiagnostic() async {
    final prefs = await SharedPreferences.getInstance();
    final derniereEtape = prefs.getString('derniere_etape') ?? 'Aucune';
    final derniereErreur =
        prefs.getString('derniere_erreur_fatale') ?? 'Aucune';
    final dateErreur = prefs.getString('derniere_erreur_date') ?? '';

    setState(() {
      _showDebugPanel = true;
      _debugSecretInfo =
          'Dernière étape atteinte: $derniereEtape\n\nDernière erreur fatale: $derniereErreur\n\nDate: $dateErreur';
    });
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
    if (!_voskReady || _recognizer == null) {
      await _speak("La reconnaissance vocale n'est pas encore prête.");
      return;
    }

    if (_isListening) {
      await _speechService?.stop();
      setState(() => _isListening = false);

      final resultJson = await _recognizer!.getFinalResult();
      _handleVoskResult(resultJson);
    } else {
      setState(() {
        _isListening = true;
        _recognizedText = '';
      });

      _speechService ??= await _vosk.initSpeechService(_recognizer!);

      _speechService!.onPartial().forEach((partial) {
        final texte = _extraireTexteVosk(partial, cle: 'partial');
        if (texte.isNotEmpty) {
          setState(() {
            _recognizedText = texte;
          });
        }
      });

      _speechService!.onResult().forEach((result) {
        _handleVoskResult(result);
      });

      await _speechService!.start();
    }
  }

  void _handleVoskResult(String resultJson) {
    final texte = _extraireTexteVosk(resultJson, cle: 'text');
    setState(() => _isListening = false);
    if (texte.isNotEmpty) {
      _analyserEtRepondre(texte);
    }
  }

  String _extraireTexteVosk(String json, {required String cle}) {
    final regex = RegExp('"$cle"\\s*:\\s*"([^"]*)"');
    final match = regex.firstMatch(json);
    return match?.group(1)?.trim() ?? '';
  }

  @override
  void dispose() {
    _speechService?.stop();
    super.dispose();
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
                onLongPress: _afficherDiagnostic,
                child: Text(
                  loc.appTitle,
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
              ),
              const SizedBox(height: 24),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 32),
                child: Text(
                  !_voskReady
                      ? _voskStatus
                      : (_isListening
                          ? loc.listeningPrompt
                          : (_recognizedText.isEmpty
                              ? loc.tapToSpeak
                              : _recognizedText)),
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
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
              ],
              const SizedBox(height: 24),
              GestureDetector(
                onTap: _toggleListening,
                child: Container(
                  width: 140,
                  height: 140,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: !_voskReady
                        ? Colors.grey
                        : (_isListening
                            ? Theme.of(context).colorScheme.error
                            : Theme.of(context).colorScheme.primary),
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
