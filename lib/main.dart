import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_ai/firebase_ai.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:intl/intl.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa_onnx;
import 'package:path_provider/path_provider.dart';
import 'package:http/http.dart' as http;
import 'package:archive/archive.dart';

import 'package:firebase_app_check/firebase_app_check.dart';
import 'firebase_options.dart';
import 'l10n/app_localizations.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );
  await FirebaseAppCheck.instance.activate(
    androidProvider: AndroidProvider.debug,
  );

  runApp(const ShadyaApp());
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

  // Sherpa-ONNX (en cours de préparation, pas encore utilisé pour l'écoute)
  static const String _sherpaModelUrl =
      'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-fr-2023-04-14.tar.bz2';
  static const String _sherpaModelDirName =
      'sherpa-onnx-streaming-zipformer-fr-2023-04-14';

  sherpa_onnx.OnlineRecognizer? _sherpaRecognizer;
  bool _sherpaReady = false;
  String _sherpaStatus = "Préparation de la reconnaissance vocale...";

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

    sherpa_onnx.initBindings();

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

  Future<String> _preparerModeleSherpa() async {
    final appDir = await getApplicationSupportDirectory();
    final modelDir = Directory('${appDir.path}/$_sherpaModelDirName');

    if (await modelDir.exists()) {
      final tokensFile = File('${modelDir.path}/tokens.txt');
      if (await tokensFile.exists()) {
        return modelDir.path;
      }
    }

    final connecte = await _estConnecte();
    if (!connecte) {
      throw Exception(
          "Connecte-toi à internet une première fois pour activer la reconnaissance vocale.");
    }

    setState(() {
      _sherpaStatus = "Téléchargement du modèle vocal (une seule fois)...";
    });

    final response = await http.get(Uri.parse(_sherpaModelUrl));
    if (response.statusCode != 200) {
      throw Exception(
          "Échec du téléchargement du modèle (code ${response.statusCode}).");
    }

    setState(() {
      _sherpaStatus = "Extraction du modèle vocal...";
    });

    final Uint8List bz2Bytes = response.bodyBytes;
    final tarBytes = BZip2Decoder().decodeBytes(bz2Bytes);
    final archive = TarDecoder().decodeBytes(tarBytes);

    await appDir.create(recursive: true);

    for (final file in archive) {
      final filePath = '${appDir.path}/${file.name}';
      if (file.isFile) {
        final outFile = File(filePath);
        await outFile.create(recursive: true);
        await outFile.writeAsBytes(file.content as List<int>);
      } else {
        await Directory(filePath).create(recursive: true);
      }
    }

    return modelDir.path;
  }

  Future<void> _initSherpa() async {
    final micStatus = await Permission.microphone.request();
    if (!micStatus.isGranted) {
      setState(() {
        _sherpaStatus = "Permission micro refusée.";
      });
      return;
    }

    try {
      final modelPath = await _preparerModeleSherpa();

      setState(() {
        _sherpaStatus = "Chargement du modèle vocal...";
      });

      final modelConfig = sherpa_onnx.OnlineModelConfig(
        transducer: sherpa_onnx.OnlineTransducerModelConfig(
          encoder: '$modelPath/encoder-epoch-21-avg-6.onnx',
          decoder: '$modelPath/decoder-epoch-21-avg-6.onnx',
          joiner: '$modelPath/joiner-epoch-21-avg-6.onnx',
        ),
        tokens: '$modelPath/tokens.txt',
        modelType: 'zipformer',
      );

      final config = sherpa_onnx.OnlineRecognizerConfig(model: modelConfig);
      _sherpaRecognizer = sherpa_onnx.OnlineRecognizer(config);

      setState(() {
        _sherpaReady = true;
        _sherpaStatus = '';
      });
    } catch (e) {
      setState(() {
        _sherpaStatus = "Erreur d'initialisation vocale: $e";
      });
    }
  }

  Future<void> _afficherDiagnosticSherpa() async {
    setState(() {
      _showDebugPanel = true;
      _debugSecretInfo =
          'Sherpa prêt: $_sherpaReady\n\nStatut: $_sherpaStatus';
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
