#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation102_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1190_v890Offset = (int) offsetArr[0];
jint _1190_v890Size = (int) sizeArr[0];
jint _1190_v890Dim0 = (int) dim0Arr[0];
double *_1190_v890 = ((double *) argArr[0]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1190_v890[(global_id * _1190_v890Dim0) + _1190_v890Offset] = 0.0;
for (int _1190_i = 0; _1190_i < 3;) {
jint k_1190_i = (global_id * 3) + _1190_i;
_1190_v890[(global_id * _1190_v890Dim0) + _1190_v890Offset] = ((((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 8) ? 0.4910059678542068 : (((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 7) ? 0.7317857290693766 : (((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 6) ? 0.5414732282621073 : (((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 5) ? 0.06500786246040346 : (((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 4) ? 0.16116040313800117 : (((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 3) ? 0.259115295536811 : (((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 2) ? 0.7267729650994087 : (((((((k_1190_i) % 9) / 3) * 1) + (((k_1190_i) % 3) * 3)) == 1) ? 0.875734695593073 : 0.7455632291458816)))))))) * ((((((k_1190_i) % 3) * 2) + ((k_1190_i) / 9)) == 5) ? 0.4152535061345083 : ((((((k_1190_i) % 3) * 2) + ((k_1190_i) / 9)) == 4) ? 0.0034184641277729133 : ((((((k_1190_i) % 3) * 2) + ((k_1190_i) / 9)) == 3) ? 0.4834759316706707 : ((((((k_1190_i) % 3) * 2) + ((k_1190_i) / 9)) == 2) ? 0.48708325944002473 : ((((((k_1190_i) % 3) * 2) + ((k_1190_i) / 9)) == 1) ? 0.45281370802722454 : 0.18497733640004632)))))) + _1190_v890[(global_id * _1190_v890Dim0) + _1190_v890Offset];
_1190_i = _1190_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
