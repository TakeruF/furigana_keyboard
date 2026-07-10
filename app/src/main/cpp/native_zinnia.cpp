#include <jni.h>

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "zinnia.h"

namespace {

void throw_runtime(JNIEnv *env, const std::string &message) {
  jclass klass = env->FindClass("java/lang/IllegalStateException");
  if (klass != nullptr) env->ThrowNew(klass, message.c_str());
}

class UtfChars {
 public:
  UtfChars(JNIEnv *env, jstring value)
      : env_(env), value_(value), chars_(env->GetStringUTFChars(value, nullptr)) {}
  ~UtfChars() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }
  const char *get() const { return chars_; }

 private:
  JNIEnv *env_;
  jstring value_;
  const char *chars_;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_furiganakeyboard_recognizer_NativeZinnia_nativeCreate(
    JNIEnv *env, jobject, jstring model_path) {
  UtfChars path(env, model_path);
  if (path.get() == nullptr) return 0;
  std::unique_ptr<zinnia::Recognizer> recognizer(zinnia::Recognizer::create());
  if (!recognizer || !recognizer->open(path.get())) {
    std::string error = recognizer ? recognizer->what() : "could not allocate recognizer";
    throw_runtime(env, "Unable to open handwriting model: " + error);
    return 0;
  }
  return reinterpret_cast<jlong>(recognizer.release());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_furiganakeyboard_recognizer_NativeZinnia_nativeRecognize(
    JNIEnv *env, jobject, jlong handle, jint width, jint height,
    jobjectArray strokes, jint limit) {
  auto *recognizer = reinterpret_cast<zinnia::Recognizer *>(handle);
  if (recognizer == nullptr) {
    throw_runtime(env, "Recognizer is closed");
    return nullptr;
  }

  std::unique_ptr<zinnia::Character> character(zinnia::Character::create());
  character->set_width(static_cast<size_t>(std::max(1, width)));
  character->set_height(static_cast<size_t>(std::max(1, height)));

  const jsize stroke_count = env->GetArrayLength(strokes);
  for (jsize stroke_id = 0; stroke_id < stroke_count; ++stroke_id) {
    auto points = static_cast<jfloatArray>(env->GetObjectArrayElement(strokes, stroke_id));
    if (points == nullptr) continue;
    const jsize value_count = env->GetArrayLength(points);
    jfloat *values = env->GetFloatArrayElements(points, nullptr);
    for (jsize i = 0; i + 1 < value_count; i += 2) {
      character->add(static_cast<size_t>(stroke_id),
                     static_cast<int>(values[i]),
                     static_cast<int>(values[i + 1]));
    }
    env->ReleaseFloatArrayElements(points, values, JNI_ABORT);
    env->DeleteLocalRef(points);
  }

  std::unique_ptr<zinnia::Result> result(
      recognizer->classify(*character, static_cast<size_t>(std::max(1, limit))));
  if (!result) {
    throw_runtime(env, "Handwriting classification failed");
    return nullptr;
  }

  jclass candidate_class = env->FindClass(
      "com/example/furiganakeyboard/recognizer/RecognitionCandidate");
  if (candidate_class == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(
      candidate_class, "<init>", "(Ljava/lang/String;F)V");
  if (constructor == nullptr) return nullptr;
  jobjectArray output = env->NewObjectArray(static_cast<jsize>(result->size()),
                                             candidate_class, nullptr);
  for (size_t i = 0; i < result->size(); ++i) {
    jstring value = env->NewStringUTF(result->value(i));
    jobject candidate = env->NewObject(
        candidate_class, constructor, value, static_cast<jfloat>(result->score(i)));
    env->SetObjectArrayElement(output, static_cast<jsize>(i), candidate);
    env->DeleteLocalRef(candidate);
    env->DeleteLocalRef(value);
  }
  return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_furiganakeyboard_recognizer_NativeZinnia_nativeDestroy(
    JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<zinnia::Recognizer *>(handle);
}
