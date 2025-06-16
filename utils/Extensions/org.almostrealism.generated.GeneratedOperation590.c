#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation590_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9134_v5820Offset = (int) offsetArr[0];
jint _9135_v5821Offset = (int) offsetArr[1];
jint _9134_v5820Size = (int) sizeArr[0];
jint _9135_v5821Size = (int) sizeArr[1];
jint _9134_v5820Dim0 = (int) dim0Arr[0];
jint _9135_v5821Dim0 = (int) dim0Arr[1];
double *_9134_v5820 = ((double *) argArr[0]);
double *_9135_v5821 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9135_v5821[global_id + _9135_v5821Offset] = _9134_v5820[(global_id * 8) + _9134_v5820Offset + 1] + _9134_v5820[(global_id * 8) + _9134_v5820Offset + 2] + _9134_v5820[(global_id * 8) + _9134_v5820Offset + 3] + _9134_v5820[(global_id * 8) + _9134_v5820Offset + 4] + _9134_v5820[(global_id * 8) + _9134_v5820Offset + 5] + _9134_v5820[(global_id * 8) + _9134_v5820Offset + 6] + _9134_v5820[(global_id * 8) + _9134_v5820Offset + 7] + _9134_v5820[(global_id * 8) + _9134_v5820Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
