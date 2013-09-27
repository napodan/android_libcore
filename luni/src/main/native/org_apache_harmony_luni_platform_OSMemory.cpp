/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "OSMemory"

#include "JNIHelp.h"
#include "JniConstants.h"
#include "ScopedPrimitiveArray.h"
#include "UniquePtr.h"
#include "java_lang_Float.h"
#include "java_lang_Double.h"

#include <byteswap.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

#if defined(__arm__)
// 32-bit ARM has load/store alignment restrictions for longs.
#define LONG_ALIGNMENT_MASK 0x3
#elif defined(__i386__)
// x86 can load anything at any alignment.
#define LONG_ALIGNMENT_MASK 0x0
#else
#error unknown load/store alignment restrictions for this architecture
#endif

static jobject runtimeInstance;

template <typename T> static T cast(jint address) {
    return reinterpret_cast<T>(static_cast<uintptr_t>(address));
}

static inline void fastSwap16(jshort& v) {
#if defined(__arm__)
    asm("rev16 %[v], %[v]" : [v] "+r" (v));
#else
    v = bswap_16(v);
#endif
}

static inline void fastSwap32(jint& v) {
#if defined(__arm__)
    asm("rev %[v], %[v]" : [v] "+r" (v));
#else
    v = bswap_32(v);
#endif
}

static void swapShorts(jshort* dstShorts, const jshort* srcShorts, size_t count) {
    // Do 32-bit swaps as long as possible...
    jint* dst = reinterpret_cast<jint*>(dstShorts);
    const jint* src = reinterpret_cast<const jint*>(srcShorts);
    for (size_t i = 0; i < count / 2; ++i) {
        jint v = *src++;                            // v=ABCD
        fastSwap32(v);                              // v=DCBA
        jint v2 = (v << 16) | ((v >> 16) & 0xffff); // v=BADC
        *dst++ = v2;
    }
    // ...with one last 16-bit swap if necessary.
    if ((count % 2) != 0) {
        jshort v = *reinterpret_cast<const jshort*>(src);
        fastSwap16(v);
        *reinterpret_cast<jshort*>(dst) = v;
    }
}

static void swapInts(jint* dstInts, const jint* srcInts, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        jint v = *srcInts++;
        fastSwap32(v);
        *dstInts++ = v;
    }
}

static void swapLongs(jlong* dstLongs, const jlong* srcLongs, size_t count) {
    jint* dst = reinterpret_cast<jint*>(dstLongs);
    const jint* src = reinterpret_cast<const jint*>(srcLongs);
    for (size_t i = 0; i < count; ++i) {
        jint v1 = *src++;
        jint v2 = *src++;
        fastSwap32(v1);
        fastSwap32(v2);
        *dst++ = v2;
        *dst++ = v1;
    }
}

static jint OSMemory_malloc(JNIEnv* env, jclass, jint size) {
    static jmethodID trackExternalAllocationMethod =
            env->GetMethodID(JniConstants::vmRuntimeClass, "trackExternalAllocation", "(J)Z");

    jboolean allowed = env->CallBooleanMethod(runtimeInstance, trackExternalAllocationMethod,
            static_cast<jlong>(size));
    if (!allowed) {
        ALOGW("External allocation of %d bytes was rejected\n", size);
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return 0;
    }

    // Our only caller wants zero-initialized memory.
    // calloc(3) may be faster than malloc(3) followed by memset(3).
    void* block = calloc(size + sizeof(jlong), 1);
    if (block == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return 0;
    }

    // Tuck a copy of the size at the head of the buffer.  We need this
    // so OSMemory_free() knows how much memory is being freed.
    jlong* result = reinterpret_cast<jlong*>(block);
    *result++ = size;
    return static_cast<jint>(reinterpret_cast<uintptr_t>(result));
}

static void OSMemory_free(JNIEnv* env, jclass, jint address) {
    static jmethodID trackExternalFreeMethod =
            env->GetMethodID(JniConstants::vmRuntimeClass, "trackExternalFree", "(J)V");

    jlong* p = reinterpret_cast<jlong*>(static_cast<uintptr_t>(address));
    jlong size = *--p;
    env->CallVoidMethod(runtimeInstance, trackExternalFreeMethod, size);
    free(reinterpret_cast<void*>(p));
}

static void OSMemory_memmove(JNIEnv*, jclass, jint dstAddress, jint srcAddress, jlong length) {
    memmove(cast<void*>(dstAddress), cast<const void*>(srcAddress), length);
}

static jbyte OSMemory_peekByte(JNIEnv*, jclass, jint srcAddress) {
    return *cast<const jbyte*>(srcAddress);
}

static void OSMemory_peekByteArray(JNIEnv* env, jclass, jint srcAddress, jbyteArray dst, jint dstOffset, jint byteCount) {
    env->SetByteArrayRegion(dst, dstOffset, byteCount, cast<const jbyte*>(srcAddress));
}

// Implements the peekXArray methods:
// - For unswapped access, we just use the JNI SetXArrayRegion functions.
// - For swapped access, we use GetXArrayElements and our own copy-and-swap routines.
//   GetXArrayElements is disproportionately cheap on Dalvik because it doesn't copy (as opposed
//   to Hotspot, which always copies). The SWAP_FN copies and swaps in one pass, which is cheaper
//   than copying and then swapping in a second pass. Depending on future VM/GC changes, the
//   swapped case might need to be revisited.
#define PEEKER(SCALAR_TYPE, JNI_NAME, SWAP_TYPE, SWAP_FN) { \
    if (swap) { \
        Scoped ## JNI_NAME ## ArrayRW elements(env, dst); \
        if (elements.get() == NULL) { \
            return; \
        } \
        const SWAP_TYPE* src = cast<const SWAP_TYPE*>(srcAddress); \
        SWAP_FN(reinterpret_cast<SWAP_TYPE*>(elements.get()) + dstOffset, src, count); \
    } else { \
        const SCALAR_TYPE* src = cast<const SCALAR_TYPE*>(srcAddress); \
        env->Set ## JNI_NAME ## ArrayRegion(dst, dstOffset, count, src); \
    } \
}

static void OSMemory_peekCharArray(JNIEnv* env, jclass, jint srcAddress, jcharArray dst, jint dstOffset, jint count, jboolean swap) {
    PEEKER(jchar, Char, jshort, swapShorts);
}

static void OSMemory_peekDoubleArray(JNIEnv* env, jclass, jint srcAddress, jdoubleArray dst, jint dstOffset, jint count, jboolean swap) {
    PEEKER(jdouble, Double, jlong, swapLongs);
}

static void OSMemory_peekFloatArray(JNIEnv* env, jclass, jint srcAddress, jfloatArray dst, jint dstOffset, jint count, jboolean swap) {
    PEEKER(jfloat, Float, jint, swapInts);
}

static void OSMemory_peekIntArray(JNIEnv* env, jclass, jint srcAddress, jintArray dst, jint dstOffset, jint count, jboolean swap) {
    PEEKER(jint, Int, jint, swapInts);
}

static void OSMemory_peekLongArray(JNIEnv* env, jclass, jint srcAddress, jlongArray dst, jint dstOffset, jint count, jboolean swap) {
    PEEKER(jlong, Long, jlong, swapLongs);
}

static void OSMemory_peekShortArray(JNIEnv* env, jclass, jint srcAddress, jshortArray dst, jint dstOffset, jint count, jboolean swap) {
    PEEKER(jshort, Short, jshort, swapShorts);
}

static void OSMemory_pokeByte(JNIEnv*, jclass, jint dstAddress, jbyte value) {
    *cast<jbyte*>(dstAddress) = value;
}

static void OSMemory_pokeByteArray(JNIEnv* env, jclass, jint dstAddress, jbyteArray src, jint offset, jint length) {
    env->GetByteArrayRegion(src, offset, length, cast<jbyte*>(dstAddress));
}

// Implements the pokeXArray methods:
// - For unswapped access, we just use the JNI GetXArrayRegion functions.
// - For swapped access, we use GetXArrayElements and our own copy-and-swap routines.
//   GetXArrayElements is disproportionately cheap on Dalvik because it doesn't copy (as opposed
//   to Hotspot, which always copies). The SWAP_FN copies and swaps in one pass, which is cheaper
//   than copying and then swapping in a second pass. Depending on future VM/GC changes, the
//   swapped case might need to be revisited.
#define POKER(SCALAR_TYPE, JNI_NAME, SWAP_TYPE, SWAP_FN) { \
    if (swap) { \
        Scoped ## JNI_NAME ## ArrayRO elements(env, src); \
        if (elements.get() == NULL) { \
            return; \
        } \
        const SWAP_TYPE* src = reinterpret_cast<const SWAP_TYPE*>(elements.get()) + srcOffset; \
        SWAP_FN(cast<SWAP_TYPE*>(dstAddress), src, count); \
    } else { \
        env->Get ## JNI_NAME ## ArrayRegion(src, srcOffset, count, cast<SCALAR_TYPE*>(dstAddress)); \
    } \
}

static void OSMemory_pokeCharArray(JNIEnv* env, jclass, jint dstAddress, jcharArray src, jint srcOffset, jint count, jboolean swap) {
    POKER(jchar, Char, jshort, swapShorts);
}

static void OSMemory_pokeDoubleArray(JNIEnv* env, jclass, jint dstAddress, jdoubleArray src, jint srcOffset, jint count, jboolean swap) {
    POKER(jdouble, Double, jlong, swapLongs);
}

static void OSMemory_pokeFloatArray(JNIEnv* env, jclass, jint dstAddress, jfloatArray src, jint srcOffset, jint count, jboolean swap) {
    POKER(jfloat, Float, jint, swapInts);
}

static void OSMemory_pokeIntArray(JNIEnv* env, jclass, jint dstAddress, jintArray src, jint srcOffset, jint count, jboolean swap) {
    POKER(jint, Int, jint, swapInts);
}

static void OSMemory_pokeLongArray(JNIEnv* env, jclass, jint dstAddress, jlongArray src, jint srcOffset, jint count, jboolean swap) {
    POKER(jlong, Long, jlong, swapLongs);
}

static void OSMemory_pokeShortArray(JNIEnv* env, jclass, jint dstAddress, jshortArray src, jint srcOffset, jint count, jboolean swap) {
    POKER(jshort, Short, jshort, swapShorts);
}

static jshort OSMemory_peekShort(JNIEnv*, jclass, jint srcAddress, jboolean swap) {
    jshort result = *cast<const jshort*>(srcAddress);
    if (swap) {
        fastSwap16(result);
    }
    return result;
}

static void OSMemory_pokeShort(JNIEnv*, jclass, jint dstAddress, jshort value, jboolean swap) {
    if (swap) {
        fastSwap16(value);
    }
    *cast<jshort*>(dstAddress) = value;
}

static jint OSMemory_peekInt(JNIEnv*, jclass, jint srcAddress, jboolean swap) {
    jint result = *cast<const jint*>(srcAddress);
    if (swap) {
        fastSwap32(result);
    }
    return result;
}

static void OSMemory_pokeInt(JNIEnv*, jclass, jint dstAddress, jint value, jboolean swap) {
    if (swap) {
        fastSwap32(value);
    }
    *cast<jint*>(dstAddress) = value;
}

static jlong OSMemory_peekLong(JNIEnv*, jclass, jint srcAddress, jboolean swap) {
    jlong result;
    if ((srcAddress & LONG_ALIGNMENT_MASK) == 0) {
        result = *cast<const jlong*>(srcAddress);
    } else {
        // Handle unaligned memory access one byte at a time
        const jbyte* src = cast<const jbyte*>(srcAddress);
        jbyte* dst = reinterpret_cast<jbyte*>(&result);
        dst[0] = src[0];
        dst[1] = src[1];
        dst[2] = src[2];
        dst[3] = src[3];
        dst[4] = src[4];
        dst[5] = src[5];
        dst[6] = src[6];
        dst[7] = src[7];
    }
    if (swap) {
        result = bswap_64(result);
    }
    return result;
}

static void OSMemory_pokeLong(JNIEnv*, jclass, jint dstAddress, jlong value, jboolean swap) {
    if (swap) {
        value = bswap_64(value);
    }
    if ((dstAddress & LONG_ALIGNMENT_MASK) == 0) {
        *cast<jlong*>(dstAddress) = value;
    } else {
        // Handle unaligned memory access one byte at a time
        const jbyte* src = reinterpret_cast<const jbyte*>(&value);
        jbyte* dst = cast<jbyte*>(dstAddress);
        dst[0] = src[0];
        dst[1] = src[1];
        dst[2] = src[2];
        dst[3] = src[3];
        dst[4] = src[4];
        dst[5] = src[5];
        dst[6] = src[6];
        dst[7] = src[7];
    }
}

static jint OSMemory_mmapImpl(JNIEnv* env, jclass, jint fd, jlong offset, jlong size, jint mapMode) {
    int prot, flags;
    switch (mapMode) {
    case 0: // MapMode.PRIVATE
        prot = PROT_READ|PROT_WRITE;
        flags = MAP_PRIVATE;
        break;
    case 1: // MapMode.READ_ONLY
        prot = PROT_READ;
        flags = MAP_SHARED;
        break;
    case 2: // MapMode.READ_WRITE
        prot = PROT_READ|PROT_WRITE;
        flags = MAP_SHARED;
        break;
    default:
        jniThrowIOException(env, EINVAL);
        ALOGE("bad mapMode %i", mapMode);
        return -1;
    }

    void* mapAddress = mmap(0, size, prot, flags, fd, offset);
    if (mapAddress == MAP_FAILED) {
        jniThrowIOException(env, errno);
    }
    return reinterpret_cast<uintptr_t>(mapAddress);
}

static void OSMemory_munmap(JNIEnv*, jclass, jint address, jlong size) {
    munmap(cast<void*>(address), size);
}

static void OSMemory_load(JNIEnv*, jclass, jint address, jlong size) {
    if (mlock(cast<void*>(address), size) != -1) {
        munlock(cast<void*>(address), size);
    }
}

static jboolean OSMemory_isLoaded(JNIEnv*, jclass, jint address, jlong size) {
    if (size == 0) {
        return JNI_TRUE;
    }

    static int page_size = getpagesize();

    int align_offset = address % page_size;// addr should align with the boundary of a page.
    address -= align_offset;
    size += align_offset;
    int page_count = (size + page_size - 1) / page_size;

    UniquePtr<unsigned char[]> vec(new unsigned char[page_count]);
    int rc = mincore(cast<void*>(address), size, vec.get());
    if (rc == -1) {
        return JNI_FALSE;
    }

    for (int i = 0; i < page_count; ++i) {
        if (vec[i] != 1) {
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

static void OSMemory_msync(JNIEnv*, jclass, jint address, jlong size) {
    msync(cast<void*>(address), size, MS_SYNC);
}

static void OSMemory_unsafeArrayCopy(JNIEnv* env, jclass, jobject dst, jint dstOffset,
        jint byteCount, jbyteArray src, jint srcOffset, jint sizeofElement, jboolean swap) {
    ScopedByteArrayRO srcBytes(env, src);
    if (srcBytes.get() == NULL) {
        return;
    }
    jarray dstArray = reinterpret_cast<jarray>(dst);
    jbyte* dstBytes = reinterpret_cast<jbyte*>(env->GetPrimitiveArrayCritical(dstArray, NULL));
    if (dstBytes == NULL) {
        return;
    }
    if (swap) {
        if (sizeofElement == 2) {
            jshort* dstShorts = reinterpret_cast<jshort*>(dstBytes + dstOffset);
            const jshort* srcShorts = reinterpret_cast<const jshort*>(srcBytes.get()) + srcOffset;
            swapShorts(dstShorts, srcShorts, byteCount / 2);
        } else if (sizeofElement == 4) {
            jint* dstInts = reinterpret_cast<jint*>(dstBytes + dstOffset);
            const jint* srcInts = reinterpret_cast<const jint*>(srcBytes.get()) + srcOffset;
            swapInts(dstInts, srcInts, byteCount / 4);
        } else if (sizeofElement == 8) {
            jlong* dstLongs = reinterpret_cast<jlong*>(dstBytes + dstOffset);
            const jlong* srcLongs = reinterpret_cast<const jlong*>(srcBytes.get()) + srcOffset;
            swapLongs(dstLongs, srcLongs, byteCount / 8);
        }
    } else {
        memmove(dstBytes + dstOffset, srcBytes.get() + srcOffset*sizeofElement, byteCount);
    }
    env->ReleasePrimitiveArrayCritical(dstArray, dstBytes, 0);
}

static JNINativeMethod gMethods[] = {
    NATIVE_METHOD(OSMemory, free, "(I)V"),
    NATIVE_METHOD(OSMemory, isLoaded, "(IJ)Z"),
    NATIVE_METHOD(OSMemory, load, "(IJ)V"),
    NATIVE_METHOD(OSMemory, malloc, "(I)I"),
    NATIVE_METHOD(OSMemory, memmove, "(IIJ)V"),
    NATIVE_METHOD(OSMemory, mmapImpl, "(IJJI)I"),
    NATIVE_METHOD(OSMemory, msync, "(IJ)V"),
    NATIVE_METHOD(OSMemory, munmap, "(IJ)V"),
    NATIVE_METHOD(OSMemory, peekByte, "(I)B"),
    NATIVE_METHOD(OSMemory, peekByteArray, "(I[BII)V"),
    NATIVE_METHOD(OSMemory, peekCharArray, "(I[CIIZ)V"),
    NATIVE_METHOD(OSMemory, peekDoubleArray, "(I[DIIZ)V"),
    NATIVE_METHOD(OSMemory, peekFloatArray, "(I[FIIZ)V"),
    NATIVE_METHOD(OSMemory, peekInt, "(IZ)I"),
    NATIVE_METHOD(OSMemory, peekIntArray, "(I[IIIZ)V"),
    NATIVE_METHOD(OSMemory, peekLong, "(IZ)J"),
    NATIVE_METHOD(OSMemory, peekLongArray, "(I[JIIZ)V"),
    NATIVE_METHOD(OSMemory, peekShort, "(IZ)S"),
    NATIVE_METHOD(OSMemory, peekShortArray, "(I[SIIZ)V"),
    NATIVE_METHOD(OSMemory, pokeByte, "(IB)V"),
    NATIVE_METHOD(OSMemory, pokeByteArray, "(I[BII)V"),
    NATIVE_METHOD(OSMemory, pokeCharArray, "(I[CIIZ)V"),
    NATIVE_METHOD(OSMemory, pokeDoubleArray, "(I[DIIZ)V"),
    NATIVE_METHOD(OSMemory, pokeFloatArray, "(I[FIIZ)V"),
    NATIVE_METHOD(OSMemory, pokeInt, "(IIZ)V"),
    NATIVE_METHOD(OSMemory, pokeIntArray, "(I[IIIZ)V"),
    NATIVE_METHOD(OSMemory, pokeLong, "(IJZ)V"),
    NATIVE_METHOD(OSMemory, pokeLongArray, "(I[JIIZ)V"),
    NATIVE_METHOD(OSMemory, pokeShort, "(ISZ)V"),
    NATIVE_METHOD(OSMemory, pokeShortArray, "(I[SIIZ)V"),
    NATIVE_METHOD(OSMemory, unsafeArrayCopy, "(Ljava/lang/Object;II[BIIZ)V"),
};
void register_org_apache_harmony_luni_platform_OSMemory(JNIEnv* env) {
    jmethodID method_getRuntime = env->GetStaticMethodID(JniConstants::vmRuntimeClass,
            "getRuntime", "()Ldalvik/system/VMRuntime;");
    if (method_getRuntime == NULL) {
        ALOGE("Unable to find VMRuntime methods\n");
        return ;
    }

    jobject instance = env->CallStaticObjectMethod(JniConstants::vmRuntimeClass, method_getRuntime);
    if (instance == NULL) {
        ALOGE("Unable to obtain VMRuntime instance\n");
        return ;
    }
    runtimeInstance = env->NewGlobalRef(instance);

    jniRegisterNativeMethods(env, "org/apache/harmony/luni/platform/OSMemory", gMethods, NELEM(gMethods));
}
