import 'device_registry.dart';
import 'tuya_service.dart';
import 'voice_command_mapper.dart';

class VoiceCommandResult {
  final bool success;
  final String message;

  const VoiceCommandResult({required this.success, required this.message});
}

class VoiceCommandExecutor {
  final TuyaService _tuyaService = TuyaService();

  Future<VoiceCommandResult> execute(String recognizedText) async {
    final command = VoiceCommandMapper.parse(recognizedText);

    if (command == null) {
      return const VoiceCommandResult(
        success: false,
        message: 'ما فهمت الأمر، عيد تاني',
      );
    }

    final device = DeviceRegistry.devices[command.device];
    if (device == null || device.deviceId.isEmpty) {
      return const VoiceCommandResult(
        success: false,
        message: 'الجهاز دا ما متوصل بعد',
      );
    }

    try {
      final bool ok;
      if (command.action == ActionType.turnOn) {
        ok = await _tuyaService.turnOn(device.deviceId);
      } else {
        ok = await _tuyaService.turnOff(device.deviceId);
      }

      if (ok) {
        final actionWord = command.action == ActionType.turnOn ? 'ولّعت' : 'قتلت';
        return VoiceCommandResult(success: true, message: '$actionWord زين');
      } else {
        return const VoiceCommandResult(
          success: false,
          message: 'ما قدرت، جرب تاني',
        );
      }
    } catch (e) {
      return const VoiceCommandResult(
        success: false,
        message: 'فيه مشكلة في الاتصال',
      );
    }
  }
}
