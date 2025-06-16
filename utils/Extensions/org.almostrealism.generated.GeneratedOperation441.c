#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation441_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5669_v4375Offset = (int) offsetArr[0];
jint _5664_v4372Offset = (int) offsetArr[1];
jint _5669_v4375Size = (int) sizeArr[0];
jint _5664_v4372Size = (int) sizeArr[1];
jint _5669_v4375Dim0 = (int) dim0Arr[0];
jint _5664_v4372Dim0 = (int) dim0Arr[1];
double *_5669_v4375 = ((double *) argArr[0]);
double *_5664_v4372 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5669_v4375[global_id + _5669_v4375Offset] = (_5664_v4372[global_id + _5664_v4372Offset + 90] + -0.05576784524704367) / 0.02588281527421291;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
