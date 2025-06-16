#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation520_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7166_v5077Offset = (int) offsetArr[0];
jint _7115_v5062Offset = (int) offsetArr[1];
jint _7165_v5076Offset = (int) offsetArr[2];
jint _7166_v5077Size = (int) sizeArr[0];
jint _7115_v5062Size = (int) sizeArr[1];
jint _7165_v5076Size = (int) sizeArr[2];
jint _7166_v5077Dim0 = (int) dim0Arr[0];
jint _7115_v5062Dim0 = (int) dim0Arr[1];
jint _7165_v5076Dim0 = (int) dim0Arr[2];
double *_7166_v5077 = ((double *) argArr[0]);
double *_7115_v5062 = ((double *) argArr[1]);
double *_7165_v5076 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7166_v5077[global_id + _7166_v5077Offset] = ((- ((_7115_v5062[_7115_v5062Offset] + _7115_v5062[_7115_v5062Offset + 1]) / 2.0)) + _7115_v5062[(global_id / 2) + _7115_v5062Offset]) * _7165_v5076[(global_id % 2) + _7165_v5076Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
