import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:pensiune_app/backup_screen.dart';
import 'package:pensiune_app/backup_service.dart';

const _channel = MethodChannel('pensiune/backup');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, Object?> status;
  late List<Map<String, Object?>> backups;
  late List<MethodCall> calls;
  PlatformException? failWith;
  late List<String> events;
  late bool encrypted;

  final noFolder = <String, Object?>{'folderUri': null};
  final usbFolder = <String, Object?>{
    'folderUri': 'content://usb/tree/1',
    'folderName': 'Backup Organizator',
    'folderAccessible': true,
    'destination': 'usb',
    'lastAutoAt': DateTime(2026, 9, 24, 0, 1).millisecondsSinceEpoch,
    'lastAutoError': null,
    'lastManualAt': null,
    'hasPassword': true,
  };
  // Parola cu care sunt criptate backup-urile din teste.
  const goodPassword = 'parola-buna';

  setUp(() {
    BackupService.debugIsAndroid = true;
    status = noFolder;
    backups = [];
    calls = [];
    failWith = null;
    events = [];
    encrypted = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      if (failWith != null && call.method != 'getStatus') throw failWith!;
      switch (call.method) {
        case 'getStatus':
          return status;
        case 'pickFolder':
          status = {...usbFolder, 'destination': call.arguments['destination']};
          return status;
        case 'createBackup':
          return {
            'id': 'doc1',
            'name': 'pensiune_20260924_101500.penbackup',
            'size': 2048,
            'modifiedAt': 0,
          };
        case 'listBackups':
          return backups;
        case 'restoreBackup':
        case 'pickAndRestoreBackup':
        case 'retryPickedRestore':
          // Backup criptat: fără parolă (sau cu parola salvată, simulată ca
          // absentă) cere parola; cu altă parolă — greșită.
          if (encrypted) {
            final pw = call.arguments['password'] as String?;
            if (pw == null) {
              throw PlatformException(code: 'BACKUP_PASSWORD_REQUIRED');
            }
            if (pw != goodPassword) {
              throw PlatformException(code: 'BACKUP_PASSWORD_WRONG');
            }
          }
          events.add('restore(${call.arguments['keepSyncPartners']})');
          return null;
        case 'setPassword':
          status = {...status, 'hasPassword': true};
          return status;
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
    BackupService.debugIsAndroid = null;
  });

  // FilledButton.icon / OutlinedButton.icon sunt subclase private — căutăm
  // orice buton Material care conține textul.
  bool buttonEnabled(WidgetTester tester, String label) => tester
      .widget<ButtonStyleButton>(find.ancestor(
          of: find.text(label),
          matching: find.byWidgetPredicate((w) => w is ButtonStyleButton)))
      .enabled;

  Future<void> pumpScreen(WidgetTester tester) async {
    // Ecran înalt: mesajul de rezultat stă sub butoane, în ListView.
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      home: BackupScreen(
        onBeforeRestore: () async => events.add('before'),
        onRestored: (ok) async => events.add('after($ok)'),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('fără folder: cere alegerea destinației, backup dezactivat',
      (tester) async {
    await pumpScreen(tester);
    expect(find.text('Niciun folder ales'), findsOneWidget);
    expect(buttonEnabled(tester, 'Creează backup acum'), isFalse);
  });

  testWidgets('alegerea stick-ului USB trimite destinația', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Stick USB'));
    await tester.pumpAndSettle();
    final pick = calls.firstWhere((c) => c.method == 'pickFolder');
    expect(pick.arguments['destination'], 'usb');
    expect(find.text('Backup Organizator'), findsOneWidget);
    expect(find.text('Folderul de backup a fost setat.'), findsOneWidget);
  });

  testWidgets('memoria telefonului trimite destinația phone', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Memoria telefonului'));
    await tester.pumpAndSettle();
    expect(calls.firstWhere((c) => c.method == 'pickFolder').arguments['destination'],
        'phone');
  });

  testWidgets('afișează ultimul backup automat și crearea manuală', (tester) async {
    status = usbFolder;
    await pumpScreen(tester);
    expect(find.text('Ultimul backup automat: 24.09.2026 00:01'), findsOneWidget);
    await tester.tap(find.text('Creează backup acum'));
    await tester.pumpAndSettle();
    expect(find.text('Backup creat: pensiune_20260924_101500.penbackup'),
        findsOneWidget);
  });

  testWidgets('eroarea backup-ului automat e vizibilă', (tester) async {
    status = {...usbFolder, 'lastAutoError': 'Nu s-a putut crea fișierul de backup.'};
    await pumpScreen(tester);
    expect(find.textContaining('Ultima încercare a eșuat'), findsOneWidget);
  });

  testWidgets('folder inaccesibil (stick scos): butoanele sunt dezactivate',
      (tester) async {
    status = {...usbFolder, 'folderAccessible': false, 'folderName': null};
    await pumpScreen(tester);
    expect(find.text('Folderul nu este accesibil'), findsOneWidget);
    expect(buttonEnabled(tester, 'Creează backup acum'), isFalse);
    expect(buttonEnabled(tester, 'Restaurează din folderul de backup'), isFalse);
    // Restaurarea dintr-un fișier ales manual rămâne disponibilă.
    expect(buttonEnabled(tester, 'Restaurează din alt fișier'), isTrue);
  });

  testWidgets('eroarea nativă la creare e afișată', (tester) async {
    status = usbFolder;
    await pumpScreen(tester);
    failWith = PlatformException(
        code: 'BACKUP_CREATE_FAILED',
        message: 'Aplicația nu mai are acces la folderul de backup. Alege-l din nou.');
    await tester.tap(find.text('Creează backup acum'));
    await tester.pumpAndSettle();
    expect(find.text('Aplicația nu mai are acces la folderul de backup. Alege-l din nou.'),
        findsOneWidget);
  });

  testWidgets('restaurare din folder: confirmare, parteneri păstrați implicit',
      (tester) async {
    status = usbFolder;
    backups = [
      {
        'id': 'doc9',
        'name': 'pensiune_auto_20260924_000100.penbackup',
        'modifiedAt': DateTime(2026, 9, 24, 0, 1).millisecondsSinceEpoch,
        'size': 4096,
        'auto': true,
      },
    ];
    await pumpScreen(tester);
    await tester.tap(find.text('Restaurează din folderul de backup'));
    await tester.pumpAndSettle();
    expect(find.text('Automat · 4.0 KB'), findsOneWidget);
    await tester.tap(find.text('24.09.2026 00:01'));
    await tester.pumpAndSettle();

    expect(find.text('Restaurezi backup-ul?'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Restaurează'));
    await tester.pumpAndSettle();

    expect(events, ['before', 'restore(true)', 'after(true)']);
    expect(calls.firstWhere((c) => c.method == 'restoreBackup').arguments['id'],
        'doc9');
    expect(find.text('Backup restaurat. Datele au fost reîncărcate.'),
        findsOneWidget);
  });

  testWidgets('restaurare din fișier cu partenerii din backup', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Restaurează din alt fișier'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Păstrează partenerii de sincronizare actuali'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Restaurează'));
    await tester.pumpAndSettle();
    expect(events, ['before', 'restore(false)', 'after(true)']);
  });

  testWidgets('restaurare anulată la confirmare nu atinge datele', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Restaurează din alt fișier'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Anulează'));
    await tester.pumpAndSettle();
    expect(events, isEmpty);
    expect(calls.where((c) => c.method == 'pickAndRestoreBackup'), isEmpty);
  });

  testWidgets('backup respins: mesaj de eroare, datele se reîncarcă oricum',
      (tester) async {
    await pumpScreen(tester);
    failWith = PlatformException(
        code: 'BACKUP_RESTORE_FAILED',
        message: 'Backup-ul este corupt (checksum invalid).');
    await tester.tap(find.text('Restaurează din alt fișier'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Restaurează'));
    await tester.pumpAndSettle();
    expect(events, ['before', 'after(false)']);
    expect(find.text('Backup-ul este corupt (checksum invalid).'), findsOneWidget);
  });

  testWidgets('selector de fișier închis fără alegere: fără eroare', (tester) async {
    await pumpScreen(tester);
    failWith = PlatformException(code: 'RESTORE_PICK_CANCELLED');
    await tester.tap(find.text('Restaurează din alt fișier'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Restaurează'));
    await tester.pumpAndSettle();
    expect(find.textContaining('RESTORE_PICK'), findsNothing);
    expect(find.textContaining('restaurat'), findsNothing);
  });

  testWidgets('folder fără backup-uri', (tester) async {
    status = usbFolder;
    await pumpScreen(tester);
    await tester.tap(find.text('Restaurează din folderul de backup'));
    await tester.pumpAndSettle();
    expect(find.text('Nu există backup-uri în folderul ales.'), findsOneWidget);
  });

  group('parola de backup', () {
    testWidgets('fără parolă: avertisment și backup-ul manual e dezactivat',
        (tester) async {
      status = {...usbFolder, 'hasPassword': false};
      await pumpScreen(tester);
      expect(find.text('Parola de backup nu e setată'), findsOneWidget);
      expect(buttonEnabled(tester, 'Creează backup acum'), isFalse);
    });

    testWidgets('setarea parolei verifică lungimea și confirmarea',
        (tester) async {
      status = {...usbFolder, 'hasPassword': false};
      await pumpScreen(tester);
      await tester.tap(find.text('Setează'));
      await tester.pumpAndSettle();

      await tester.enterText(find.widgetWithText(TextField, 'Parolă'), 'scurta');
      await tester.enterText(find.widgetWithText(TextField, 'Repetă parola'), 'scurta');
      await tester.tap(find.text('Salvează'));
      await tester.pumpAndSettle();
      expect(find.text('Minim 8 caractere.'), findsOneWidget);

      await tester.enterText(find.widgetWithText(TextField, 'Parolă'), 'parola-buna');
      await tester.enterText(find.widgetWithText(TextField, 'Repetă parola'), 'parola-alta');
      await tester.tap(find.text('Salvează'));
      await tester.pumpAndSettle();
      expect(find.text('Parolele nu coincid.'), findsOneWidget);
      expect(calls.where((c) => c.method == 'setPassword'), isEmpty);

      await tester.enterText(find.widgetWithText(TextField, 'Repetă parola'), 'parola-buna');
      await tester.tap(find.text('Salvează'));
      await tester.pumpAndSettle();
      expect(calls.singleWhere((c) => c.method == 'setPassword').arguments['password'],
          'parola-buna');
      expect(find.text('Backup-urile sunt criptate cu parolă'), findsOneWidget);
      expect(buttonEnabled(tester, 'Creează backup acum'), isTrue);
    });

    testWidgets('restaurare criptată: parolă greșită, apoi corectă',
        (tester) async {
      status = usbFolder;
      encrypted = true;
      backups = [
        {
          'id': 'doc9',
          'name': 'pensiune_auto_20260924_000100.penbackup',
          'modifiedAt': DateTime(2026, 9, 24, 0, 1).millisecondsSinceEpoch,
          'size': 4096,
          'auto': true,
        },
      ];
      await pumpScreen(tester);
      await tester.tap(find.text('Restaurează din folderul de backup'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('24.09.2026 00:01'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Restaurează'));
      await tester.pumpAndSettle();

      expect(find.text('Parola backup-ului'), findsOneWidget);
      await tester.enterText(find.byType(TextField), 'gresita1');
      await tester.tap(find.text('Deschide'));
      await tester.pumpAndSettle();
      expect(find.text('Parolă greșită. Încearcă din nou.'), findsOneWidget);

      await tester.enterText(find.byType(TextField), goodPassword);
      await tester.tap(find.text('Deschide'));
      await tester.pumpAndSettle();

      expect(events, ['before', 'restore(true)', 'after(true)']);
      expect(find.text('Backup restaurat. Datele au fost reîncărcate.'),
          findsOneWidget);
    });

    testWidgets('fișier ales criptat: parola se trimite fără a-l alege din nou',
        (tester) async {
      encrypted = true;
      await pumpScreen(tester);
      await tester.tap(find.text('Restaurează din alt fișier'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Restaurează'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), goodPassword);
      await tester.tap(find.text('Deschide'));
      await tester.pumpAndSettle();

      expect(calls.where((c) => c.method == 'pickAndRestoreBackup').length, 1);
      expect(calls.singleWhere((c) => c.method == 'retryPickedRestore')
          .arguments['password'], goodPassword);
      expect(events, ['before', 'restore(true)', 'after(true)']);
    });

    testWidgets('renunțarea la parolă nu afișează eroare, datele se reîncarcă',
        (tester) async {
      encrypted = true;
      await pumpScreen(tester);
      await tester.tap(find.text('Restaurează din alt fișier'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Restaurează'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Anulează'));
      await tester.pumpAndSettle();

      expect(events, ['before', 'after(false)']);
      expect(find.textContaining('restaurat'), findsNothing);
      expect(find.textContaining('PASSWORD'), findsNothing);
    });
  });
}
