#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation553_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7784_v5370Offset = (int) offsetArr[0];
jint _7807_v5373Offset = (int) offsetArr[1];
jint _7810_v5378Offset = (int) offsetArr[2];
jint _7784_v5370Size = (int) sizeArr[0];
jint _7807_v5373Size = (int) sizeArr[1];
jint _7810_v5378Size = (int) sizeArr[2];
jint _7784_v5370Dim0 = (int) dim0Arr[0];
jint _7807_v5373Dim0 = (int) dim0Arr[1];
jint _7810_v5378Dim0 = (int) dim0Arr[2];
double *_7784_v5370 = ((double *) argArr[0]);
double *_7807_v5373 = ((double *) argArr[1]);
double *_7810_v5378 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7810_v5378[global_id + _7810_v5378Offset] = (- ((_7807_v5373[(global_id * 3) + _7807_v5373Offset + 1] + _7807_v5373[(global_id * 3) + _7807_v5373Offset + 2] + _7807_v5373[(global_id * 3) + _7807_v5373Offset]) * _7784_v5370[_7784_v5370Offset])) + _7810_v5378[global_id + _7810_v5378Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
