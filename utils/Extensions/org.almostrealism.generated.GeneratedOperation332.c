#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation332_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4275_v3402Offset = (int) offsetArr[0];
jint _4224_v3387Offset = (int) offsetArr[1];
jint _4274_v3401Offset = (int) offsetArr[2];
jint _4275_v3402Size = (int) sizeArr[0];
jint _4224_v3387Size = (int) sizeArr[1];
jint _4274_v3401Size = (int) sizeArr[2];
jint _4275_v3402Dim0 = (int) dim0Arr[0];
jint _4224_v3387Dim0 = (int) dim0Arr[1];
jint _4274_v3401Dim0 = (int) dim0Arr[2];
double *_4275_v3402 = ((double *) argArr[0]);
double *_4224_v3387 = ((double *) argArr[1]);
double *_4274_v3401 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4275_v3402[global_id + _4275_v3402Offset] = ((- ((_4224_v3387[_4224_v3387Offset] + _4224_v3387[_4224_v3387Offset + 1] + _4224_v3387[_4224_v3387Offset + 2] + _4224_v3387[_4224_v3387Offset + 3]) / 4.0)) + _4224_v3387[(global_id / 4) + _4224_v3387Offset]) * _4274_v3401[(global_id % 4) + _4274_v3401Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
