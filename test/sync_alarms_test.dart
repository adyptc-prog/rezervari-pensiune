import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/main.dart';

const _smsChannel = MethodChannel('pensiune/sms');

Map<String, Object?> _item(int number, String syncId, String phone) => {
      'syncId': syncId,
      'number': number,
      'name': 'Client $number',
      'description': '',
      'createdAt': '2026-10-01T10:00:00.000',
      'expiresAt': '2030-01-0${number}T12:00:00.000',
      'warningAt': '2030-01-0${number}T11:00:00.000',
      'phoneNumber': phone,
      'phoneNumber2': null,
      'phoneNumber3': null,
      'startsAt': null,
      'validated': false,
    };

// Același algoritm ca java.lang.String.hashCode() (vezi ValidationService).
int _javaHash(String s) {
  var h = 0;
  for (final u in s.codeUnits) {
    h = (h * 31 + u) & 0xFFFFFFFF;
  }
  return h > 0x7FFFFFFF ? h - 0x100000000 : h;
}

void main() {
  late List<MethodCall> calls;
  late List<Map<String, String>> queue;

  setUp(() {
    SharedPreferences.setMockInitialValues({
      'management_boards': jsonEncode([
        {'id': 'b1', 'name': 'Tabel 1'},
        {'id': 'b2', 'name': 'Tabel 2'},
        {'id': 'b3', 'name': 'Tabel 3'},
      ]),
      'management_active_board': 'b1',
      'management_items_b1': jsonEncode([
        _item(1, 'aaaa', '0711111111'),
        _item(2, 'bbbb', '0722222222'),
        _item(3, 'cccc', '0733333333'),
      ]),
      'management_next_number_b1': 4,
    });
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

  // Ultima operație pe alarma SMS cu acest id: 'schedule' sau 'cancel'.
  String? lastSmsOp(int id) {
    for (final c in calls.reversed) {
      if ((c.method == 'schedule' || c.method == 'cancel') &&
          (c.arguments as Map)['id'] == id) {
        return c.method;
      }
    }
    return null;
  }

  String? lastNotifOp(int id) {
    for (final c in calls.reversed) {
      if ((c.method == 'scheduleNotif' || c.method == 'cancelNotif') &&
          (c.arguments as Map)['id'] == id) {
        return c.method;
      }
    }
    return null;
  }

  testWidgets(
      'ultima înregistrare ștearsă prin sincronizare nu mai trimite SMS/notificări',
      (tester) async {
    queue.add({'id': 'q1', 'board': 'b1', 'msg': 'PEN:D:cccc'});

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    // Numărul 3: SMS avertizare 310, expirare 320; notificări 31 și 32.
    expect(lastSmsOp(310), 'cancel');
    expect(lastSmsOp(320), 'cancel');
    expect(lastNotifOp(31), 'cancelNotif');
    expect(lastNotifOp(32), 'cancelNotif');
    expect(
      calls.any((c) =>
          c.method == 'cancelValidation' &&
          (c.arguments as Map)['id'] == _javaHash('cccc')),
      isTrue,
    );

    final prefs = await SharedPreferences.getInstance();
    expect(prefs.containsKey('sms_alarm_310'), isFalse);
    expect(prefs.containsKey('sms_alarm_320'), isFalse);
  });

  testWidgets('reprogramarea nu e anulată de propria anulare (ordinea contează)',
      (tester) async {
    queue.add({'id': 'q1', 'board': 'b1', 'msg': 'PEN:D:bbbb'});

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    // „cccc” devine numărul 2: alarmele lui trebuie să rămână programate
    // sub 2, cu payload-ul salvat.
    expect(lastSmsOp(210), 'schedule');
    expect(lastSmsOp(220), 'schedule');
    final prefs = await SharedPreferences.getInstance();
    expect(jsonDecode(prefs.getString('sms_alarm_220')!)['phone'], '0733333333');
    // Numărul 3 nu mai există.
    expect(lastSmsOp(320), 'cancel');
    expect(prefs.containsKey('sms_alarm_320'), isFalse);
  });

  testWidgets('rezervarea prin bot nu primește SMS „EXPIRAT”, cea manuală da',
      (tester) async {
    String add(String syncId, {bool bot = false}) => 'PEN:A:${jsonEncode({
          's': syncId,
          'n': '+40755555555',
          'c': '2030-01-05T10:00',
          'e': '2030-01-05T10:30',
          'p1': '+40755555555',
          if (bot) 'b': true,
        })}';
    queue.add({'id': 'q1', 'board': 'b1', 'msg': add('botb', bot: true)});
    queue.add({'id': 'q2', 'board': 'b1', 'msg': add('manu')});

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    // Numerele 4 (bot) și 5 (manual): expirare = n*100+20.
    expect(lastSmsOp(420), isNot('schedule'));
    expect(lastSmsOp(520), 'schedule');
    final prefs = await SharedPreferences.getInstance();
    final items = jsonDecode(prefs.getString('management_items_b1')!) as List;
    expect(items.firstWhere((i) => i['syncId'] == 'botb')['viaBot'], isTrue);
  });

  testWidgets('template-ul SMS nou se aplică pe toate tabelele', (tester) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('management_items_b2',
        jsonEncode([_item(1, 'tab2', '0744444444')]));

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    await tester.tap(find.text('Mesaj SMS'));
    await settle(tester);
    await tester.enterText(find.widgetWithText(TextField, 'Template mesaj'),
        'Nou: [NUME]');
    await tester.tap(find.text('Salvează'));
    await settle(tester);

    // Tabelul 2 (index 1): avertizare = 10000000 + 1*100 + 10.
    final payload = jsonDecode(prefs.getString('sms_alarm_10000110')!);
    expect(payload['message'], 'Nou: Client 1');
    expect(payload['phone'], '0744444444');
    // Și tabelul activ.
    expect(jsonDecode(prefs.getString('sms_alarm_110')!)['message'], 'Nou: Client 1');
  });
}

