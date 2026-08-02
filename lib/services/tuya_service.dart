import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'tuya_config.dart';

class TuyaService {
  String? _accessToken;
  DateTime? _tokenExpiry;

  String _sha256Hex(String input) {
    return sha256.convert(utf8.encode(input)).toString();
  }

  String _hmacSha256(String key, String message) {
    final hmac = Hmac(sha256, utf8.encode(key));
    final digest = hmac.convert(utf8.encode(message));
    return digest.toString().toUpperCase();
  }

  String _buildSign({
    required String method,
    required String path,
    required String body,
    required String timestamp,
    String? accessToken,
  }) {
    final contentHash = _sha256Hex(body);
    final stringToSign = '$method\n$contentHash\n\n$path';

    String str;
    if (accessToken == null) {
      str = '${TuyaConfig.clientId}$timestamp$stringToSign';
    } else {
      str = '${TuyaConfig.clientId}$accessToken$timestamp$stringToSign';
    }
    return _hmacSha256(TuyaConfig.clientSecret, str);
  }

  Future<String> _getAccessToken() async {
    if (_accessToken != null &&
        _tokenExpiry != null &&
        DateTime.now().isBefore(_tokenExpiry!)) {
      return _accessToken!;
    }

    const path = '/v1.0/token?grant_type=1';
    final timestamp = DateTime.now().millisecondsSinceEpoch.toString();
    final sign = _buildSign(
      method: 'GET',
      path: path,
      body: '',
      timestamp: timestamp,
    );

    final response = await http.get(
      Uri.parse('${TuyaConfig.baseUrl}$path'),
      headers: {
        'client_id': TuyaConfig.clientId,
        'sign': sign,
        't': timestamp,
        'sign_method': 'HMAC-SHA256',
      },
    );

    final data = jsonDecode(response.body);

    if (data['success'] != true) {
      throw Exception('Erreur token Tuya: ${data['msg'] ?? response.body}');
    }

    _accessToken = data['result']['access_token'];
    final expireSeconds = data['result']['expire_time'] as int;
    _tokenExpiry = DateTime.now().add(Duration(seconds: expireSeconds - 60));

    return _accessToken!;
  }

  Future<bool> sendCommand({
    required String deviceId,
    required String code,
    required dynamic value,
  }) async {
    final token = await _getAccessToken();
    final path = '/v1.0/iot-03/devices/$deviceId/commands';
    final timestamp = DateTime.now().millisecondsSinceEpoch.toString();

    final bodyMap = {
      'commands': [
        {'code': code, 'value': value}
      ]
    };
    final body = jsonEncode(bodyMap);

    final sign = _buildSign(
      method: 'POST',
      path: path,
      body: body,
      timestamp: timestamp,
      accessToken: token,
    );

    final response = await http.post(
      Uri.parse('${TuyaConfig.baseUrl}$path'),
      headers: {
        'client_id': TuyaConfig.clientId,
        'access_token': token,
        'sign': sign,
        't': timestamp,
        'sign_method': 'HMAC-SHA256',
        'Content-Type': 'application/json',
      },
      body: body,
    );

    final data = jsonDecode(response.body);
    return data['success'] == true;
  }

  Future<bool> turnOn(String deviceId, {String code = 'switch_1'}) {
    return sendCommand(deviceId: deviceId, code: code, value: true);
  }

  Future<bool> turnOff(String deviceId, {String code = 'switch_1'}) {
    return sendCommand(deviceId: deviceId, code: code, value: false);
  }

  Future<List<dynamic>> getDeviceStatus(String deviceId) async {
    final token = await _getAccessToken();
    final path = '/v1.0/iot-03/devices/$deviceId/status';
    final timestamp = DateTime.now().millisecondsSinceEpoch.toString();

    final sign = _buildSign(
      method: 'GET',
      path: path,
      body: '',
      timestamp: timestamp,
      accessToken: token,
    );

    final response = await http.get(
      Uri.parse('${TuyaConfig.baseUrl}$path'),
      headers: {
        'client_id': TuyaConfig.clientId,
        'access_token': token,
        'sign': sign,
        't': timestamp,
        'sign_method': 'HMAC-SHA256',
      },
    );

    final data = jsonDecode(response.body);
    if (data['success'] != true) {
      throw Exception('Erreur status Tuya: ${data['msg'] ?? response.body}');
    }
    return data['result'];
  }
}
