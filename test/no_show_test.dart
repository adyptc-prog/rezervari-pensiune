import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/main.dart';
import 'package:pensiune_app/no_show.dart';

const _smsChannel = MethodChannel('pensiune/sms');

NoShowSource _src(String id, String? phone, DateTime at,
        {bool noShow = true, String name = 'Ana'}) =>
    (syncId: id, phone: phone, name: name, at: at, noShow: noShow);

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
  group('logica neprezentărilor', () {
    final now = DateTime(2030, 6, 1, 12);

    test('clientKey: același client indiferent de format', () {
      expect(clientKey('+40712345678'), '712345678');
      expect(clientKey('0712345678'), '712345678');
      expect(clientKey('0712 345 678'), '712345678');
      expect(clientKey('123'), '');
      expect(clientKey(null), '');
    });

    test('telefonul clientului poate fi scris la nume sau în descriere', () {
      expect(findPhoneInText('0712345678'), '0712345678');
      expect(findPhoneInText('Ana +40 712 345 678'), '+40 712 345 678');
      expect(findPhoneInText('Ana, tuns 2025'), isNull);
      expect(noShowClientPhone(null, '0712345678', ''), '0712345678');
      expect(noShowClientPhone('', 'Ana', 'tel 0712.345.678'), '0712.345.678');
      expect(noShowClientPhone('0799999999', '0712345678', ''), '0799999999');
      expect(noShowClientPhone(null, 'Ana', ''), isNull);
    });

    test('numără doar neprezentările din ultimele 6 luni', () {
      final r = computeNoShows([
        _src('a', '0712345678', DateTime(2030, 5, 1)),
        _src('b', '+40712345678', DateTime(2030, 3, 1)),
        _src('c', '0712345678', DateTime(2029, 11, 1)), // > 6 luni
        _src('d', '0712345678', DateTime(2030, 5, 2), noShow: false),
        _src('a', '0712345678', DateTime(2030, 5, 1)), // dublură
        _src('e', null, DateTime(2030, 5, 1)),
      ], {}, now: now);
      expect(r.keys, ['712345678']);
      expect(r['712345678']!.count, 2);
      expect(r['712345678']!.last, DateTime(2030, 5, 1));
    });

    test('iertarea șterge neprezentările de dinainte, nu și pe cele noi', () {
      final r = computeNoShows([
        _src('a', '0712345678', DateTime(2030, 3, 1)),
        _src('b', '0712345678', DateTime(2030, 5, 1)),
      ], {'712345678': DateTime(2030, 4, 1)}, now: now);
      expect(r['712345678']!.count, 1);
    });

    test('mesajul de iertare se codifică și se decodifică', () {
      final msg = forgiveMessage('712345678', DateTime(2030, 4, 1, 9, 30));
      expect(msg, 'PEN:F:712345678|2030-04-01T09:30');
      expect(parseForgiveMessage(msg), ('712345678', DateTime(2030, 4, 1, 9, 30)));
      expect(parseForgiveMessage('PEN:F:abc|2030-04-01T09:30'), isNull);
      expect(parseForgiveMessage('PEN:D:x'), isNull);
    });
  });

  group('în aplicație', () {
    late List<MethodCall> calls;
    late List<Map<String, String>> queue;
    final recent = DateTime.now().subtract(const Duration(days: 2));

    setUp(() {
      SharedPreferences.setMockInitialValues({
        'management_boards': jsonEncode([
          {'id': 'b1', 'name': 'Tabel 1'},
          {'id': 'b2', 'name': 'Tabel 2'},
        ]),
        'management_active_board': 'b1',
        'attendance_since': DateTime.now()
            .subtract(const Duration(days: 30))
            .toIso8601String(),
        'management_items_b1': jsonEncode([
          _item(1, 'aaaa', '0712345678', recent, attendance: kNoShow),
          _item(2, 'bbbb', '0799999999', DateTime(2030, 1, 1)),
        ]),
        // Același client, pe alt tabel, cu alt format de număr.
        'management_items_b2': jsonEncode([
          _item(1, 'cccc', '+40712345678', recent, attendance: kNoShow),
        ]),
        'management_next_number_b1': 3,
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
          case 'sendSync':
            return true;
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

    Future<Map<String, dynamic>> summary() async {
      final prefs = await SharedPreferences.getInstance();
      return jsonDecode(prefs.getString(kNoShowSummaryKey)!)
          as Map<String, dynamic>;
    }

    testWidgets('bilele adună neprezentările din toate tabelele', (tester) async {
      tester.view.physicalSize = const Size(1600, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      expect(find.byTooltip('2 neprezentări'), findsOneWidget);
      expect((await summary())['712345678'], hasLength(2));
    });

    testWidgets('programarea manuală cu telefonul scris la nume primește bilă',
        (tester) async {
      tester.view.physicalSize = const Size(1600, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final prefs = await SharedPreferences.getInstance();
      final items = jsonDecode(prefs.getString('management_items_b1')!) as List;
      items.add({
        ..._item(3, 'dddd', '', recent, attendance: kNoShow),
        'name': '0733 333 333',
        'phoneNumber': null,
      });
      await prefs.setString('management_items_b1', jsonEncode(items));

      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      expect(find.byTooltip('1 neprezentare'), findsOneWidget);
      expect((await summary())['733333333'], hasLength(1));
    });

    testWidgets('iertarea golește bilele și se trimite partenerului',
        (tester) async {
      tester.view.physicalSize = const Size(1600, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      await tester.tap(find.byTooltip('Clienți cu neprezentări'));
      await settle(tester);
      await tester.tap(find.text('Iartă').first);
      await settle(tester);
      await tester.tap(find.widgetWithText(FilledButton, 'Iartă'));
      await settle(tester);

      expect(find.text('Niciun client cu neprezentări în ultimele 6 luni.'),
          findsOneWidget);
      expect(await summary(), isEmpty);
      final sent = calls
          .where((c) => c.method == 'sendSync')
          .map((c) => (c.arguments as Map)['message'] as String)
          .where((m) => m.startsWith('PEN:F:712345678|'));
      // Câte unul pe fiecare tabel (cele 2 din test sunt completate la 10).
      expect(sent, hasLength(kBoardCount));
    });

    testWidgets('iertarea primită de la partener se aplică', (tester) async {
      queue.add({
        'id': 'q1',
        'board': 'b1',
        'msg': forgiveMessage('712345678', DateTime.now()),
        'origin': 'partner',
      });

      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      expect(await summary(), isEmpty);
      expect(find.byTooltip('Clienți cu neprezentări'), findsNothing);
    });

    testWidgets('dialogul de alertă avertizează la un telefon cu neprezentări',
        (tester) async {
      tester.view.physicalSize = const Size(1600, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      // Rândul 2 (viitor) are butonul de alertă.
      await tester.tap(find.byTooltip('Setează alertă & SMS').first);
      await settle(tester);
      await tester.enterText(
          find.widgetWithText(TextField, 'Telefon 1 (opțional)'), '0712 345 678');
      await settle(tester);
      expect(find.text('Clientul are 2 neprezentări în ultimele 6 luni.'),
          findsOneWidget);
    });
  });
}
