#!/data/data/com.termux/files/usr/bin/bash
# Builds pda-launcher.apk: javac -> d8 -> aapt2 -> zipalign -> apksigner
# Same toolchain as pda-panel; no Gradle, no Kotlin.
set -e
cd "$(dirname "$0")"
BT=${BT:-$ANDROID_HOME/build-tools/37.0.0}
AJ=$ANDROID_HOME/platforms/android-31/android.jar
KS=${KS:-$HOME/.android/launcher.keystore}
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex $OUT/gen

"$BT/aapt2" compile --dir res -o $OUT/res.zip
"$BT/aapt2" link -I "$AJ" --manifest AndroidManifest.xml --java $OUT/gen \
  -o $OUT/unsigned.apk $OUT/res.zip

javac --release 11 -nowarn -cp "$AJ" -d $OUT/classes \
  $(command find src $OUT/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$AJ" --output $OUT/dex $(command find $OUT/classes -name '*.class')
(cd $OUT/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias launcher -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android -dname "CN=pda-launcher" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out pda-launcher.apk $OUT/aligned.apk
echo "built $(pwd)/pda-launcher.apk"
