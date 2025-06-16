#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_c_Free_apply (JNIEnv* env, jobject thisObject, jlong val) {
	free((double *) val);
}
