#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation640_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10023_v6597Offset = (int) offsetArr[0];
jint _10018_v6586Offset = (int) offsetArr[1];
jint _10023_v6597Size = (int) sizeArr[0];
jint _10018_v6586Size = (int) sizeArr[1];
jint _10023_v6597Dim0 = (int) dim0Arr[0];
jint _10018_v6586Dim0 = (int) dim0Arr[1];
double *_10023_v6597 = ((double *) argArr[0]);
double *_10018_v6586 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10023_v6597[global_id + _10023_v6597Offset] = ((- ((_10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset + 1] + _10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset + 2] + _10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset + 3] + _10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset]) / 4.0)) + _10018_v6586[global_id + _10018_v6586Offset]) * ((- ((_10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset + 1] + _10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset + 2] + _10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset + 3] + _10018_v6586[((global_id / 4) * 4) + _10018_v6586Offset]) / 4.0)) + _10018_v6586[global_id + _10018_v6586Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
