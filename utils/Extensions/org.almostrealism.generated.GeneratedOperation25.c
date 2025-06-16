#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation25_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _562_v363Offset = (int) offsetArr[0];
jint _563_v366Offset = (int) offsetArr[1];
jint _563_v367Offset = (int) offsetArr[2];
jint _562_v363Size = (int) sizeArr[0];
jint _563_v366Size = (int) sizeArr[1];
jint _563_v367Size = (int) sizeArr[2];
jint _562_v363Dim0 = (int) dim0Arr[0];
jint _563_v366Dim0 = (int) dim0Arr[1];
jint _563_v367Dim0 = (int) dim0Arr[2];
double *_562_v363 = ((double *) argArr[0]);
double *_563_v366 = ((double *) argArr[1]);
double *_563_v367 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_562_v363[_562_v363Offset] = 0.002342981809057548;
_562_v363[_562_v363Offset + 1] = 0.0026840193923539286;
_562_v363[_562_v363Offset + 2] = -0.002013972708317378;
_562_v363[_562_v363Offset + 3] = -0.012165182157384656;
_562_v363[_562_v363Offset + 4] = -0.011133803964824829;
_562_v363[_562_v363Offset + 5] = 0.018958876911674638;
_562_v363[_562_v363Offset + 6] = 0.05364813743857179;
_562_v363[_562_v363Offset + 7] = 0.023582649877818124;
_562_v363[_562_v363Offset + 8] = -0.11020537436833551;
_562_v363[_562_v363Offset + 9] = -0.2826902662699673;
_562_v363[_562_v363Offset + 10] = 0.63718820861678;
_562_v363[_562_v363Offset + 11] = -0.28269026626996735;
_562_v363[_562_v363Offset + 12] = -0.11020537436833554;
_562_v363[_562_v363Offset + 13] = 0.023582649877818128;
_562_v363[_562_v363Offset + 14] = 0.05364813743857181;
_562_v363[_562_v363Offset + 15] = 0.01895887691167464;
_562_v363[_562_v363Offset + 16] = -0.01113380396482483;
_562_v363[_562_v363Offset + 17] = -0.012165182157384657;
_562_v363[_562_v363Offset + 18] = -0.002013972708317379;
_562_v363[_562_v363Offset + 19] = 0.0026840193923539286;
_562_v363[_562_v363Offset + 20] = 0.002342981809057548;
_562_v363[_562_v363Offset + 21] = -0.002342981809057548;
_562_v363[_562_v363Offset + 22] = -0.0026840193923539286;
_562_v363[_562_v363Offset + 23] = 0.002013972708317378;
_562_v363[_562_v363Offset + 24] = 0.012165182157384656;
_562_v363[_562_v363Offset + 25] = 0.011133803964824829;
_562_v363[_562_v363Offset + 26] = -0.018958876911674638;
_562_v363[_562_v363Offset + 27] = -0.05364813743857179;
_562_v363[_562_v363Offset + 28] = -0.023582649877818124;
_562_v363[_562_v363Offset + 29] = 0.11020537436833551;
_562_v363[_562_v363Offset + 30] = 0.2826902662699673;
_562_v363[_562_v363Offset + 31] = 0.36281179138321995;
_562_v363[_562_v363Offset + 32] = 0.28269026626996735;
_562_v363[_562_v363Offset + 33] = 0.11020537436833554;
_562_v363[_562_v363Offset + 34] = -0.023582649877818128;
_562_v363[_562_v363Offset + 35] = -0.05364813743857181;
_562_v363[_562_v363Offset + 36] = -0.01895887691167464;
_562_v363[_562_v363Offset + 37] = 0.01113380396482483;
_562_v363[_562_v363Offset + 38] = 0.012165182157384657;
_562_v363[_562_v363Offset + 39] = 0.002013972708317379;
_562_v363[_562_v363Offset + 40] = -0.0026840193923539286;
_562_v363[_562_v363Offset + 41] = -0.002342981809057548;
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset] = _562_v363[(((int) (floor(_563_v367[_563_v367Offset] * 2.0) * 21.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 1] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 1.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 2] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 2.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 3] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 3.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 4] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 4.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 5] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 5.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 6] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 6.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 7] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 7.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 8] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 8.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 9] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 9.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 10] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 10.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 11] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 11.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 12] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 12.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 13] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 13.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 14] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 14.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 15] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 15.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 16] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 16.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 17] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 17.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 18] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 18.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 19] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 19.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];
_563_v366[(global_id * _563_v366Dim0) + _563_v366Offset + 20] = _562_v363[(((int) ((floor(_563_v367[_563_v367Offset] * 2.0) * 21.0) + 20.0)) + (global_id * _562_v363Dim0)) + _562_v363Offset];

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
