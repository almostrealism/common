#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation319_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3948_v3349Offset = (int) offsetArr[0];
jint _3948_v3350Offset = (int) offsetArr[1];
jint _3948_v3352Offset = (int) offsetArr[2];
jint _3948_v3349Size = (int) sizeArr[0];
jint _3948_v3350Size = (int) sizeArr[1];
jint _3948_v3352Size = (int) sizeArr[2];
jint _3948_v3349Dim0 = (int) dim0Arr[0];
jint _3948_v3350Dim0 = (int) dim0Arr[1];
jint _3948_v3352Dim0 = (int) dim0Arr[2];
double *_3948_v3349 = ((double *) argArr[0]);
double *_3948_v3350 = ((double *) argArr[1]);
double *_3948_v3352 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3948_v3349[global_id + _3948_v3349Offset] = _3948_v3350[global_id + _3948_v3350Offset] * _3948_v3352[global_id + _3948_v3352Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
