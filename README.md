## PayPen

payshap-toy/
├── settings.gradle.kts          ← lists the modules
├── gradlew, gradlew.bat         ← wrapper scripts
├── gradle/wrapper/              ← wrapper config
├── .github/workflows/ci.yml     ← CI pipeline
├── ledger/
│   ├── build.gradle.kts
│   └── src/main/java/com/schmebb/ledger/...
│       src/test/java/com/schmebb/ledger/...
├── common/
│   ├── build.gradle.kts
│   └── src/main/java/com/schmebb/common/...
├── directory/   (Phase 3)
├── hub/         (Phase 5)
└── bank/        (Phase 4)
