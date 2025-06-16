#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation628_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9982_v6536Offset = (int) offsetArr[0];
jint _9947_v6489Offset = (int) offsetArr[1];
jint _9967_v6505Offset = (int) offsetArr[2];
jint _9976_v6523Offset = (int) offsetArr[3];
jint _9982_v6536Size = (int) sizeArr[0];
jint _9947_v6489Size = (int) sizeArr[1];
jint _9967_v6505Size = (int) sizeArr[2];
jint _9976_v6523Size = (int) sizeArr[3];
jint _9982_v6536Dim0 = (int) dim0Arr[0];
jint _9947_v6489Dim0 = (int) dim0Arr[1];
jint _9967_v6505Dim0 = (int) dim0Arr[2];
jint _9976_v6523Dim0 = (int) dim0Arr[3];
double *_9982_v6536 = ((double *) argArr[0]);
double *_9947_v6489 = ((double *) argArr[1]);
double *_9967_v6505 = ((double *) argArr[2]);
double *_9976_v6523 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9982_v6536[global_id + _9982_v6536Offset] = ((((_9967_v6505[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9967_v6505Offset + 1] + _9967_v6505[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9967_v6505Offset + 2] + _9967_v6505[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9967_v6505Offset + 3] + _9967_v6505[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9967_v6505Offset]) * -0.25) + ((((- (global_id % 16)) + (global_id / 16)) == 0) ? 1 : 0)) * ((- ((_9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset + 1] + _9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset + 2] + _9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset + 3] + _9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset]) / 4.0)) + _9947_v6489[(global_id / 16) + _9947_v6489Offset])) + ((((_9976_v6523[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9976_v6523Offset + 1] + _9976_v6523[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9976_v6523Offset + 2] + _9976_v6523[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9976_v6523Offset + 3] + _9976_v6523[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9976_v6523Offset]) * -0.25) + ((((- (global_id % 16)) + (global_id / 16)) == 0) ? 1 : 0)) * ((- ((_9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset + 1] + _9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset + 2] + _9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset + 3] + _9947_v6489[((global_id / 64) * 4) + _9947_v6489Offset]) / 4.0)) + _9947_v6489[(global_id / 16) + _9947_v6489Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
