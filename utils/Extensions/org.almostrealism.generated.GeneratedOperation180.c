#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation180_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2352_v2189Offset = (int) offsetArr[0];
jint _2350_v2184Offset = (int) offsetArr[1];
jint _2352_v2189Size = (int) sizeArr[0];
jint _2350_v2184Size = (int) sizeArr[1];
jint _2352_v2189Dim0 = (int) dim0Arr[0];
jint _2350_v2184Dim0 = (int) dim0Arr[1];
double *_2352_v2189 = ((double *) argArr[0]);
double *_2350_v2184 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2352_v2189[global_id + _2352_v2189Offset] = (((((global_id / 48) % 48) * 49) + (- (global_id % 2304))) == 0) ? _2350_v2184[(global_id / 48) + _2350_v2184Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
