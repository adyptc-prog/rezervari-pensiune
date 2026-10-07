import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/main.dart';

// SMS-urile de sincronizare pot sosi în altă ordine decât au fost trimise.
const _smsChannel = MethodChannel('pensiune/sms');

final _updated = DateTime(2026, 10, 8, 12, 0);

Map<String, Object?> _local(String syncId, String name, {DateTime? updatedAt}) => {
      'syncId': syncId,
      'number': 1,
      'name': name,
      'description': '',
      'createdAt': '2026-10-01T10:00:00.000',
      'expiresAt': '2030-01-02T11:00:00.000',
      'validated': true,
      if (updatedAt != null) 'updatedAt': updatedAt.toIso8601String(),
    };

String _msg(String prefix, String syncId, String name, {DateTime? u}) =>
    '$prefix${jsonEncode({
          's': syncId,
          'n': name,
          'c': '2026-10-01T10:00',
          'e': '2030-01-02T11:00',
          if (u != null) 'u': u.millisecondsSinceEpoch,
        })}';

void main() {
  late List<Map<String, String>> queue;

  setUp(() {
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

  void seed({List<Map<String, Object?>> items = const [], List<Object?> deleted = const []}) {
    SharedPreferences.setMockInitialValues({
      'management_boards': jsonEncode([
        {'id': 'b1', 'name': 'Pensiune'},
      ]),
      'management_active_board': 'b1',
      'management_items_b1': jsonEncode(items),
      'management_next_number_b1': items.length + 1,
      'management_deleted_buffer_b1': jsonEncode(deleted),
    });
  }

  Future<List<dynamic>> runAndRead(WidgetTester tester) async {
    await tester.pumpWidget(const ManagementApp());
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    final prefs = await SharedPreferences.getInstance();
    return jsonDecode(prefs.getString('management_items_b1')!) as List;
  }

  testWidgets('o adăugare întârziată nu readuce rezervarea ștearsă',
      (tester) async {
    seed(deleted: [
      {
        'item': _local('sters', 'Ștearsă'),
        'deletedAt': DateTime.now().toIso8601String(),
      },
    ]);
    queue.add({'id': 'q1', 'board': 'b1', 'msg': _msg('PEN:A:', 'sters', 'Ștearsă')});
    queue.add({'id': 'q2', 'board': 'b1', 'msg': _msg('PEN:U:', 'sters', 'Ștearsă')});

    expect(await runAndRead(tester), isEmpty);
  });

  testWidgets('o modificare mai veche nu o suprascrie pe cea locală',
      (tester) async {
    seed(items: [_local('rez1', 'Nou local', updatedAt: _updated)]);
    queue.add({
      'id': 'q1',
      'board': 'b1',
      'msg': _msg('PEN:U:', 'rez1', 'Vechi',
          u: _updated.subtract(const Duration(minutes: 5))),
    });

    final items = await runAndRead(tester);
    expect(items.single['name'], 'Nou local');
  });

  testWidgets('o modificare mai nouă e aplicată și își păstrează marcajul',
      (tester) async {
    seed(items: [_local('rez1', 'Vechi local', updatedAt: _updated)]);
    final newer = _updated.add(const Duration(minutes: 5));
    queue.add({'id': 'q1', 'board': 'b1', 'msg': _msg('PEN:U:', 'rez1', 'De la partener', u: newer)});

    final items = await runAndRead(tester);
    expect(items.single['name'], 'De la partener');
    expect(DateTime.parse(items.single['updatedAt'] as String), newer);
  });

  testWidgets('mesajele fără marcaj (versiuni vechi, botul) se aplică în continuare',
      (tester) async {
    seed(items: [_local('rez1', 'Local', updatedAt: _updated)]);
    queue.add({'id': 'q1', 'board': 'b1', 'msg': _msg('PEN:U:', 'rez1', 'Fără marcaj')});

    final items = await runAndRead(tester);
    expect(items.single['name'], 'Fără marcaj');
  });

  test('marcajul circulă în formatul de sincronizare', () {
    final item = Item(
      syncId: 'x',
      number: 1,
      name: 'A',
      description: '',
      createdAt: DateTime(2026, 10, 1),
      updatedAt: _updated,
    );
    expect(Item.fromSyncJson(item.toSyncJson()).updatedAt, _updated);
    expect(Item.fromJson(item.toJson()).updatedAt, _updated);
  });
}
