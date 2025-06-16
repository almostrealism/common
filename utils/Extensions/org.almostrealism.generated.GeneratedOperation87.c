#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation87_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1112_v774Offset = (int) offsetArr[0];
jint _1111_v773Offset = (int) offsetArr[1];
jint _1112_v774Size = (int) sizeArr[0];
jint _1111_v773Size = (int) sizeArr[1];
jint _1112_v774Dim0 = (int) dim0Arr[0];
jint _1111_v773Dim0 = (int) dim0Arr[1];
double *_1112_v774 = ((double *) argArr[0]);
double *_1111_v773 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1112_v774[(global_id * _1112_v774Dim0) + _1112_v774Offset] = (((global_id * 2) % 2) == 1) ? ((_1111_v773[(((global_id / 4) * _1111_v773Dim0) + (global_id % 4)) + _1111_v773Offset] * 2.0) + 1.0) : (_1111_v773[(((global_id / 4) * _1111_v773Dim0) + (global_id % 4)) + _1111_v773Offset] * 2.0);
_1112_v774[(global_id * _1112_v774Dim0) + _1112_v774Offset + 1] = (_1111_v773[(((global_id / 4) * _1111_v773Dim0) + (global_id % 4)) + _1111_v773Offset] * 2.0) + 1.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
