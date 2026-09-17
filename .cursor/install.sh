#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for the NostrShot Android app.
#
# Installs the Android SDK (command-line tools, platform 36, build-tools 36.0.0,
# platform-tools) if it is not already present, exposes it on the PATH for
# interactive shells, and writes android/local.properties so Gradle can find it.
#
# The macOS app under mac/ targets macOS 14 (AppKit/SwiftUI) and cannot be built
# on this Linux VM; only the Android app is set up here.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="16111833"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
JAVA_HOME_DEFAULT="/usr/lib/jvm/java-21-openjdk-amd64"

echo "==> NostrShot Android environment bootstrap"
echo "    ANDROID_HOME=$ANDROID_HOME"

# 1) Command-line tools -------------------------------------------------------
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Installing Android command-line tools ($CMDLINE_TOOLS_VERSION)"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  rm -rf "$tmp/extract" && mkdir -p "$tmp/extract"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extract"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/extract/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
else
  echo "==> Android command-line tools already present"
fi

export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 2) SDK packages (sdkmanager is idempotent: no-ops if already installed) ------
echo "==> Ensuring SDK packages (platform-tools, platforms;android-36, build-tools;36.0.0)"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null

# 3) Gradle needs sdk.dir -----------------------------------------------------
echo "==> Writing android/local.properties"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$REPO_ROOT/android/local.properties"

# 4) Persist env for interactive shells (best-effort) -------------------------
PROFILE_FILE="/etc/profile.d/android_sdk.sh"
PROFILE_CONTENT=$(cat <<EOF
export JAVA_HOME="\${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:\$PATH"
EOF
)
if printf '%s\n' "$PROFILE_CONTENT" | sudo tee "$PROFILE_FILE" >/dev/null 2>&1; then
  sudo chmod 644 "$PROFILE_FILE" 2>/dev/null || true
  echo "==> Wrote $PROFILE_FILE"
else
  # Fallback: append (guarded) to the user's bashrc.
  if ! grep -q 'NostrShot Android SDK env' "$HOME/.bashrc" 2>/dev/null; then
    {
      echo "# NostrShot Android SDK env"
      printf '%s\n' "$PROFILE_CONTENT"
    } >> "$HOME/.bashrc"
    echo "==> Appended SDK env to ~/.bashrc"
  fi
fi

echo "==> Warming Gradle wrapper + dependency cache (android)"
( cd "$REPO_ROOT/android" && JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}" ./gradlew --no-daemon -q help >/dev/null ) || true

echo "==> Bootstrap complete."
echo "    Build/test:  cd android && ./gradlew testDebugUnitTest assembleDebug"
