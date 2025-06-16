#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation221_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2658_v2493Offset = (int) offsetArr[0];
jint _2659_v2490Offset = (int) offsetArr[1];
jint _2662_v2499Offset = (int) offsetArr[2];
jint _2658_v2493Size = (int) sizeArr[0];
jint _2659_v2490Size = (int) sizeArr[1];
jint _2662_v2499Size = (int) sizeArr[2];
jint _2658_v2493Dim0 = (int) dim0Arr[0];
jint _2659_v2490Dim0 = (int) dim0Arr[1];
jint _2662_v2499Dim0 = (int) dim0Arr[2];
double *_2658_v2493 = ((double *) argArr[0]);
double *_2659_v2490 = ((double *) argArr[1]);
double *_2662_v2499 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2662_v2499[global_id + _2662_v2499Offset] = (_2658_v2493[((global_id / 12) * 4) + _2658_v2493Offset + 1] * _2659_v2490[(((global_id * 4) + 1) % 48) + _2659_v2490Offset]) + (_2658_v2493[((global_id / 12) * 4) + _2658_v2493Offset + 2] * _2659_v2490[(((global_id * 4) + 2) % 48) + _2659_v2490Offset]) + (_2658_v2493[((global_id / 12) * 4) + _2658_v2493Offset + 3] * _2659_v2490[(((global_id * 4) + 3) % 48) + _2659_v2490Offset]) + (_2658_v2493[((global_id / 12) * 4) + _2658_v2493Offset] * _2659_v2490[((global_id * 4) % 48) + _2659_v2490Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
