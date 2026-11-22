#!/usr/bin/env sh
# Lightweight Gradle bootstrap for environments where the standard wrapper JAR
# is not yet present. It downloads the pinned Gradle distribution once and runs it.
set -eu
GRADLE_VERSION="8.11.1"
BASE_DIR="${GRADLE_USER_HOME:-${HOME}/.gradle}/turrit-bootstrap"
INSTALL_DIR="${BASE_DIR}/gradle-${GRADLE_VERSION}"

if [ -x "${INSTALL_DIR}/bin/gradle" ]; then
  exec "${INSTALL_DIR}/bin/gradle" "$@"
fi

if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  echo "错误：首次运行需要 curl 和 unzip。" >&2
  exit 1
fi

mkdir -p "${BASE_DIR}"
ARCHIVE="${BASE_DIR}/gradle-${GRADLE_VERSION}-bin.zip"
TEMP_DIR="${BASE_DIR}/extract-${GRADLE_VERSION}"
if [ ! -f "${ARCHIVE}" ]; then
  echo "正在下载 Gradle ${GRADLE_VERSION}..."
  curl --fail --location --retry 3 \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    --output "${ARCHIVE}"
fi
rm -rf "${TEMP_DIR}"
mkdir -p "${TEMP_DIR}"
unzip -q "${ARCHIVE}" -d "${TEMP_DIR}"
rm -rf "${INSTALL_DIR}"
mv "${TEMP_DIR}/gradle-${GRADLE_VERSION}" "${INSTALL_DIR}"
rmdir "${TEMP_DIR}"
exec "${INSTALL_DIR}/bin/gradle" "$@"
