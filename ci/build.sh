#!/usr/bin/env bash
# خطوات بناء ملف APK — يستدعيه GitHub Actions. يمكن تعديل هذا الملف دون لمس ملف الـ workflow.
set -euo pipefail

echo "== Accept Android SDK licenses =="
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true

echo "== Prepare keystore =="
base64 -d app/rihla.keystore.b64 > app/rihla.keystore

echo "== Assemble web page =="
if [ ! -f app/src/main/assets/www/index.html ]; then
  mkdir -p app/src/main/assets/www
  cat www-src/index.part*.html > app/src/main/assets/www/index.html
fi
ls -la app/src/main/assets/www/

echo "== Gradle build =="
if ! command -v gradle >/dev/null 2>&1; then
  curl -sSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q /tmp/gradle.zip -d /tmp/gradle
  export PATH="/tmp/gradle/gradle-8.9/bin:$PATH"
fi
gradle --version
gradle assembleRelease --no-daemon --stacktrace

cp app/build/outputs/apk/release/app-release.apk rihla.apk
ls -la rihla.apk

echo "== Publish release =="
gh release delete latest --yes --cleanup-tag || true
gh release create latest rihla.apk --title "رحلة المعرفة — أحدث نسخة" --notes "APK مبني تلقائيًا من آخر تعديل. حمّله على الهاتف وثبّته."
