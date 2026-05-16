#include <stddef.h>
#include <stdint.h>

typedef uint32_t CC_LONG;

enum {
  CC_MD5_DIGEST_LENGTH = 16,
  CC_SHA1_DIGEST_LENGTH = 20,
  CC_SHA256_DIGEST_LENGTH = 32,
  CC_SHA512_DIGEST_LENGTH = 64,
};

typedef enum {
  kCCHmacAlgSHA1 = 0,
  kCCHmacAlgMD5 = 1,
  kCCHmacAlgSHA256 = 2,
  kCCHmacAlgSHA384 = 3,
  kCCHmacAlgSHA512 = 4,
} CCHmacAlgorithm;

unsigned char *CC_MD5(const void *data, CC_LONG len, unsigned char *md);
unsigned char *CC_SHA1(const void *data, CC_LONG len, unsigned char *md);
unsigned char *CC_SHA256(const void *data, CC_LONG len, unsigned char *md);
unsigned char *CC_SHA512(const void *data, CC_LONG len, unsigned char *md);

void CCHmac(
  CCHmacAlgorithm algorithm,
  const void *key,
  size_t keyLength,
  const void *data,
  size_t dataLength,
  void *macOut
);

typedef uint32_t CCPBKDFAlgorithm;
enum {
    kCCPBKDF2 = 2,
};

typedef uint32_t CCPseudoRandomAlgorithm;
enum {
    kCCPRFHmacAlgSHA1 = 1,
    kCCPRFHmacAlgSHA224 = 2,
    kCCPRFHmacAlgSHA256 = 3,
    kCCPRFHmacAlgSHA384 = 4,
    kCCPRFHmacAlgSHA512 = 5,
};

int CCKeyDerivationPBKDF(
    CCPBKDFAlgorithm algorithm,
    const char *password,
    size_t passwordLen,
    const uint8_t *salt,
    size_t saltLen,
    CCPseudoRandomAlgorithm prf,
    uint32_t rounds,
    uint8_t *derivedKey,
    size_t derivedKeyLen
);

typedef int32_t CCCryptorStatus;
enum {
    kCCSuccess = 0,
    kCCParamError = -4300,
    kCCBufferTooSmall = -4301,
    kCCMemoryFailure = -4302,
    kCCAlignmentError = -4303,
    kCCDecodeError = -4304,
    kCCUnimplemented = -4305,
    kCCOverflow = -4306,
    kCCRNGFailure = -4307,
    kCCUnspecifiedError = -4308,
    kCCCallSequenceError = -4309,
    kCCKeySizeError = -4310,
    kCCInvalidKey = -4311,
};

typedef uint32_t CCOperation;
enum {
    kCCEncrypt = 0,
    kCCDecrypt = 1,
};

typedef uint32_t CCAlgorithm;
enum {
    kCCAlgorithmAES128 = 0,
    kCCAlgorithmAES = 0,
    kCCAlgorithmDES = 1,
    kCCAlgorithm3DES = 2,
    kCCAlgorithmCAST = 3,
    kCCAlgorithmRC4 = 4,
    kCCAlgorithmRC2 = 5,
    kCCAlgorithmBlowfish = 6,
};

typedef uint32_t CCOptions;
enum {
    kCCOptionPKCS7Padding = 1,
    kCCOptionECBMode = 2,
};

CCCryptorStatus CCCrypt(
    CCOperation op,
    CCAlgorithm alg,
    CCOptions options,
    const void *key,
    size_t keyLength,
    const void *iv,
    const void *dataIn,
    size_t dataInLength,
    void *dataOut,
    size_t dataOutAvailable,
    size_t *dataOutMoved
);
