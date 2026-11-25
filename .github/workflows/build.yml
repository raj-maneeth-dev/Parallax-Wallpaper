name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Accept Android SDK licenses
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Download segmentation models
        run: |
          mkdir -p app/src/main/assets
          B=https://storage.googleapis.com/mediapipe-models/image_segmenter
          curl -fL --retry 3 -o app/src/main/assets/selfie_segmenter.tflite $B/selfie_segmenter/float16/latest/selfie_segmenter.tflite
          curl -fL --retry 3 -o app/src/main/assets/deeplab_v3.tflite $B/deeplab_v3/float32/latest/deeplab_v3.tflite
          ls -l app/src/main/assets

      - name: Build debug APK
        run: gradle :app:assembleDebug --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: parallax-debug-apk
          path: app/build/outputs/apk/debug/*.apk
