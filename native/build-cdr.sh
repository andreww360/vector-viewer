#!/usr/bin/env bash
# Compila el motor CorelDRAW para Android: lcms2 + librevenge + libcdr (todo estático)
# y el puente JNI libcdrbridge.so en app/src/main/jniLibs/<abi>/.
# Se ejecuta en GitHub Actions (Ubuntu + NDK). Uso: native/build-cdr.sh arm64-v8a
set -euo pipefail

ABI="${1:-arm64-v8a}"
API=28
case "$ABI" in
  arm64-v8a)   TARGET=aarch64-linux-android ;;
  armeabi-v7a) TARGET=armv7a-linux-androideabi ;;
  x86_64)      TARGET=x86_64-linux-android ;;
  *) echo "ABI no soportada: $ABI"; exit 1 ;;
esac

REPO="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
[ -d "$NDK" ] || { echo "::error::No se encontró el NDK de Android"; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
WORK="$REPO/native-build"
SRC="$WORK/src"
PREFIX="$WORK/out/$ABI"
OUT="$REPO/app/src/main/jniLibs/$ABI"
LOG="$WORK/build-$ABI.log"
mkdir -p "$SRC" "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$OUT"
: > "$LOG"

# Si algo falla, muestra el final del registro como anotación en GitHub (visible sin iniciar sesión)
report_error() {
  local dir; dir="$(pwd)"
  {
    echo "== Paso: ${STEP:-?} (carpeta $dir)"
    tail -n 60 "$LOG"
    if [ -f "$dir/config.log" ]; then echo "== config.log"; grep -n "error" "$dir/config.log" | tail -n 25; fi
  } > "$WORK/error.txt" 2>&1
  python3 -c "import sys; t=open(sys.argv[1]).read()[-6000:]; print('::error title=Motor CDR::' + t.replace('%','%25').replace('\r','').replace('\n','%0A'))" "$WORK/error.txt"
}
trap report_error ERR

run() { "$@" >> "$LOG" 2>&1; }

export CC="$TC/bin/${TARGET}${API}-clang"
export CXX="$TC/bin/${TARGET}${API}-clang++"
export AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" NM="$TC/bin/llvm-nm"
export CFLAGS="-O2 -fPIC -fvisibility=hidden"
export CXXFLAGS="-O2 -fPIC -fvisibility=hidden -std=c++17"
export CPPFLAGS="-I$PREFIX/include"
export LDFLAGS="-L$PREFIX/lib"
export PKG_CONFIG_PATH=""
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
CONF=(--host="$TARGET" --prefix="$PREFIX" --disable-shared --enable-static --with-pic)
JOBS="$(nproc)"

# Boost: solo cabeceras (las del sistema sirven para cualquier arquitectura)
ln -sfn /usr/include/boost "$PREFIX/include/boost"

# zlib viene en el NDK
cat > "$PREFIX/lib/pkgconfig/zlib.pc" <<EOF
Name: zlib
Description: zlib del NDK
Version: 1.2.13
Libs: -lz
Cflags:
EOF

fetch() { # url archivo
  [ -f "$SRC/$2" ] || run curl -fsSL --retry 3 -o "$SRC/$2" "$1"
}

STEP="lcms2"
fetch "https://github.com/mm2/Little-CMS/releases/download/lcms2.16/lcms2-2.16.tar.gz" lcms2.tar.gz
rm -rf "$SRC/lcms2-2.16" && tar -xzf "$SRC/lcms2.tar.gz" -C "$SRC"
cd "$SRC/lcms2-2.16"
run ./configure "${CONF[@]}" --without-jpeg --without-tiff
run make -j"$JOBS" install

STEP="librevenge"
fetch "https://sourceforge.net/projects/libwpd/files/librevenge/librevenge-0.0.5/librevenge-0.0.5.tar.xz/download" librevenge.tar.xz
rm -rf "$SRC/librevenge-0.0.5" && tar -xJf "$SRC/librevenge.tar.xz" -C "$SRC"
cd "$SRC/librevenge-0.0.5"
run ./configure "${CONF[@]}" --without-docs --disable-tests --disable-werror --disable-weffc --enable-streams --enable-generators
run make -j"$JOBS"
run make install

STEP="libcdr"
LIBCDR_REV=b5211a57af37dac59a04f6041822a07f4a7cf7d6
if [ ! -d "$SRC/libcdr/.git" ]; then
  run git clone --filter=blob:none https://github.com/LibreOffice/libcdr "$SRC/libcdr"
fi
cd "$SRC/libcdr"
run git checkout -f "$LIBCDR_REV"
run ./autogen.sh
run ./configure "${CONF[@]}" --without-icu --disable-tools --disable-fuzzers --without-docs --disable-tests --disable-werror --disable-weffc
run make -j"$JOBS" -C src/lib
run make -C src/lib install
run make -C inc install
cp -f libcdr-0.1.pc "$PREFIX/lib/pkgconfig/" 2>/dev/null || true

STEP="puente JNI"
cd "$REPO/native"
run "$CXX" -shared -fPIC -O2 -std=c++17 -fvisibility=hidden \
  -I"$PREFIX/include" -I"$PREFIX/include/librevenge-0.0" -I"$PREFIX/include/libcdr-0.1" \
  cdr_jni.cpp iconv_shim.cpp \
  -L"$PREFIX/lib" \
  -Wl,--start-group -lcdr-0.1 -lrevenge-generators-0.0 -lrevenge-stream-0.0 -lrevenge-0.0 -llcms2 -Wl,--end-group \
  -lz -llog -ldl -static-libstdc++ \
  -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384 \
  -o "$OUT/libcdrbridge.so"
run "$STRIP" --strip-unneeded "$OUT/libcdrbridge.so"

trap - ERR
ls -la "$OUT"
echo "Motor CDR listo: $OUT/libcdrbridge.so"
