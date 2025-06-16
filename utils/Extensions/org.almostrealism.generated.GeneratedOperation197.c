#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation197_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2541_v2332Offset = (int) offsetArr[0];
jint _2540_v2330Offset = (int) offsetArr[1];
jint _2541_v2332Size = (int) sizeArr[0];
jint _2540_v2330Size = (int) sizeArr[1];
jint _2541_v2332Dim0 = (int) dim0Arr[0];
jint _2540_v2330Dim0 = (int) dim0Arr[1];
double *_2541_v2332 = ((double *) argArr[0]);
double *_2540_v2330 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2541_v2332[global_id + _2541_v2332Offset] = (((- (global_id % 100)) + (global_id / 100)) == 0) ? _2540_v2330[((global_id / 100) % 10) + _2540_v2330Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
