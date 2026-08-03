enum DeviceType { light, fan, airConditioner }

class TuyaDevice {
  final DeviceType type;
  final String deviceId;

  const TuyaDevice({
    required this.type,
    required this.deviceId,
  });
}

class DeviceRegistry {
  static const Map<DeviceType, TuyaDevice> devices = {
    DeviceType.light: TuyaDevice(
      type: DeviceType.light,
      deviceId: '',
    ),
    DeviceType.fan: TuyaDevice(
      type: DeviceType.fan,
      deviceId: '',
    ),
    DeviceType.airConditioner: TuyaDevice(
      type: DeviceType.airConditioner,
      deviceId: '',
    ),
  };
}
