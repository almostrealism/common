#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation225_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2677_v2517Offset = (int) offsetArr[0];
jint _2678_v2519Offset = (int) offsetArr[1];
jint _2677_v2517Size = (int) sizeArr[0];
jint _2678_v2519Size = (int) sizeArr[1];
jint _2677_v2517Dim0 = (int) dim0Arr[0];
jint _2678_v2519Dim0 = (int) dim0Arr[1];
double *_2677_v2517 = ((double *) argArr[0]);
double *_2678_v2519 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2678_v2519[global_id + _2678_v2519Offset] = _2677_v2517[(global_id * 16) + _2677_v2517Offset + 8] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 1] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 13] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 2] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 3] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 4] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 15] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 12] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 5] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 6] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 11] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 7] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 14] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 10] + _2677_v2517[(global_id * 16) + _2677_v2517Offset + 9] + _2677_v2517[(global_id * 16) + _2677_v2517Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
