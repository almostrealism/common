#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation804_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12189_v8005Offset = (int) offsetArr[0];
jint _12184_v8002Offset = (int) offsetArr[1];
jint _12189_v8005Size = (int) sizeArr[0];
jint _12184_v8002Size = (int) sizeArr[1];
jint _12189_v8005Dim0 = (int) dim0Arr[0];
jint _12184_v8002Dim0 = (int) dim0Arr[1];
double *_12189_v8005 = ((double *) argArr[0]);
double *_12184_v8002 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12189_v8005[global_id + _12189_v8005Offset] = (_12184_v8002[global_id + _12184_v8002Offset] + -0.0476376197363612) / 0.028346990247718612;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
