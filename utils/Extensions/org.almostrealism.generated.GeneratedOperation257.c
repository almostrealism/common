#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation257_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2956_v2804Offset = (int) offsetArr[0];
jint _2951_v2795Offset = (int) offsetArr[1];
jint _2956_v2804Size = (int) sizeArr[0];
jint _2951_v2795Size = (int) sizeArr[1];
jint _2956_v2804Dim0 = (int) dim0Arr[0];
jint _2951_v2795Dim0 = (int) dim0Arr[1];
double *_2956_v2804 = ((double *) argArr[0]);
double *_2951_v2795 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2956_v2804[global_id + _2956_v2804Offset] = - pow(exp(- _2951_v2795[global_id + _2951_v2795Offset]) + 1.0, -2.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
