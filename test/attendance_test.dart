import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/main.dart';

const _smsChannel = MethodChannel('pensiune/sms');

Map<String, Object?> _item(int number, String syncId, String expiresAt,
        {String? attendance}) =>
    {
      'syncId': syncId,
      'number': number,
      'name': 'Client $number',
      'description': '',
      'createdAt': '2026-01-01T10:00:00.000',
      'expiresAt': expiresAt,
      'warningAt': null,
      'phoneNumber': '07111111$number$number',
      'phoneNumber2': null,
      'phoneNumber3': null,
      'attendance': attendance,
    };

void main() {
  late List<Map<String, String>> queue;

  final since = DateTime.now().subtract(const Duration(days: 3));
  final ended = DateTime.now().subtract(const Duration(hours: 2));
  final beforeFeature = DateTime.now().subtract(const Duration(days: 10));

  setUp(() {
    SharedPreferences.setMockInitialValues({
      'management_boards': jsonEncode([
        {'id': 'b1', 'name': 'Tabel 1'},
      ]),
      'management_active_board': 'b1',
      'attendance_since': since.toIso8601String(),
      'management_items_b1': jsonEncode([
        _item(1, 'aaaa', ended.toIso8601String()),
        // Încheiată înainte de pornirea funcției: nu se cere confirmare.
        _item(2, 'bbbb', beforeFeature.toIso8601String()),
        _item(3, 'cccc', '2030-01-01T12:00:00.000'),
      ]),
      'management_next_number_b1': 4,
    });
    debugSimulateAndroid = true;
    queue = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_smsChannel, (call) async {
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

  Future<List<dynamic>> savedItems() async {
    final prefs = await SharedPreferences.getInstance();
    return jsonDecode(prefs.getString('management_items_b1')!) as List;
  }

  test('needsAttendance: doar încheiate, neconfirmate, după pornirea funcției',
      () {
    final now = DateTime(2030, 1, 10, 12);
    final s = DateTime(2030, 1, 1);
    Item it(DateTime? exp, {String? a}) => Item(
        syncId: 'x', number: 1, name: 'n', description: '',
        createdAt: DateTime(2029), expiresAt: exp, attendance: a);
    expect(needsAttendance(it(DateTime(2030, 1, 10, 11)), s, now: now), isTrue);
    expect(needsAttendance(it(DateTime(2030, 1, 10, 13)), s, now: now), isFalse);
    expect(needsAttendance(it(DateTime(2029, 12, 31)), s, now: now), isFalse);
    expect(needsAttendance(it(null), s, now: now), isFalse);
    expect(needsAttendance(it(DateTime(2030, 1, 10, 11), a: kCame), s, now: now),
        isFalse);
  });

  test('prezența circulă prin formatul de sincronizare', () {
    final item = Item(
        syncId: 's1', number: 1, name: 'n', description: '',
        createdAt: DateTime(2030), attendance: kNoShow);
    final j = item.toSyncJson();
    expect(j['a'], 'n');
    expect(Item.fromSyncJson(j).attendance, kNoShow);
    expect(Item.fromSyncJson({...j, 'a': 'c'}).attendance, kCame);
    expect(Item.fromSyncJson({...j}..remove('a')).attendance, isNull);
  });

  testWidgets('bannerul arată programările de confirmat, ✓ salvează prezența',
      (tester) async {
    tester.view.physicalSize = const Size(1600, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    expect(find.text('1 rezervări de confirmat'), findsOneWidget);
    await tester.tap(find.text('Confirmă'));
    await settle(tester);
    await tester.tap(find.byTooltip('A venit'));
    await settle(tester);
    expect(find.text('Toate rezervările sunt confirmate.'), findsOneWidget);
    await tester.tap(find.text('Închide'));
    await settle(tester);

    expect(find.text('1 rezervări de confirmat'), findsNothing);
    final items = await savedItems();
    expect(items.firstWhere((i) => i['syncId'] == 'aaaa')['attendance'], kCame);
    expect(items.firstWhere((i) => i['syncId'] == 'bbbb')['attendance'], isNull);
  });

  testWidgets('„Nu a venit” din rând se salvează și poate fi corectat',
      (tester) async {
    tester.view.physicalSize = const Size(1600, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    await tester.tap(find.byTooltip('Confirmă prezența'));
    await settle(tester);
    await tester.tap(find.text('Nu a venit'));
    await settle(tester);
    var items = await savedItems();
    expect(items.firstWhere((i) => i['syncId'] == 'aaaa')['attendance'], kNoShow);

    await tester.tap(find.byTooltip('Confirmă prezența'));
    await settle(tester);
    await tester.tap(find.text('A venit'));
    await settle(tester);
    items = await savedItems();
    expect(items.firstWhere((i) => i['syncId'] == 'aaaa')['attendance'], kCame);
  });

  testWidgets('prezența marcată pe telefonul partener se preia la sincronizare',
      (tester) async {
    final j = {
      's': 'aaaa',
      'n': 'Client 1',
      'c': '2026-01-01T10:00',
      'e': ended.toIso8601String().substring(0, 16),
      'p1': '0711111111',
      'a': 'n',
    };
    queue.add({'id': 'q1', 'board': 'b1', 'msg': 'PEN:U:${jsonEncode(j)}'});

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    final items = await savedItems();
    expect(items.firstWhere((i) => i['syncId'] == 'aaaa')['attendance'], kNoShow);
  });
}
