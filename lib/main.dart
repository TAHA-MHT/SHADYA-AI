import 'package:flutter/material.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';

import 'l10n/app_localizations.dart';

void main() {
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
          seedColor: const Color(0xFF2E6BE6),
          brightness: Brightness.light,
        ),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF2E6BE6),
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

  bool _speechEnabled = false;
  bool _isListening = false;
  String _recognizedText = '';

  @override
  void initState() {
    super.initState();
    _initAssistant();
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
      await _speak(AppLocalizations.of(context)!.greeting);
    } else {
      setState(() {});
    }
  }

  Future<void> _speak(String text) async {
    await _tts.setLanguage('fr-FR');
    await _tts.setSpeechRate(0.5);
    await _tts.speak(text);
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
          setState(() => _recognizedText = result.recognizedWords);
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
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              loc.appTitle,
              style: Theme.of(context).textTheme.headlineMedium,
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
            const SizedBox(height: 48),
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
          ],
        ),
      ),
    );
  }
}
