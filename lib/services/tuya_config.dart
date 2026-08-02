class TuyaConfig {
  static const String clientId = String.fromEnvironment(
    'TUYA_CLIENT_ID',
    defaultValue: '',
  );
  static const String clientSecret = String.fromEnvironment(
    'TUYA_CLIENT_SECRET',
    defaultValue: '',
  );

  static const String baseUrl = 'https://openapi.tuyaeu.com';
}
