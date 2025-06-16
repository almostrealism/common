#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation872_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13236_v8460Offset = (int) offsetArr[0];
jint _13237_v8461Offset = (int) offsetArr[1];
jint _13236_v8460Size = (int) sizeArr[0];
jint _13237_v8461Size = (int) sizeArr[1];
jint _13236_v8460Dim0 = (int) dim0Arr[0];
jint _13237_v8461Dim0 = (int) dim0Arr[1];
double *_13236_v8460 = ((double *) argArr[0]);
double *_13237_v8461 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13237_v8461[global_id + _13237_v8461Offset] = _13236_v8460[(global_id * 8) + _13236_v8460Offset + 1] + _13236_v8460[(global_id * 8) + _13236_v8460Offset + 2] + _13236_v8460[(global_id * 8) + _13236_v8460Offset + 3] + _13236_v8460[(global_id * 8) + _13236_v8460Offset + 4] + _13236_v8460[(global_id * 8) + _13236_v8460Offset + 5] + _13236_v8460[(global_id * 8) + _13236_v8460Offset + 6] + _13236_v8460[(global_id * 8) + _13236_v8460Offset + 7] + _13236_v8460[(global_id * 8) + _13236_v8460Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
