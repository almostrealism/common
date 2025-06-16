#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation167_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2199_v2113Offset = (int) offsetArr[0];
jint _2196_v2106Offset = (int) offsetArr[1];
jint _2199_v2113Size = (int) sizeArr[0];
jint _2196_v2106Size = (int) sizeArr[1];
jint _2199_v2113Dim0 = (int) dim0Arr[0];
jint _2196_v2106Dim0 = (int) dim0Arr[1];
double *_2199_v2113 = ((double *) argArr[0]);
double *_2196_v2106 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2199_v2113[global_id + _2199_v2113Offset] = (((((global_id / 9) % 9) * 10) + (- (global_id % 81))) == 0) ? _2196_v2106[(((global_id / 81) * 3) + ((global_id / 9) % 3)) + _2196_v2106Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
