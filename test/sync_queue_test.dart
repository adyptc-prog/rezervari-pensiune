import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/main.dart';

const _smsChannel = MethodChannel('pensiune/sms');

// Simulează coada nativă (SmsSyncReceiver): intrări cu id, confirmate
// individual prin ackSyncMessages.
class _FakeNativeQueue {
  final entries = <Map<String, Object>>[];
  final acked = <List<String>>[];
  var reads = 0;
  var nextId = 0;
  Duration readDelay = Duration.zero;
  // Rulează imediat după ce Flutter a citit coada — ca un SMS sosit chiar
  // în timpul procesării.
  void Function()? afterRead;

  final sent = <Map<String, String>>[];

  void add(String board, String msg, {String? origin, bool forwarded = false}) =>
      entries.add({
        'id': 'e${nextId++}',
        'board': board,
        'msg': msg,
        'origin': ?origin,
        if (forwarded) 'forwarded': true,
      });

  Future<Object?> handle(MethodCall call) async {
    switch (call.method) {
      case 'getSyncMessages':
        reads++;
        final snapshot = jsonEncode(entries);
        await Future<void>.delayed(readDelay);
        afterRead?.call();
        afterRead = null;
        return snapshot;
      case 'sendSync':
        sent.add({
          'board': call.arguments['boardId'] as String,
          'message': call.arguments['message'] as String,
        });
        return true;
      case 'ackSyncMessages':
        final ids = (call.arguments['ids'] as List).cast<String>();
        acked.add(ids);
        entries.removeWhere((e) => ids.contains(e['id']));
        return null;
    }
    return null;
  }
}

String _add(String syncId, String name) => 'PEN:A:${jsonEncode({
      's': syncId,
      'n': name,
      'c': '2026-10-01T10:00',
    })}';

void main() {
  late _FakeNativeQueue queue;

  setUp(() {
    SharedPreferences.setMockInitialValues({
      'management_boards': jsonEncode([
        {'id': 'b1', 'name': 'Tabel 1'},
        {'id': 'b2', 'name': 'Tabel 2'},
        {'id': 'b3', 'name': 'Tabel 3'},
      ]),
      'management_active_board': 'b1',
      'management_items_b1': '[]',
      'management_items_b2': '[]',
      'sync_partner_phone_b1': '0744444444',
      'sync_partner_phone_b2': '0755555555',
    });
    debugSimulateAndroid = true;
    queue = _FakeNativeQueue();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_smsChannel, queue.handle);
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

  testWidgets('un mesaj sosit în timpul procesării rămâne în coadă',
      (tester) async {
    queue.add('b1', _add('aaaa', 'Rezervare A'));
    queue.afterRead = () => queue.add('b1', _add('bbbb', 'Rezervare B'));

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    expect(find.text('Rezervare A'), findsOneWidget);
    // Doar intrarea citită a fost confirmată; cea sosită după rămâne.
    expect(queue.acked, [
      ['e0']
    ]);
    expect(queue.entries.map((e) => e['id']), ['e1']);
  });

  testWidgets('mesajul rămas e aplicat la următoarea procesare',
      (tester) async {
    queue.add('b1', _add('aaaa', 'Rezervare A'));
    queue.afterRead = () => queue.add('b1', _add('bbbb', 'Rezervare B'));

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await settle(tester);

    expect(find.text('Rezervare A'), findsOneWidget);
    expect(find.text('Rezervare B'), findsOneWidget);
    expect(queue.entries, isEmpty);
  });

  testWidgets('două procesări nu rulează simultan', (tester) async {
    queue.add('b1', _add('aaaa', 'Rezervare A'));
    queue.readDelay = const Duration(milliseconds: 500);

    await tester.pumpWidget(const ManagementApp());
    await tester.pump(const Duration(milliseconds: 100));
    // Revenirea în aplicație cât timp prima procesare încă rulează.
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump(const Duration(milliseconds: 100));
    expect(queue.reads, 1);

    await settle(tester);
    expect(find.text('Rezervare A'), findsOneWidget);
  });

  testWidgets('rezervarea făcută de bot e trimisă și partenerului tabelului',
      (tester) async {
    queue.add('b2', _add('aaaa', 'Rezervare bot'), origin: 'local');
    queue.add('b2', 'PEN:D:zzzz', origin: 'local');

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    expect(queue.sent, [
      {'board': 'b2', 'message': _add('aaaa', 'Rezervare bot')},
      {'board': 'b2', 'message': 'PEN:D:zzzz'},
    ]);
  });

  testWidgets('mesajele venite de la partener nu sunt retrimise',
      (tester) async {
    queue.add('b1', _add('aaaa', 'De la partener'), origin: 'partner');
    // Intrare scrisă de o versiune veche (fără origin) — tot de la partener.
    queue.add('b1', _add('bbbb', 'Veche'));

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    expect(find.text('De la partener'), findsOneWidget);
    expect(queue.sent, isEmpty);
  });

  testWidgets('schimbarea trimisă deja nativ partenerului nu e retrimisă',
      (tester) async {
    queue.add('b2', _add('aaaa', 'Trimisă nativ'), origin: 'local', forwarded: true);
    queue.add('b2', _add('bbbb', 'Netrimisă'), origin: 'local');

    await tester.pumpWidget(const ManagementApp());
    await settle(tester);

    expect(queue.sent, [
      {'board': 'b2', 'message': _add('bbbb', 'Netrimisă')},
    ]);
    // Ambele sunt totuși aplicate și confirmate.
    expect(queue.entries, isEmpty);
  });
}
