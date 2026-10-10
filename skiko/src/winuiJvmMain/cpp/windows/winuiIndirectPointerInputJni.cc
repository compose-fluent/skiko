#include "winuiIndirectPointerInput.h"

#include <jni.h>
#include <new>
#include <vector>

namespace {
    constexpr const char* EventMethodName = "onNativeEvent";
    constexpr const char* EventMethodSignature =
        "(I[J[J[F[F[Z[F[J[F[F[ZIJZIIIIJ)Z";
    constexpr const char* CancelMethodName = "onNativeCancel";
    constexpr const char* CancelMethodSignature = "()V";

    struct CallbackContext {
        JavaVM* vm = nullptr;
        jobject callback = nullptr;
        jmethodID event_method = nullptr;
        jmethodID cancel_method = nullptr;
    };

    struct JniBinding {
        CallbackContext callback;
        void* native_binding = nullptr;
    };

    bool clear_pending_exception(JNIEnv* env) {
        if (!env->ExceptionCheck()) {
            return false;
        }
        env->ExceptionClear();
        return true;
    }

    JNIEnv* callback_environment(CallbackContext* context, bool* attached) {
        *attached = false;
        if (context == nullptr || context->vm == nullptr) {
            return nullptr;
        }
        JNIEnv* env = nullptr;
        const jint result = context->vm->GetEnv(
            reinterpret_cast<void**>(&env),
            JNI_VERSION_1_8
        );
        if (result == JNI_OK) {
            return env;
        }
        if (result != JNI_EDETACHED ||
            context->vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
            return nullptr;
        }
        *attached = true;
        return env;
    }

    void finish_callback(CallbackContext* context, bool attached) {
        if (attached && context != nullptr && context->vm != nullptr) {
            context->vm->DetachCurrentThread();
        }
    }

    bool initialize_callback_context(
        JNIEnv* env,
        jobject callback,
        CallbackContext* context
    ) {
        if (callback == nullptr || env->GetJavaVM(&context->vm) != JNI_OK) {
            return false;
        }
        jclass callback_class = env->GetObjectClass(callback);
        if (callback_class == nullptr) {
            return false;
        }
        context->event_method = env->GetMethodID(
            callback_class,
            EventMethodName,
            EventMethodSignature
        );
        context->cancel_method = env->GetMethodID(
            callback_class,
            CancelMethodName,
            CancelMethodSignature
        );
        env->DeleteLocalRef(callback_class);
        if (context->event_method == nullptr || context->cancel_method == nullptr ||
            env->ExceptionCheck()) {
            return false;
        }
        context->callback = env->NewGlobalRef(callback);
        return context->callback != nullptr && !env->ExceptionCheck();
    }

    void destroy_callback_context(JNIEnv* env, CallbackContext* context) {
        if (context->callback != nullptr) {
            env->DeleteGlobalRef(context->callback);
            context->callback = nullptr;
        }
    }

    int32_t invoke_event_callback(
        void* raw_context,
        const SkikoWinUIIndirectPointerEventView* event
    ) {
        auto* context = static_cast<CallbackContext*>(raw_context);
        if (context == nullptr || event == nullptr ||
            (event->change_count > 0 && event->changes == nullptr)) {
            return SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED;
        }

        bool attached = false;
        JNIEnv* env = callback_environment(context, &attached);
        if (env == nullptr) {
            return SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED;
        }

        const jsize count = static_cast<jsize>(event->change_count);
        jlongArray pointer_ids = env->NewLongArray(count);
        jlongArray timestamps = env->NewLongArray(count);
        jfloatArray x = env->NewFloatArray(count);
        jfloatArray y = env->NewFloatArray(count);
        jbooleanArray pressed = env->NewBooleanArray(count);
        jfloatArray pressure = env->NewFloatArray(count);
        jlongArray previous_timestamps = env->NewLongArray(count);
        jfloatArray previous_x = env->NewFloatArray(count);
        jfloatArray previous_y = env->NewFloatArray(count);
        jbooleanArray previous_pressed = env->NewBooleanArray(count);

        const auto delete_local_arrays = [&]() {
            if (pointer_ids != nullptr) env->DeleteLocalRef(pointer_ids);
            if (timestamps != nullptr) env->DeleteLocalRef(timestamps);
            if (x != nullptr) env->DeleteLocalRef(x);
            if (y != nullptr) env->DeleteLocalRef(y);
            if (pressed != nullptr) env->DeleteLocalRef(pressed);
            if (pressure != nullptr) env->DeleteLocalRef(pressure);
            if (previous_timestamps != nullptr) env->DeleteLocalRef(previous_timestamps);
            if (previous_x != nullptr) env->DeleteLocalRef(previous_x);
            if (previous_y != nullptr) env->DeleteLocalRef(previous_y);
            if (previous_pressed != nullptr) env->DeleteLocalRef(previous_pressed);
        };

        if (pointer_ids == nullptr || timestamps == nullptr || x == nullptr || y == nullptr ||
            pressed == nullptr || pressure == nullptr || previous_timestamps == nullptr ||
            previous_x == nullptr || previous_y == nullptr || previous_pressed == nullptr) {
            clear_pending_exception(env);
            delete_local_arrays();
            finish_callback(context, attached);
            return SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED;
        }

        std::vector<jlong> pointer_id_values(count);
        std::vector<jlong> timestamp_values(count);
        std::vector<jfloat> x_values(count);
        std::vector<jfloat> y_values(count);
        std::vector<jboolean> pressed_values(count);
        std::vector<jfloat> pressure_values(count);
        std::vector<jlong> previous_timestamp_values(count);
        std::vector<jfloat> previous_x_values(count);
        std::vector<jfloat> previous_y_values(count);
        std::vector<jboolean> previous_pressed_values(count);
        for (jsize index = 0; index < count; ++index) {
            const SkikoWinUIIndirectPointerChangeView& change = event->changes[index];
            pointer_id_values[index] = static_cast<jlong>(change.pointer_id);
            timestamp_values[index] = static_cast<jlong>(change.timestamp_millis);
            x_values[index] = change.x;
            y_values[index] = change.y;
            pressed_values[index] = change.pressed ? JNI_TRUE : JNI_FALSE;
            pressure_values[index] = change.pressure;
            previous_timestamp_values[index] =
                static_cast<jlong>(change.previous_timestamp_millis);
            previous_x_values[index] = change.previous_x;
            previous_y_values[index] = change.previous_y;
            previous_pressed_values[index] = change.previous_pressed ? JNI_TRUE : JNI_FALSE;
        }

        if (count > 0) {
            env->SetLongArrayRegion(pointer_ids, 0, count, pointer_id_values.data());
            env->SetLongArrayRegion(timestamps, 0, count, timestamp_values.data());
            env->SetFloatArrayRegion(x, 0, count, x_values.data());
            env->SetFloatArrayRegion(y, 0, count, y_values.data());
            env->SetBooleanArrayRegion(pressed, 0, count, pressed_values.data());
            env->SetFloatArrayRegion(pressure, 0, count, pressure_values.data());
            env->SetLongArrayRegion(
                previous_timestamps,
                0,
                count,
                previous_timestamp_values.data()
            );
            env->SetFloatArrayRegion(previous_x, 0, count, previous_x_values.data());
            env->SetFloatArrayRegion(previous_y, 0, count, previous_y_values.data());
            env->SetBooleanArrayRegion(
                previous_pressed,
                0,
                count,
                previous_pressed_values.data()
            );
        }

        int32_t result = SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED;
        if (!env->ExceptionCheck()) {
            const jboolean consumed = env->CallBooleanMethod(
                context->callback,
                context->event_method,
                static_cast<jint>(event->type),
                pointer_ids,
                timestamps,
                x,
                y,
                pressed,
                pressure,
                previous_timestamps,
                previous_x,
                previous_y,
                previous_pressed,
                static_cast<jint>(event->primary_directional_motion_axis),
                static_cast<jlong>(event->device_id),
                event->has_device_rect ? JNI_TRUE : JNI_FALSE,
                static_cast<jint>(event->device_rect_left),
                static_cast<jint>(event->device_rect_top),
                static_cast<jint>(event->device_rect_right),
                static_cast<jint>(event->device_rect_bottom),
                static_cast<jlong>(event->frame_id)
            );
            if (!env->ExceptionCheck()) {
                result = consumed
                    ? SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_CONSUMED
                    : SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_UNCONSUMED;
            }
        }
        clear_pending_exception(env);
        delete_local_arrays();
        finish_callback(context, attached);
        return result;
    }

    void invoke_cancel_callback(void* raw_context) {
        auto* context = static_cast<CallbackContext*>(raw_context);
        bool attached = false;
        JNIEnv* env = callback_environment(context, &attached);
        if (env == nullptr) {
            return;
        }
        env->CallVoidMethod(context->callback, context->cancel_method);
        clear_pending_exception(env);
        finish_callback(context, attached);
    }
}

extern "C" {
    JNIEXPORT jlong JNICALL
    Java_org_jetbrains_skiko_winui_WinUIIndirectPointerInputJni_create(
        JNIEnv* env,
        jobject,
        jlong window_pointer,
        jobject callback,
        jintArray unavailable_reason
    ) {
        if (unavailable_reason == nullptr || env->GetArrayLength(unavailable_reason) < 1) {
            return 0;
        }
        auto* binding = new (std::nothrow) JniBinding();
        if (binding == nullptr) {
            return 0;
        }
        if (!initialize_callback_context(env, callback, &binding->callback)) {
            destroy_callback_context(env, &binding->callback);
            delete binding;
            return 0;
        }

        int32_t reason = SKIKO_WINUI_INDIRECT_POINTER_AVAILABLE;
        binding->native_binding = skiko_winui_indirect_pointer_create(
            reinterpret_cast<void*>(static_cast<intptr_t>(window_pointer)),
            &binding->callback,
            invoke_event_callback,
            invoke_cancel_callback,
            &reason
        );
        const jint java_reason = static_cast<jint>(reason);
        env->SetIntArrayRegion(unavailable_reason, 0, 1, &java_reason);
        if (env->ExceptionCheck() || binding->native_binding == nullptr) {
            if (binding->native_binding != nullptr) {
                skiko_winui_indirect_pointer_close(binding->native_binding);
            }
            destroy_callback_context(env, &binding->callback);
            delete binding;
            return 0;
        }
        return reinterpret_cast<jlong>(binding);
    }

    JNIEXPORT jboolean JNICALL
    Java_org_jetbrains_skiko_winui_WinUIIndirectPointerInputJni_cancel(
        JNIEnv*,
        jobject,
        jlong handle
    ) {
        auto* binding = reinterpret_cast<JniBinding*>(handle);
        return binding == nullptr ||
                skiko_winui_indirect_pointer_cancel(binding->native_binding)
            ? JNI_TRUE
            : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL
    Java_org_jetbrains_skiko_winui_WinUIIndirectPointerInputJni_close(
        JNIEnv* env,
        jobject,
        jlong handle
    ) {
        auto* binding = reinterpret_cast<JniBinding*>(handle);
        if (binding == nullptr) {
            return JNI_TRUE;
        }
        if (!skiko_winui_indirect_pointer_close(binding->native_binding)) {
            return JNI_FALSE;
        }
        binding->native_binding = nullptr;
        destroy_callback_context(env, &binding->callback);
        delete binding;
        return JNI_TRUE;
    }

    JNIEXPORT jboolean JNICALL
    Java_org_jetbrains_skiko_winui_WinUIIndirectPointerInputJni_isActive(
        JNIEnv*,
        jobject,
        jlong handle
    ) {
        auto* binding = reinterpret_cast<JniBinding*>(handle);
        return binding != nullptr &&
                skiko_winui_indirect_pointer_is_active(binding->native_binding)
            ? JNI_TRUE
            : JNI_FALSE;
    }

    JNIEXPORT jint JNICALL
    Java_org_jetbrains_skiko_winui_WinUIIndirectPointerInputJni_emitSmoke(
        JNIEnv* env,
        jobject,
        jobject callback
    ) {
        CallbackContext context;
        if (!initialize_callback_context(env, callback, &context)) {
            destroy_callback_context(env, &context);
            return SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED;
        }
        const int32_t result = skiko_winui_indirect_pointer_emit_smoke(
            &context,
            invoke_event_callback,
            invoke_cancel_callback
        );
        destroy_callback_context(env, &context);
        return static_cast<jint>(result);
    }
}
