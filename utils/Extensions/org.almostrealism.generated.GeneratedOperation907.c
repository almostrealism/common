#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation907_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13798_v8806Offset = (int) offsetArr[0];
jint _13799_v8807Offset = (int) offsetArr[1];
jint _13798_v8806Size = (int) sizeArr[0];
jint _13799_v8807Size = (int) sizeArr[1];
jint _13798_v8806Dim0 = (int) dim0Arr[0];
jint _13799_v8807Dim0 = (int) dim0Arr[1];
double *_13798_v8806 = ((double *) argArr[0]);
double *_13799_v8807 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13799_v8807[global_id + _13799_v8807Offset] = _13798_v8806[(global_id * 16) + _13798_v8806Offset + 8] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 1] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 13] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 2] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 3] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 4] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 15] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 12] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 5] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 6] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 11] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 7] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 14] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 10] + _13798_v8806[(global_id * 16) + _13798_v8806Offset + 9] + _13798_v8806[(global_id * 16) + _13798_v8806Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
