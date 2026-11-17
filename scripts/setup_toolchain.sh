#!/bin/bash
# Bolt Light Browser toolchain setup (sandbox-safe, idempotent)
# v1.3.80 REWRITE (2026-11-09): github.com release downloads now 504 from
# this sandbox, so the old Adoptium-tarball path is dead. The working recipe:
#   - JDK: apt-get download openjdk-21-jdk-headless (works WITHOUT root:
#     downloads the .deb) + dpkg-deb -x into ~/jdk21, then graft the RUNTIME
#     bits from the system openjdk-21-jre (java binary, modules, *.so, conf/,
#     security/, jspawnhelper, tzdb.dat) — see jdk_graft() for the exact
#     list; missing any one of them fails in a DIFFERENT exotic way
#     (slf4j ClassNotFound / posix_spawn error 2 / ZoneInfoFile / trustAnchors)
#   - Gradle 8.9: services.gradle.org zip (slow at first, retries are fast)
#   - Android SDK: dl.google.com is FAST here (13 MB/s) — cmdline-tools,
#     platforms;android-34, build-tools;34.0.0, platform-tools
#   - Keystore: recovered from git history (commit 0ba6a3d, pre-untracking);
#     cert SHA-256 must equal CERT_EXPECT below or updates won't install
set -e
BASE=/home/z/my-project
JDK=$BASE/../jdk21/usr/lib/jvm/java-21-openjdk-amd64
CERT_EXPECT="bcd57eb42ca770a340f78ad64b56999d6238f88ab194cc8c787ee46082938ae4"

# ---------- 1. JDK 21 (grafted headless + system JRE runtime) ----------
jdk_graft() {
  mkdir -p /home/z/jdk21
  cd /tmp
  apt-get download openjdk-21-jdk-headless
  dpkg-deb -x openjdk-21-jdk-headless_*_amd64.deb /home/z/jdk21/
  rm -f openjdk-21-jdk-headless_*.deb
  SYS=/usr/lib/jvm/java-21-openjdk-amd64
  D=$SYS
  # 1a. the java BINARY (copied, never symlinked — $ORIGIN lib resolution)
  rm -f $JDK/bin/java && cp $SYS/bin/java $JDK/bin/java
  # 1b. runtime modules + core libs (modules decides java.home)
  cp $SYS/lib/modules $SYS/lib/jrt-fs.jar $SYS/lib/jvm.cfg $SYS/lib/tzdb.dat $JDK/lib/
  cp $SYS/lib/*.so $JDK/lib/ 2>/dev/null || true
  cp -r $SYS/lib/server $JDK/lib/
  # 1c. jspawnhelper (posix_spawn error 2 without it)
  cp $SYS/lib/jspawnhelper $JDK/lib/ && chmod +x $JDK/lib/jspawnhelper
  # 1d. conf/ + security/ (java.security + cacerts — trustAnchors without)
  cp -r $SYS/conf $JDK/ 2>/dev/null || true
  mkdir -p $JDK/lib/security && cp $SYS/lib/security/* $JDK/lib/security/ 2>/dev/null || true
  cp -r $SYS/lib/jfr $JDK/lib/ 2>/dev/null || true
  cp $SYS/lib/jexec $SYS/lib/jar.binfmt $SYS/lib/jvm.cfg-default $JDK/lib/ 2>/dev/null || true
  cp $SYS/bin/jpackage $SYS/bin/keytool $SYS/bin/rmiregistry $JDK/bin/ 2>/dev/null || true
  # sanity: java.home must be the JDK dir and gradle's cli jar must load
  # (v1.3.81: compare NORMALIZED paths — $JDK carries a literal "../" from
  # BASE and java.home prints the RESOLVED path, so a plain string compare
  # failed a perfectly good graft and killed the whole setup)
  [ "$(cd $JDK && pwd)" = "$($JDK/bin/java -XshowSettings:properties -version 2>&1 | grep -o 'java.home = .*' | cut -d' ' -f3)" ] \
    || { echo "ERROR: java.home did not graft"; exit 1; }
  echo "JDK 21 grafted at $JDK"
}
if [ ! -x "$JDK/bin/javac" ] || ! "$JDK/bin/java" -version >/dev/null 2>&1; then
  echo "[1/4] Building grafted JDK 21..."
  jdk_graft
fi

# ---------- 2. Gradle 8.9 ----------
if [ ! -d "$BASE/gradle-8.9" ]; then
  echo "[2/4] Downloading Gradle 8.9 (retries ok if slow)..."
  for i in 1 2 3; do
    curl -sL --retry 3 -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip && break
    sleep 5
  done
  unzip -q /tmp/gradle.zip -d $BASE && rm -f /tmp/gradle.zip
fi

# ---------- 3. Android SDK (dl.google.com — fast) ----------
if [ ! -d "$BASE/sdk/cmdline-tools" ]; then
  echo "[3/4] Installing Android cmdline-tools..."
  mkdir -p $BASE/sdk/cmdline-tools
  curl -sL --retry 3 -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q /tmp/clt.zip -d /tmp/clt
  mv /tmp/clt/cmdline-tools $BASE/sdk/cmdline-tools/latest
  rm -rf /tmp/clt /tmp/clt.zip
fi
export JAVA_HOME=$JDK
export PATH=$JAVA_HOME/bin:$PATH
if [ ! -d "$BASE/sdk/platforms/android-34" ] || [ ! -d "$BASE/sdk/build-tools/34.0.0" ]; then
  yes | $BASE/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$BASE/sdk --licenses > /dev/null 2>&1 || true
  $BASE/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$BASE/sdk \
    "platforms;android-34" "build-tools;34.0.0" "platform-tools" > /dev/null
fi

# ---------- 4. Repo + creds ----------
# v1.4.10 (2026-11-17): the source repo is the PUBLIC bolt-browser (the old
# private repo is dead). Clone needs NO token; pushes authenticate via the
# explicit push URL in scripts/release.sh.
TOKEN=$(cat $BASE/.ghtoken 2>/dev/null || echo none)
if [ ! -d "$BASE/bolt-browser/.git" ]; then
  git clone -q https://github.com/arfan1210/bolt-browser.git $BASE/bolt-browser
fi
cd $BASE/bolt-browser
git config user.name "arfan1210"
git config user.email "arfan1210@users.noreply.github.com"

# local.properties (sdk.dir + signing creds; never commit)
grep -q "sdk.dir=" local.properties 2>/dev/null || echo "sdk.dir=$BASE/sdk" >> local.properties
grep -q "bolt.storePassword=" local.properties 2>/dev/null || \
  printf 'bolt.storeFile=%s\nbolt.storePassword=ASK_THE_OWNER\nbolt.keyAlias=bolt\nbolt.keyPassword=ASK_THE_OWNER\n' "$BASE/bolt.keystore" >> local.properties

# gradle daemon JVM = the grafted JDK (AGP needs its jlink)
mkdir -p /home/z/.gradle
grep -q "org.gradle.java.home" /home/z/.gradle/gradle.properties 2>/dev/null || \
  echo "org.gradle.java.home=$JDK" >> /home/z/.gradle/gradle.properties

# ---------- 5. Keystore check ----------
if [ ! -f "$BASE/bolt.keystore" ]; then
  echo "WARNING: $BASE/bolt.keystore missing. Ask the maintainer for the"
  echo "rotated backup (password: download/bolt-keystore-password.txt), then verify:"
  echo "  keytool -list -v -keystore $BASE/bolt.keystore -storepass <pw> -alias bolt   # SHA-256 must be $CERT_EXPECT"
fi

echo "[OK] Toolchain ready. Build: cd $BASE/bolt-browser && JAVA_HOME=$JDK GRADLE_USER_HOME=$BASE/.gradle-home PATH=\$JAVA_HOME/bin:\$PATH $BASE/gradle-8.9/bin/gradle assembleRelease -x lint"
