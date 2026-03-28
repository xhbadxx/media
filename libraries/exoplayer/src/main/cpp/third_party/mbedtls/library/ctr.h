/**
 * Minimal ctr.h stub — only provides mbedtls_ctr_increment_counter
 * needed by aes.c CTR mode. We only use ECB/CBC but aes.c compiles
 * CTR unconditionally.
 */
#ifndef MBEDTLS_CTR_H
#define MBEDTLS_CTR_H

#include <stdint.h>
#include <string.h>

static inline void mbedtls_ctr_increment_counter(unsigned char counter[16])
{
    for (int i = 15; i >= 0; i--) {
        if (++counter[i] != 0) {
            break;
        }
    }
}

#endif /* MBEDTLS_CTR_H */
