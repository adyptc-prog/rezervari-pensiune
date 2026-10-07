import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/main.dart';

const _smsChannel = MethodChannel('pensiune/sms');

Map<String, Object?> _item(
  int number,
  String syncId, {
  String name = 'Client',
  String? phone = '0711111111',
  required DateTime createdAt,
  required DateTime expiresAt,
  bool validated = false,
}) =>
    {
      'syncId': syncId,
      'number': number,
      'name': name,
      'description': '',
      'createdAt': createdAt.toIso8601String(),
      'expiresAt': expiresAt.toIso8601String(),
      'warningAt': null,
      'phoneNumber': phone,
      'phoneNumber2': null,
      'phoneNumber3': null,
      'startsAt': null,
      'validated': validated,
    };

void main() {
  late List<MethodCall> calls;
  late List<Map<String, String>> queue;

  void setUpPrefs(List<Map<String, Object?>> items) {
    SharedPreferences.setMockInitialValues({
      'management_boards': jsonEncode([
        {'id': 'b1', 'name': 'Pensiune'},
      ]),
      'management_active_board': 'b1',
      'management_items_b1': jsonEncode(items),
      'management_next_number_b1': items.length + 1,
    });
  }

  setUp(() {
    debugSimulateAndroid = true;
    calls = [];
    queue = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_smsChannel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getSyncMessages':
          return jsonEncode(queue);
        case 'ackSyncMessages':
          final ids = (call.arguments['ids'] as List).cast<String>();
          queue.removeWhere((e) => ids.contains(e['id']));
          return null;
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_smsChannel, null);
    debugSimulateAndroid = null;
  });

  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Future<void> pumpApp(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1600, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(const ManagementApp());
    await settle(tester);
  }

  Iterable<MethodCall> validationSchedules() =>
      calls.where((c) => c.method == 'scheduleValidation');

  final now = DateTime.now();
  final old = now.subtract(const Duration(days: 3));
  final future = now.add(const Duration(days: 5));
  final past = now.subtract(const Duration(days: 1));

  group('termenul de plată de 24h', () {
    testWidgets('nu se reprogramează pentru rezervări mai vechi de 24h',
        (tester) async {
      setUpPrefs([
        _item(1, 'vechi', createdAt: old, expiresAt: future),
      ]);
      // Orice mesaj de sincronizare reprograma termenul pentru tot tabelul.
      queue.add({
        'id': 'q1',
        'board': 'b1',
        'msg': 'PEN:A:${jsonEncode({
              's': 'nou1',
              'n': 'Nou',
              'c': now.toIso8601String().substring(0, 16),
              'e': future.toIso8601String().substring(0, 16),
            })}',
      });

      await pumpApp(tester);

      final ids = validationSchedules()
          .map((c) => (c.arguments as Map)['id'])
          .toList();
      // Doar rezervarea nouă primește termen; cea veche nu (s-ar fi
      // declanșat imediat și ar fi șters-o).
      expect(ids, hasLength(1));
      final prefs = await SharedPreferences.getInstance();
      expect(
          prefs.getKeys().where((k) => k.startsWith('validation_alarm_')),
          hasLength(1));
    });

    testWidgets('debifarea „validat” cere confirmare', (tester) async {
      setUpPrefs([
        _item(1, 'plat', createdAt: old, expiresAt: future, validated: true),
      ]);
      await pumpApp(tester);

      await tester.tap(find.byTooltip('Anulează validarea'));
      await settle(tester);
      expect(find.text('Anulezi validarea?'), findsOneWidget);
      await tester.tap(find.text('Renunță'));
      await settle(tester);

      final prefs = await SharedPreferences.getInstance();
      final items = jsonDecode(prefs.getString('management_items_b1')!) as List;
      expect(items.single['validated'], isTrue);

      await tester.tap(find.byTooltip('Anulează validarea'));
      await settle(tester);
      await tester.tap(find.widgetWithText(FilledButton, 'Anulează validarea'));
      await settle(tester);
      final after = jsonDecode(prefs.getString('management_items_b1')!) as List;
      expect(after.single['validated'], isFalse);
      // Termenul (createdAt + 24h) a trecut — nu se reprogramează.
      expect(validationSchedules(), isEmpty);
    });
  });

  group('ștergerea unei rezervări', () {
    Future<void> deleteFirst(WidgetTester tester) async {
      await tester.tap(find.byTooltip('Șterge').first);
      await settle(tester);
      await tester.tap(find.widgetWithText(FilledButton, 'Șterge'));
      await settle(tester);
    }

    Iterable<MethodCall> smsSent() => calls.where((c) => c.method == 'sendSms');

    testWidgets('sejur încheiat: clientul nu primește „anulată”',
        (tester) async {
      setUpPrefs([_item(1, 'trecut', createdAt: old, expiresAt: past)]);
      await pumpApp(tester);
      await deleteFirst(tester);
      expect(smsSent(), isEmpty);
    });

    testWidgets('sejur viitor: clientul e anunțat', (tester) async {
      setUpPrefs([_item(1, 'viitor', createdAt: old, expiresAt: future)]);
      await pumpApp(tester);
      await deleteFirst(tester);
      expect(smsSent(), hasLength(1));
      expect((smsSent().single.arguments as Map)['message'],
          contains('anulată de proprietar'));
    });
  });

  group('date demonstrative', () {
    test('sunt recunoscute după nume și data creării', () {
      Item demo(String name, DateTime created, {String? phone}) => Item(
            syncId: 'x',
            number: 1,
            name: name,
            description: '',
            createdAt: created,
            phoneNumber: phone,
          );
      expect(isDemoItem(demo('Buget anual', DateTime(2026, 5, 20, 14))), isTrue);
      expect(isDemoItem(demo('Buget anual', DateTime(2026, 5, 21, 14))), isFalse);
      expect(
          isDemoItem(demo('Buget anual', DateTime(2026, 5, 20, 14),
              phone: '0711111111')),
          isFalse);
      expect(isDemoItem(demo('Ion Popescu', DateTime(2026, 5, 20, 14))), isFalse);
    });

    testWidgets('prima instalare pornește cu tabelul gol', (tester) async {
      SharedPreferences.setMockInitialValues({});
      await pumpApp(tester);
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString('management_items_b1');
      expect(raw == null || (jsonDecode(raw) as List).isEmpty, isTrue);
      expect(find.text('Proiect Alpha'), findsNothing);
    });

    testWidgets('cele rămase din versiunile vechi sunt șterse, restul renumerotat',
        (tester) async {
      setUpPrefs([
        {
          ..._item(1, 'demo1',
              name: 'Întâlnire echipă',
              phone: null,
              createdAt: DateTime(2026, 3, 15, 10),
              expiresAt: DateTime(2026, 12, 31, 17)),
          'description': 'Ședință săptămânală de status',
        },
        _item(2, 'real', name: 'Ion', createdAt: old, expiresAt: future),
      ]);
      await pumpApp(tester);

      final prefs = await SharedPreferences.getInstance();
      final items = jsonDecode(prefs.getString('management_items_b1')!) as List;
      expect(items.map((i) => i['syncId']), ['real']);
      expect(items.single['number'], 1);
      expect(prefs.getInt('management_next_number_b1'), 2);
    });
  });
}
