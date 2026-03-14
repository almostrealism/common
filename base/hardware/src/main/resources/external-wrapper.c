
void reverse(uint8_t* dbuffer, uint8_t* buffer, int len) {
    uint8_t out[len];

    for (int i = 0; i < len; i++) {
        dbuffer[len - i - 1] = buffer[i];
    }
}

FILE* open(char* f, char* a) {
    FILE *fp;
    fp = fopen(f, a);

    if (fp == NULL) {
        perror("Error while opening file\n");
        exit(EXIT_FAILURE);
    }

    return fp;
}

FILE* ropen(char* dir, char* f) {
    char l[250];
    sprintf(l, "%s/%s", dir, f);
    return open(l, "rb");
}

FILE* ropeni(char* dir, int f) {
    char l[250];
    sprintf(l, "%s/%i", dir, f);
    return open(l, "rb");
}

FILE* wopeni(char* dir, int f) {
    char l[250];
    sprintf(l, "%s/%i", dir, f);
    return open(l, "wb");
}

uint32_t readInt(uint8_t* buffer) {
    // return buffer[0] + (buffer[1] << 8) + (buffer[2] << 16) + (buffer[3] << 24);
    return (buffer[0] << 24) + (buffer[1] << 16) + (buffer[2] << 8) + buffer[3];
}

double readDouble(uint8_t* buffer) {
    double out;
    uint8_t dbuffer[8];
    reverse(dbuffer, buffer, 8);
    memcpy(&out, dbuffer, sizeof(double));
    return out;
}

void writeDouble(uint8_t* buffer, double value) {
    double out;
    uint8_t dbuffer[8];
    memcpy(dbuffer, &value, sizeof(double));
    reverse(buffer, dbuffer, 8);
}

int main(int argc, char **argv) {
    char* dir;
    dir = argv[1];

    uint8_t buffer[4];
    FILE *fp;

    fp = ropen(dir, "count");

    fread(buffer, sizeof(buffer), 1, fp);
    fclose(fp);

    uint32_t count = readInt(buffer);
    // printf("Reading %i arguments...\n", count);

    uint32_t sizes[count];

    fp = ropen(dir, "sizes");

    for (int i = 0; i < count; i++) {
        fread(buffer, sizeof(buffer), 1, fp);
        sizes[i] = readInt(buffer);
        // printf("Argument %i size = %i\n", i, sizes[i]);
    }

    // printf("Read argument sizes\n");
    fclose(fp);

    uint32_t offsets[count];

    fp = ropen(dir, "offsets");

    for (int i = 0; i < count; i++) {
        fread(buffer, sizeof(buffer), 1, fp);
        offsets[i] = readInt(buffer);
        // printf("Argument %i offset = %i\n", i, offsets[i]);
    }

    // printf("Read argument offsets\n");
    fclose(fp);

    uint8_t dbuffer[8];

    long args[count];

    for (int i = 0; i < count; i++) {
        fp = ropeni(dir, i);

        args[i] = (long) malloc((size_t) (sizes[i] * 8));
        double* output = (double *) args[i];

        for (int j = 0; j < sizes[i]; j++) {
            fread(dbuffer, sizeof(dbuffer), 1, fp);
            output[j] = readDouble(dbuffer);
            // printf("(%i,%i): %f\n", i, j, output[j]);
        }

        // printf("Read argument %i data\n", i);

        fclose(fp);
    }

    double* result = (double *) args[0];

    apply(args, offsets, sizes, count);

    for (int i = 0; i < count; i++) {
        fp = wopeni(dir, i);

        double* output = (double *) args[i];

        for (int j = 0; j < sizes[i]; j++) {
            writeDouble(dbuffer, output[j]);
            fwrite(dbuffer, sizeof(dbuffer), 1, fp);
        }

        free(output);
        fclose(fp);
    }
}