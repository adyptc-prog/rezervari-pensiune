import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/main.dart';
import 'package:pensiune_app/no_show.dart';

const _smsChannel = MethodChannel('pensiune/sms');

Map<String, Object?> _item(int number, String syncId, String phone,
        DateTime expiresAt, {String? attendance}) =>
    {
      'syncId': syncId,
      'number': number,
      'name': 'Client $number',
      'description': '',
      'createdAt': '2026-01-01T10:00:00.000',
      'expiresAt': expiresAt.toIso8601String(),
      'warningAt': null,
      'phoneNumber': phone,
      'phoneNumber2': null,
      'phoneNumber3': null,
      'attendance': attendance,
    };

void main() {
  test('textul SMS-ului de neprezentare', () {
    final at = DateTime(2030, 1, 5, 9, 30);
    expect(noShowSmsText(at, 3, 1),
        'Nu te-ai prezentat la rezervarea din 05.01.2030. '
        'La 3 neprezentări nu mai poți rezerva prin SMS.');
    expect(noShowSmsText(at, 3, 3), contains('te rugăm să suni la pensiune'));
    expect(noShowSmsText(at, 0, 5),
        'Nu te-ai prezentat la rezervarea din 05.01.2030.');
  });

  late List<MethodCall> calls;
  late List<Map<String, String>> queue;
  final longAgo = DateTime.now().subtract(const Duration(hours: 25));
  final recently = DateTime.now().subtract(const Duration(hours: 2));

  void setUpPrefs({bool sms = false}) {
    SharedPreferences.setMockInitialValues({
      'management_boards': jsonEncode([
        {'id': 'b1', 'name': 'Tabel 1'},
        {'id': 'b2', 'name': 'Tabel 2'},
      ]),
      'management_active_board': 'b1',
      'attendance_since':
          DateTime.now().subtract(const Duration(days: 30)).toIso8601String(),
      'management_items_b1': jsonEncode([
        _item(1, 'old1', '0711111111', longAgo),
        _item(2, 'new2', '0722222222', recently),
      ]),
      'management_items_b2': jsonEncode([
        _item(1, 'old3', '0733333333', longAgo),
      ]),
      'management_next_number_b1': 3,
      if (sms) kNoShowSmsKey: true,
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

  Future<List<dynamic>> saved(String board) async {
    final prefs = await SharedPreferences.getInstance();
    return jsonDecode(prefs.getString('management_items_$board')!) as List;
  }

  List<String> smsTo() => [
        for (final c in calls)
          if (c.method == 'sendSms') (c.arguments as Map)['phone'] as String,
      ];

  testWidgets('neconfirmatele de peste 24h devin neprezentări pe toate tabelele',
      (tester) async {
    setUpPrefs();
    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    final b1 = await saved('b1');
    final old1 = b1.firstWhere((i) => i['syncId'] == 'old1');
    expect(old1['attendance'], kNoShow);
    expect(old1['attendanceAuto'], isTrue);
    expect(b1.firstWhere((i) => i['syncId'] == 'new2')['attendance'], isNull);
    expect((await saved('b2')).first['attendance'], kNoShow);

    final prefs = await SharedPreferences.getInstance();
    final summary = jsonDecode(prefs.getString(kNoShowSummaryKey)!) as Map;
    expect(summary.keys, containsAll(['711111111', '733333333']));
    // Cea de acum 2 ore e încă „de confirmat” — trimisă botului ca neconfirmată.
    final pending = jsonDecode(prefs.getString(kNoShowPendingKey)!) as Map;
    expect(pending.keys, ['722222222']);
    // Fără opțiunea de SMS, clienții nu sunt anunțați.
    expect(smsTo(), isEmpty);
  });

  testWidgets('cu opțiunea activă, clientul e anunțat o singură dată',
      (tester) async {
    setUpPrefs(sms: true);
    await tester.pumpWidget(const ManagementApp());
    await settle(tester);
    expect(smsTo(), unorderedEquals(['0711111111', '0733333333']));

    calls.clear();
    await tester.pumpWidget(const ManagementApp(key: ValueKey('again')));
    await settle(tester);
    expect(smsTo(), isEmpty);
  });

  testWidgets('neprezentarea marcată pe partener e anunțată de acest telefon',
      (tester) async {
    setUpPrefs(sms: true);
    final prefs = await SharedPreferences.getInstance();
    // Doar programarea recentă, ca să nu intervină marcarea automată.
    await prefs.setString('management_items_b1',
        jsonEncode([_item(1, 'new2', '0722222222', recently)]));
    await prefs.remove('management_items_b2');
    queue.add({
      'id': 'q1',
      'board': 'b1',
      'origin': 'partner',
      'msg': 'PEN:U:${jsonEncode({
            's': 'new2',
            'n': 'Client 1',
            'c': '2026-01-01T10:00',
            'e': recently.toIso8601String().substring(0, 16),
            'p1': '0722222222',
            'a': 'n',
          })}',
    });

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    expect(smsTo(), ['0722222222']);
  });
}
