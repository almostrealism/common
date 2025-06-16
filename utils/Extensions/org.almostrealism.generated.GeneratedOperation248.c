#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation248_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2815_v2682Offset = (int) offsetArr[0];
jint _2815_v2683Offset = (int) offsetArr[1];
jint _2815_v2682Size = (int) sizeArr[0];
jint _2815_v2683Size = (int) sizeArr[1];
jint _2815_v2682Dim0 = (int) dim0Arr[0];
jint _2815_v2683Dim0 = (int) dim0Arr[1];
double *_2815_v2682 = ((double *) argArr[0]);
double *_2815_v2683 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2815_v2682[_2815_v2682Offset] = 0.0;
for (int _2815_i = 0; _2815_i < 768;) {
_2815_v2682[_2815_v2682Offset] = _2815_v2682[_2815_v2682Offset] + _2815_v2683[_2815_i + _2815_v2683Offset];
_2815_i = _2815_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
