import 'package:flutter_test/flutter_test.dart';

import 'package:pensiune_app/main.dart';

void main() {
  test('instalarea cu 3 tabele primește încă 7, fără să le schimbe pe cele vechi', () {
    const old = [
      Board(id: 'b1', name: 'Unghii'),
      Board(id: 'b2', name: 'Păr'),
      Board(id: 'b3', name: 'Tabel 3'),
    ];
    final r = completeBoards(old)!;
    expect(r.length, kBoardCount);
    expect(r.take(3).map((b) => b.name), ['Unghii', 'Păr', 'Tabel 3']);
    expect(r.map((b) => b.id),
        ['b1', 'b2', 'b3', 'b4', 'b5', 'b6', 'b7', 'b8', 'b9', 'b10']);
    expect(r.last.name, 'Tabel 10');
  });

  test('lista completă nu se modifică', () {
    final full = [for (var i = 1; i <= 10; i++) Board(id: 'b$i', name: 'T$i')];
    expect(completeBoards(full), isNull);
  });

  test('instalare nouă: 10 tabele', () {
    final r = completeBoards(const [Board(id: 'b1', name: 'Tabel 1')])!;
    expect(r.length, 10);
    expect(r.map((b) => b.id).toSet().length, 10);
  });
}
