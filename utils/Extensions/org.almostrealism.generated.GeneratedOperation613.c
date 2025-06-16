#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation613_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9382_v6225Offset = (int) offsetArr[0];
jint _9381_v6223Offset = (int) offsetArr[1];
jint _9382_v6225Size = (int) sizeArr[0];
jint _9381_v6223Size = (int) sizeArr[1];
jint _9382_v6225Dim0 = (int) dim0Arr[0];
jint _9381_v6223Dim0 = (int) dim0Arr[1];
double *_9382_v6225 = ((double *) argArr[0]);
double *_9381_v6223 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9382_v6225[_9382_v6225Offset] = (_9381_v6223[_9381_v6223Offset] + _9381_v6223[_9381_v6223Offset + 1]) / 2.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
