import 'dart:io';
import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_ai/firebase_ai.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:url_launcher/url_launcher.dart';

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

  // Liste des commandes locales (mot-clés -> réponse), fonctionne sans internet
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
    _initAssistant();
    _loadContacts();
  }

  Future<void> _loadContacts() async {
    if (await FlutterContacts.requestPermission()) {
      final contacts = await FlutterContacts.getContacts(withProperties: true);
      setState(() {
        _contacts = contacts;
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

  // Cherche une commande locale correspondant au texte entendu (domotique)
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

  // Cherche si la phrase demande d'appeler ou d'ouvrir un contact
  // Retourne true si la commande a été traitée (trouvée ou non)
  Future<bool> _essayerCommandeContact(String texte) async {
    final texteMinuscule = texte.toLowerCase();
    final motsDeclencheurs = ['appelle', 'appeler', 'ouvre le contact', 'ouvre contact'];

    final estCommandeContact = motsDeclencheurs.any((mot) => texteMinuscule.contains(mot));
    if (!estCommandeContact) return false;

    // On retire les mots déclencheurs pour isoler le nom recherché
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

    // Priorité 1 : commande de contact/appel (fonctionne même hors ligne)
    final commandeContactTraitee = await _essayerCommandeContact(texteEntendu);
    if (commandeContactTraitee) return;

    final connecte = await _estConnecte();

    if (!connecte) {
      // Mode hors ligne : on cherche une commande domotique locale connue
      final reponseLocale = _chercherCommandeLocale(texteEntendu);
      if (reponseLocale != null) {
        setState(() {
          _recognizedText = "Shadya : $reponseLocale";
        });
        await _speak(reponseLocale);
      } else {
        const messageHorsLigne =
            "Je n'ai pas de connexion internet, je ne peux pas répondre à cette question maintenant.";
        setState(() {
          _recognizedText = messageHorsLigne;
        });
        await _speak(messageHorsLigne);
      }
      return;
    }

    // Mode en ligne : on passe par Gemini comme avant
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
      final reponseLocale = _chercherCommandeLocale(texteEntendu);
      final messageErreur = reponseLocale ??
          "Une erreur est survenue, réessaie dans un instant.";
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
                onLongPress: _fetchDebugSecret,
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
