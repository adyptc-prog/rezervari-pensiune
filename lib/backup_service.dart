import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

// Stare backup, așa cum o raportează partea nativă (BackupManager.status).
class BackupStatus {
  final String? folderName;
  final bool folderAccessible;
  final bool hasFolder;
  final String? destination; // 'usb' | 'phone'
  final DateTime? lastAutoAt;
  final String? lastAutoError;
  final DateTime? lastManualAt;
  // Backup-urile sunt criptate cu parola de backup a telefonului — fără ea
  // nu se poate crea niciun backup.
  final bool hasPassword;

  const BackupStatus({
    this.folderName,
    this.folderAccessible = false,
    this.hasFolder = false,
    this.destination,
    this.lastAutoAt,
    this.lastAutoError,
    this.lastManualAt,
    this.hasPassword = false,
  });

  static DateTime? _date(Object? ms) =>
      ms is int ? DateTime.fromMillisecondsSinceEpoch(ms) : null;

  factory BackupStatus.fromMap(Map<Object?, Object?>? m) => BackupStatus(
        folderName: m?['folderName'] as String?,
        folderAccessible: (m?['folderAccessible'] as bool?) ?? false,
        hasFolder: m?['folderUri'] != null,
        destination: m?['destination'] as String?,
        lastAutoAt: _date(m?['lastAutoAt']),
        lastAutoError: m?['lastAutoError'] as String?,
        lastManualAt: _date(m?['lastManualAt']),
        hasPassword: (m?['hasPassword'] as bool?) ?? false,
      );
}

class BackupEntry {
  final String id;
  final String name;
  final DateTime modifiedAt;
  final int size;
  final bool auto;

  const BackupEntry({
    required this.id,
    required this.name,
    required this.modifiedAt,
    required this.size,
    required this.auto,
  });

  factory BackupEntry.fromMap(Map<Object?, Object?> m) => BackupEntry(
        id: m['id'] as String? ?? '',
        name: m['name'] as String? ?? '',
        modifiedAt: DateTime.fromMillisecondsSinceEpoch((m['modifiedAt'] as int?) ?? 0),
        size: (m['size'] as int?) ?? 0,
        auto: (m['auto'] as bool?) ?? false,
      );
}

// Utilizatorul a închis selectorul fără să aleagă nimic.
class BackupCancelled implements Exception {
  const BackupCancelled();
}

// Backup-ul e criptat: parola lipsește (wrong = false) sau e greșită.
class BackupPasswordNeeded implements Exception {
  final bool wrong;
  const BackupPasswordNeeded({required this.wrong});
}

// ─── Serviciu backup ──────────────────────────────────────────────────────────
// Backup-ul (manual + automat zilnic) și restaurarea sunt implementate nativ
// (BackupManager.kt), ca backup-ul automat să ruleze și cu aplicația închisă.
class BackupService {
  static const _ch = MethodChannel('pensiune/backup');

  @visibleForTesting
  static bool? debugIsAndroid;
  static bool get isSupported => debugIsAndroid ?? Platform.isAndroid;

  static Future<T?> _call<T>(String method, [Map<String, Object?>? args]) async {
    try {
      return await _ch.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      if (e.code.endsWith('_CANCELLED')) throw const BackupCancelled();
      if (e.code == 'BACKUP_PASSWORD_REQUIRED') {
        throw const BackupPasswordNeeded(wrong: false);
      }
      if (e.code == 'BACKUP_PASSWORD_WRONG') {
        throw const BackupPasswordNeeded(wrong: true);
      }
      throw Exception(e.message ?? e.code);
    }
  }

  static Future<BackupStatus> getStatus() async {
    if (!isSupported) return const BackupStatus();
    return BackupStatus.fromMap(await _call<Map<Object?, Object?>>('getStatus'));
  }

  // destination: 'usb' sau 'phone' — un singur folder activ.
  static Future<BackupStatus> pickFolder(String destination) async =>
      BackupStatus.fromMap(await _call<Map<Object?, Object?>>(
          'pickFolder', {'destination': destination}));

  static Future<BackupEntry> createBackup() async =>
      BackupEntry.fromMap((await _call<Map<Object?, Object?>>('createBackup'))!);

  static Future<List<BackupEntry>> listBackups() async {
    final r = await _call<List<Object?>>('listBackups') ?? const [];
    return r.whereType<Map<Object?, Object?>>().map(BackupEntry.fromMap).toList();
  }

  static const minPasswordLength = 8;

  static Future<BackupStatus> setPassword(String password) async =>
      BackupStatus.fromMap(await _call<Map<Object?, Object?>>(
          'setPassword', {'password': password}));

  static Future<void> restoreBackup(String id,
          {required bool keepSyncPartners, String? password}) =>
      _call<void>('restoreBackup', {
        'id': id,
        'keepSyncPartners': keepSyncPartners,
        'password': ?password,
      });

  static Future<void> pickAndRestoreBackup({required bool keepSyncPartners}) =>
      _call<void>('pickAndRestoreBackup', {'keepSyncPartners': keepSyncPartners});

  // Același fișier ales la pickAndRestoreBackup, de data asta cu parola.
  static Future<void> retryPickedRestore(
          {required bool keepSyncPartners, required String password}) =>
      _call<void>('retryPickedRestore',
          {'keepSyncPartners': keepSyncPartners, 'password': password});
}
