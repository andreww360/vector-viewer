// Puente JNI: CorelDRAW (CDR / CMX) → una página SVG por mesa de trabajo, usando libcdr
// (MPL 2.0) y el generador SVG de librevenge. La app dibuja esos SVG con AndroidSVG.
#include <jni.h>
#include <android/log.h>

#include <librevenge/librevenge.h>
#include <librevenge-generators/librevenge-generators.h>
#include <librevenge-stream/librevenge-stream.h>
#include <libcdr/libcdr.h>

#define LOG_TAG "cdrbridge"

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_anddesign_vectorviewer_render_CdrNative_convertToSvg(JNIEnv *env, jclass, jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  jobjectArray result = nullptr;
  try {
    librevenge::RVNGFileStream input(path);
    librevenge::RVNGStringVector pages;
    librevenge::RVNGSVGDrawingGenerator generator(pages, "svg");
    bool ok = false;
    if (libcdr::CDRDocument::isSupported(&input)) {
      ok = libcdr::CDRDocument::parse(&input, &generator);
    } else if (libcdr::CMXDocument::isSupported(&input)) {
      ok = libcdr::CMXDocument::parse(&input, &generator);
    }
    if (ok && !pages.empty()) {
      jclass byteArrayClass = env->FindClass("[B");
      result = env->NewObjectArray(static_cast<jsize>(pages.size()), byteArrayClass, nullptr);
      for (unsigned i = 0; i < pages.size() && result; ++i) {
        const char *svg = pages[i].cstr();
        const jsize len = static_cast<jsize>(pages[i].size());
        jbyteArray bytes = env->NewByteArray(len);
        if (!bytes) { result = nullptr; break; }
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(svg));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), bytes);
        env->DeleteLocalRef(bytes);
      }
    } else {
      __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "libcdr no pudo interpretar %s", path);
    }
  } catch (...) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "excepción al interpretar %s", path);
    result = nullptr;
  }
  env->ReleaseStringUTFChars(jpath, path);
  return result;
}
