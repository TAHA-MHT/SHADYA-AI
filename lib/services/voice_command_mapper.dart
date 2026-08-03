import 'device_registry.dart';

enum ActionType { turnOn, turnOff }

class VoiceCommand {
  final DeviceType device;
  final ActionType action;

  const VoiceCommand({required this.device, required this.action});
}

class VoiceCommandMapper {
  static final Map<DeviceType, List<String>> _deviceKeywords = {
    DeviceType.light: ['nur', 'noor', 'daw', 'day'],
    DeviceType.fan: ['marwah', 'marwa'],
    DeviceType.airConditioner: ['mukayyaf', 'mukayyif'],
  };

  static final List<String> _turnOnKeywords = [
    'walli', 'wale', 'shakhil', 'shakhal', 'dawwir',
  ];
  static final List<String> _turnOffKeywords = [
    'aktul', 'aktol', 'battil', 'waggif', 'sidd',
  ];

  static VoiceCommand? parse(String recognizedText) {
    final text = recognizedText.toLowerCase().trim();
    if (text.isEmpty) return null;

    DeviceType? matchedDevice;
    for (final entry in _deviceKeywords.entries) {
      if (entry.value.any((kw) => text.contains(kw))) {
        matchedDevice = entry.key;
        break;
      }
    }
    if (matchedDevice == null) return null;

    ActionType? matchedAction;
    if (_turnOnKeywords.any((kw) => text.contains(kw))) {
      matchedAction = ActionType.turnOn;
    } else if (_turnOffKeywords.any((kw) => text.contains(kw))) {
      matchedAction = ActionType.turnOff;
    }
    if (matchedAction == null) return null;

    return VoiceCommand(device: matchedDevice, action: matchedAction);
  }
}
