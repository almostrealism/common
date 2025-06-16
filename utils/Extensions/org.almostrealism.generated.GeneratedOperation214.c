#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation214_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2630_v2413Offset = (int) offsetArr[0];
jint _2631_v2416Offset = (int) offsetArr[1];
jint _2633_v2421Offset = (int) offsetArr[2];
jint _2630_v2413Size = (int) sizeArr[0];
jint _2631_v2416Size = (int) sizeArr[1];
jint _2633_v2421Size = (int) sizeArr[2];
jint _2630_v2413Dim0 = (int) dim0Arr[0];
jint _2631_v2416Dim0 = (int) dim0Arr[1];
jint _2633_v2421Dim0 = (int) dim0Arr[2];
double *_2630_v2413 = ((double *) argArr[0]);
double *_2631_v2416 = ((double *) argArr[1]);
double *_2633_v2421 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2633_v2421[global_id + _2633_v2421Offset] = (_2630_v2413[((global_id * 2) % 2) + _2630_v2413Offset] * _2631_v2416[(global_id * 2) + _2631_v2416Offset]) + (_2631_v2416[(global_id * 2) + _2631_v2416Offset + 1] * _2630_v2413[_2630_v2413Offset + 1]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
