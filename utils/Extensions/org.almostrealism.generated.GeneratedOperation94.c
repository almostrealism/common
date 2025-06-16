#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation94_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1169_v827Offset = (int) offsetArr[0];
jint _1169_v828Offset = (int) offsetArr[1];
jint _1169_v830Offset = (int) offsetArr[2];
jint _1169_v827Size = (int) sizeArr[0];
jint _1169_v828Size = (int) sizeArr[1];
jint _1169_v830Size = (int) sizeArr[2];
jint _1169_v827Dim0 = (int) dim0Arr[0];
jint _1169_v828Dim0 = (int) dim0Arr[1];
jint _1169_v830Dim0 = (int) dim0Arr[2];
double *_1169_v827 = ((double *) argArr[0]);
double *_1169_v828 = ((double *) argArr[1]);
double *_1169_v830 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1169_v827[global_id + _1169_v827Offset] = _1169_v828[(global_id / 2) + _1169_v828Offset] * _1169_v830[(global_id % 2) + _1169_v830Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
