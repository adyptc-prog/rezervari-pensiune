import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:pensiune_app/license_screen.dart';
import 'package:pensiune_app/license_service.dart';

const _channel = MethodChannel('pensiune/license');

// Răspunsurile simulate ale părții native (MainActivity / LicenseStore).
Map<String, Object?> _activeLicense({int days = 200, bool lifetime = false}) => {
      'status': 'active',
      'message': 'License active. $days days remaining.',
      'licenseId': 'lic-1',
      'isLifetime': lifetime,
      'validUntil': lifetime ? null : '2027-04-12T00:00:00Z',
      'daysUntilExpiry': lifetime ? null : days,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, Object?> checkResponse;
  late Object? pickResponse; // Map sau PlatformException
  late List<String> calls;
  late Map<String, Object?> shareInfo;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    LicenseService.debugReset();
    LicenseService.debugIsAndroid = true;
    checkResponse = {'status': 'missing', 'message': 'No license file selected.'};
    pickResponse = null;
    calls = [];
    shareInfo = {'fromPartner': false, 'sharedWith': null};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call.method);
      switch (call.method) {
        case 'isLicensed':
          return true; // flag-ul vechi .orgtoken — nu trebuie să mai conteze
        case 'checkLicense':
          return checkResponse;
        case 'getBusinessId':
          return 'organizator-1700000000000';
        case 'pickLicenseFile':
          if (pickResponse is PlatformException) throw pickResponse!;
          return pickResponse;
        case 'getLicenseShareInfo':
          return shareInfo;
        case 'consumePartnerNotice':
          return false;
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
    LicenseService.debugReset();
  });

  group('LicenseService', () {
    test('fără licență: trial activ la prima pornire, poate adăuga', () async {
      await LicenseService.load();
      expect(LicenseService.isLicensed, isFalse);
      expect(LicenseService.isTrialActive, isTrue);
      expect(LicenseService.canAdd, isTrue);
    });

    test('trial expirat și fără licență: nu poate adăuga', () async {
      SharedPreferences.setMockInitialValues({
        'trial_start_date':
            DateTime.now().subtract(const Duration(days: 31)).toIso8601String(),
      });
      await LicenseService.load();
      expect(LicenseService.isTrialActive, isFalse);
      expect(LicenseService.canAdd, isFalse);
    });

    test('pe Android contează trial-ul calculat nativ (ceas protejat)',
        () async {
      // Local pare abia început, dar nativ (ceasul fusese dat înapoi) a expirat.
      checkResponse = {
        'status': 'missing',
        'message': '',
        'trialActive': false,
        'trialDaysLeft': 0,
      };
      await LicenseService.load();
      expect(LicenseService.isTrialActive, isFalse);
      expect(LicenseService.trialDaysLeft, 0);
      expect(LicenseService.canAdd, isFalse);
    });

    test('licență activă de la site', () async {
      checkResponse = _activeLicense();
      await LicenseService.load();
      expect(LicenseService.isLicensed, isTrue);
      expect(LicenseService.isExpiringSoon, isFalse);
      expect(LicenseService.validUntilLocal, isNotNull);
    });

    test('licența care expiră devine inactivă la reverificare', () async {
      checkResponse = _activeLicense();
      await LicenseService.load();
      expect(LicenseService.isLicensed, isTrue);
      checkResponse = {'status': 'invalid', 'message': 'License has expired.'};
      await LicenseService.checkNewLicense();
      expect(LicenseService.isLicensed, isFalse);
    });

    test('avertizare expirare: la ≤10 zile, o singură dată pe zi', () async {
      checkResponse = _activeLicense(days: 7);
      await LicenseService.load();
      expect(LicenseService.isExpiringSoon, isTrue);
      expect(await LicenseService.shouldWarnExpiryToday(), isTrue);
      expect(await LicenseService.shouldWarnExpiryToday(), isFalse);
    });

    test('licența pe viață nu declanșează avertizarea', () async {
      checkResponse = _activeLicense(lifetime: true);
      await LicenseService.load();
      expect(LicenseService.isExpiringSoon, isFalse);
      expect(await LicenseService.shouldWarnExpiryToday(), isFalse);
    });

    test('fișier invalid: mesajul explică, licența existentă rămâne', () async {
      checkResponse = _activeLicense();
      await LicenseService.load();
      pickResponse = {
        'status': 'invalid',
        'message': 'License belongs to another installation.',
      };
      final r = await LicenseService.pickLicenseFile();
      expect(r.success, isFalse);
      expect(r.message, 'License belongs to another installation.');
      expect(LicenseService.isLicensed, isTrue);
    });

    test('selector anulat: fără mesaj de eroare', () async {
      pickResponse = PlatformException(code: 'LICENSE_PICK_CANCELLED');
      final r = await LicenseService.pickLicenseFile();
      expect(r.success, isFalse);
      expect(r.message, isEmpty);
    });

    test('fișier valid: activ după import', () async {
      await LicenseService.load();
      pickResponse = _activeLicense();
      checkResponse = _activeLicense();
      final r = await LicenseService.pickLicenseFile();
      expect(r.success, isTrue);
      expect(LicenseService.isLicensed, isTrue);
    });

    test('vechiul cod .orgtoken nu mai activează aplicația', () async {
      // Nativ, un telefon vechi poate avea încă flag-ul „licensed” — ignorat.
      await LicenseService.load();
      expect(LicenseService.isLicensed, isFalse);
      expect(calls, isNot(contains('isLicensed')));
    });

    test('non-Android: nelimitat, fără apeluri native', () async {
      LicenseService.debugIsAndroid = false;
      await LicenseService.load();
      expect(LicenseService.isLicensed, isTrue);
      expect(LicenseService.canAdd, isTrue);
      expect(calls, isEmpty);
    });
  });

  group('LicenseScreen', () {
    Future<void> pumpScreen(WidgetTester tester,
        {Future<LicenseShareOutcome?> Function()? onShare}) async {
      await LicenseService.load();
      await tester.pumpWidget(MaterialApp(
        home: LicenseScreen(onShareWithPartners: onShare),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('afișează trial-ul și codul de instalare', (tester) async {
      await pumpScreen(tester, onShare: () async => (sent: 1, refused: null));
      expect(find.text('Perioadă de trial'), findsOneWidget);
      expect(find.text('organizator-1700000000000'), findsOneWidget);
      // Fără licență activă nu are ce trimite partenerului.
      expect(find.text('Trimite licența la telefonul partener'), findsNothing);
    });

    testWidgets('licență activă: dată de expirare și trimitere la partener',
        (tester) async {
      checkResponse = _activeLicense();
      var shared = 0;
      await pumpScreen(tester, onShare: () async {
        shared++;
        return (sent: 1, refused: null);
      });
      expect(find.text('Licență activă'), findsOneWidget);
      expect(find.textContaining('200 zile rămase'), findsOneWidget);

      await tester.tap(find.text('Trimite licența la telefonul partener'));
      await tester.pumpAndSettle();
      expect(shared, 1);
      expect(find.text('Licența a fost trimisă prin SMS la 1 telefon.'),
          findsOneWidget);
    });

    testWidgets('trimitere oprită (SMS blocat): fără mesaj înșelător',
        (tester) async {
      checkResponse = _activeLicense();
      await pumpScreen(tester, onShare: () async => null);
      await tester.tap(find.text('Trimite licența la telefonul partener'));
      await tester.pumpAndSettle();
      expect(find.textContaining('Licența a fost trimisă'), findsNothing);
      expect(find.textContaining('Niciun telefon partener'), findsNothing);
    });

    testWidgets('licență care expiră curând e marcată', (tester) async {
      checkResponse = _activeLicense(days: 3);
      await pumpScreen(tester);
      expect(find.text('Licența expiră în curând'), findsOneWidget);
    });

    testWidgets('import reușit trimite licența automat partenerilor',
        (tester) async {
      var shared = 0;
      await pumpScreen(tester, onShare: () async {
        shared++;
        return (sent: 1, refused: null);
      });
      pickResponse = _activeLicense();
      checkResponse = _activeLicense();
      await tester.tap(find.text('Selectează fișierul de licență'));
      await tester.pumpAndSettle();
      expect(find.text('Licență activată cu succes!'), findsOneWidget);
      expect(find.text('Licență activă'), findsOneWidget);
      expect(shared, 1);
    });

    testWidgets('import respins afișează motivul', (tester) async {
      await pumpScreen(tester);
      pickResponse = {
        'status': 'invalid',
        'message': 'License signature is invalid.',
      };
      await tester.tap(find.text('Selectează fișierul de licență'));
      await tester.pumpAndSettle();
      expect(find.text('License signature is invalid.'), findsOneWidget);
    });
  
    testWidgets('al treilea telefon: trimiterea e refuzată, cu explicație',
        (tester) async {
      checkResponse = _activeLicense();
      shareInfo = {'fromPartner': false, 'sharedWith': '***111'};
      await pumpScreen(tester,
          onShare: () async => (sent: 0, refused: 'other_partner'));
      expect(find.text('Folosită și pe telefonul ***111'), findsOneWidget);
      await tester.tap(find.text('Trimite licența la telefonul partener'));
      await tester.pumpAndSettle();
      expect(find.textContaining('deja folosită pe al doilea telefon (***111)'),
          findsOneWidget);
    });

    testWidgets('licența primită de la partener nu poate fi trimisă mai departe',
        (tester) async {
      checkResponse = _activeLicense();
      shareInfo = {'fromPartner': true, 'sharedWith': null};
      await pumpScreen(tester, onShare: () async => (sent: 1, refused: null));
      expect(find.text('Licență primită de la telefonul partener'), findsOneWidget);
      expect(find.text('Trimite licența la telefonul partener'), findsNothing);
    });
  });
}

