#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation34_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _729_v465Offset = (int) offsetArr[0];
jint _729_v467Offset = (int) offsetArr[1];
jint _729_v465Size = (int) sizeArr[0];
jint _729_v467Size = (int) sizeArr[1];
jint _729_v465Dim0 = (int) dim0Arr[0];
jint _729_v467Dim0 = (int) dim0Arr[1];
double *_729_v465 = ((double *) argArr[0]);
double *_729_v467 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_729_v465[global_id + _729_v465Offset] = ((global_id == 30) ? 30.0 : ((global_id == 29) ? 29.0 : ((global_id == 28) ? 28.0 : ((global_id == 27) ? 27.0 : _729_v467[global_id + _729_v467Offset])))) + -15.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
