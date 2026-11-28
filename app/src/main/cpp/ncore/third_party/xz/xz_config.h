/* SPDX-License-Identifier: 0BSD */
/*
 * XZ Embedded 的用户态构建配置（裁剪版）。
 *
 * 原始文件是 xz-embedded/userspace/xz_config.h，这里只保留 ncore 需要的部分：
 *   - 只要 XZ_DYNALLOC（解压时未知输出大小，字典由解码器自己按需 malloc）
 *   - 校验类型除 CRC32/CRC64 外一律忽略（XZ_DEC_ANY_CHECK），
 *     模块 zip 不可能来自恶意构造以外的途径，没必要为了校验类型多带一份 SHA-256
 *   - BCJ 过滤器全开，xz 用 --x86/--arm 等参数压缩时也能解
 */
#ifndef XZ_CONFIG_H
#define XZ_CONFIG_H

#define XZ_DEC_DYNALLOC
#define XZ_USE_CRC64
#define XZ_DEC_ANY_CHECK

#define XZ_DEC_X86
#define XZ_DEC_POWERPC
#define XZ_DEC_IA64
#define XZ_DEC_ARM
#define XZ_DEC_ARMTHUMB
#define XZ_DEC_ARM64
#define XZ_DEC_RISCV
#define XZ_DEC_SPARC

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "xz.h"

#define kmalloc_obj(type) malloc(sizeof(type))
#define kfree(ptr) free(ptr)
#define vmalloc(size) malloc(size)
#define vfree(ptr) free(ptr)

#define memeq(a, b, size) (memcmp(a, b, size) == 0)
#define memzero(buf, size) memset(buf, 0, size)

#ifndef min
#	define min(x, y) ((x) < (y) ? (x) : (y))
#endif

#ifndef fallthrough
#	if defined(__GNUC__) && __GNUC__ >= 7
#		define fallthrough __attribute__((__fallthrough__))
#	else
#		define fallthrough do {} while (0)
#	endif
#endif

#ifndef __always_inline
#	define __always_inline inline __attribute__((__always_inline__))
#endif

static inline uint32_t get_unaligned_le32(const uint8_t *buf)
{
	return (uint32_t)buf[0]
			| ((uint32_t)buf[1] << 8)
			| ((uint32_t)buf[2] << 16)
			| ((uint32_t)buf[3] << 24);
}

static inline uint32_t get_unaligned_be32(const uint8_t *buf)
{
	return (uint32_t)((uint32_t)buf[0] << 24)
			| ((uint32_t)buf[1] << 16)
			| ((uint32_t)buf[2] << 8)
			| (uint32_t)buf[3];
}

static inline void put_unaligned_le32(uint32_t val, uint8_t *buf)
{
	buf[0] = (uint8_t)val;
	buf[1] = (uint8_t)(val >> 8);
	buf[2] = (uint8_t)(val >> 16);
	buf[3] = (uint8_t)(val >> 24);
}

static inline void put_unaligned_be32(uint32_t val, uint8_t *buf)
{
	buf[0] = (uint8_t)(val >> 24);
	buf[1] = (uint8_t)(val >> 16);
	buf[2] = (uint8_t)(val >> 8);
	buf[3] = (uint8_t)val;
}

#define get_le32 get_unaligned_le32
#define get_be32 get_unaligned_be32

#endif
