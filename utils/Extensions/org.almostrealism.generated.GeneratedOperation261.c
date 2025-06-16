#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation261_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3062_v2817Offset = (int) offsetArr[0];
jint _3058_v2815Offset = (int) offsetArr[1];
jint _3062_v2817Size = (int) sizeArr[0];
jint _3058_v2815Size = (int) sizeArr[1];
jint _3062_v2817Dim0 = (int) dim0Arr[0];
jint _3058_v2815Dim0 = (int) dim0Arr[1];
double *_3062_v2817 = ((double *) argArr[0]);
double *_3058_v2815 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3062_v2817[(global_id * _3062_v2817Dim0) + _3062_v2817Offset] = _3058_v2815[(((global_id / _3058_v2815Size) * _3058_v2815Dim0) + (global_id % _3058_v2815Size)) + _3058_v2815Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
