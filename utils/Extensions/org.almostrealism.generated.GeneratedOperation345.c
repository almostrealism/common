#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation345_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4349_v3593Offset = (int) offsetArr[0];
jint _4349_v3594Offset = (int) offsetArr[1];
jint _4349_v3596Offset = (int) offsetArr[2];
jint _4349_v3593Size = (int) sizeArr[0];
jint _4349_v3594Size = (int) sizeArr[1];
jint _4349_v3596Size = (int) sizeArr[2];
jint _4349_v3593Dim0 = (int) dim0Arr[0];
jint _4349_v3594Dim0 = (int) dim0Arr[1];
jint _4349_v3596Dim0 = (int) dim0Arr[2];
double *_4349_v3593 = ((double *) argArr[0]);
double *_4349_v3594 = ((double *) argArr[1]);
double *_4349_v3596 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4349_v3593[global_id + _4349_v3593Offset] = _4349_v3594[global_id + _4349_v3594Offset] * _4349_v3596[global_id + _4349_v3596Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
