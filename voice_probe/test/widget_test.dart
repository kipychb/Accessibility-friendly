import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_probe/main.dart';

void main() {
  testWidgets('Captions and controls are visible before recording', (
    tester,
  ) async {
    await tester.pumpWidget(const MaterialApp(home: VoicePage()));
    expect(find.text('回答字幕'), findsOneWidget);
    expect(find.text('開始說話'), findsOneWidget);
    expect(find.text('尚未辨識'), findsOneWidget);
  });
}
