/* Usage: probe_test expect-on | expect-off */
#include <stdio.h>
#include <string.h>

#include "opus_probe.h"

static int failures = 0;

static void check_int(const char *name, int got, int want) {
  if (got == want) {
    printf("PASS %s\n", name);
  } else {
    printf("FAIL %s: got %d, want %d\n", name, got, want);
    failures++;
  }
}

int main(int argc, char **argv) {
  int want;
  const char *version;

  if (argc != 2) {
    fprintf(stderr, "usage: %s expect-on|expect-off\n", argv[0]);
    return 2;
  }
  if (strcmp(argv[1], "expect-on") == 0) {
    want = 1;
  } else if (strcmp(argv[1], "expect-off") == 0) {
    want = 0;
  } else {
    fprintf(stderr, "usage: %s expect-on|expect-off\n", argv[0]);
    return 2;
  }

  version = app_opus_version();
  if (version != NULL && strcmp(version, "libopus 1.6") == 0) {
    printf("PASS version\n");
  } else {
    printf("FAIL version: got %s\n", version ? version : "(null)");
    failures++;
  }
  check_int("deep_plc", app_opus_deep_plc_active(), want);
  check_int("osce", app_opus_osce_active(), want);
  check_int("bwe", app_opus_bwe_active(), want);

  return failures == 0 ? 0 : 1;
}
