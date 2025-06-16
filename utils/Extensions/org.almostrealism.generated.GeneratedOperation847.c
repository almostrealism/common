#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation847_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12735_v8369Offset = (int) offsetArr[0];
jint _12735_v8370Offset = (int) offsetArr[1];
jint _12735_v8372Offset = (int) offsetArr[2];
jint _12735_v8369Size = (int) sizeArr[0];
jint _12735_v8370Size = (int) sizeArr[1];
jint _12735_v8372Size = (int) sizeArr[2];
jint _12735_v8369Dim0 = (int) dim0Arr[0];
jint _12735_v8370Dim0 = (int) dim0Arr[1];
jint _12735_v8372Dim0 = (int) dim0Arr[2];
double *_12735_v8369 = ((double *) argArr[0]);
double *_12735_v8370 = ((double *) argArr[1]);
double *_12735_v8372 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12735_v8369[global_id + _12735_v8369Offset] = _12735_v8370[global_id + _12735_v8370Offset + 60] * _12735_v8372[global_id + _12735_v8372Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
