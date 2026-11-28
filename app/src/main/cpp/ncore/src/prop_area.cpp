// SPDX-License-Identifier: GPL-3.0-or-later
// 属性读写核心，逐字取自上游 manager/app/src/main/cpp/prop/src/main.cpp
// （NekoSU 仓库的独立 prop 工具），只做了三处改动：
//   1. 去掉 main()，改成 ncore::prop::{read,write,list} 三个入口；
//   2. SetProperty 多一个 force_direct 参数，用来实现 resetprop -n；
//   3. PROP_DIRNAME 自己兜底（NDK 的 <sys/system_properties.h> 不带它）。
//
// 实现要点（原作者的注释保留了）：bionic 只给 init 一个可写的 property area
// 映射，__system_property_set() 也就没法改第二次 "ro." 属性。所以这里自己
// 以读写方式打开 /dev/__properties__/<context> 的 backing file，按 bionic
// 的 writer 协议直接改原始布局。
#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <linux/futex.h>
#include <sstream>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <vector>

#include "prop_area.h"

// PROP_DIRNAME 在 bionic 的 <sys/_system_properties.h> 里，NDK 的
// <sys/system_properties.h> 不会把它带进来。值是固定的，这里补一份兜底。
#ifndef PROP_DIRNAME
#	define PROP_DIRNAME "/dev/__properties__"
#endif

namespace ncore::prop
{

namespace
{

// ---------------------------------------------------------------------------
// bionic property area layout.
//
// Every system property lives in a shared memory "property area".  Under
// /dev/__properties__ there is one file per SELinux context (plus
// "properties_serial" and "property_info").  bionic maps those files read-only
// into normal processes: only init, which runs the property service, owns a
// writable mapping.
//
// That is why __system_property_add()/__system_property_update() cannot be
// used here: called from a normal process they write through the read-only
// mapping and crash (and even init refuses to update an existing "ro."
// property).  To modify "ro." properties we therefore open the backing file
// read-write ourselves and apply bionic's writer protocol on the raw layout.
// Every other property is set through __system_property_set(), i.e. through
// init's property service, so its checks and triggers still apply.
//
// Layout references (AOSP bionic):
//   libc/system_properties/prop_area.h
//   libc/system_properties/prop_info.h
//   libc/system_properties/system_properties.cpp
//
// Note that bionic assumes init is the only writer of the property area.  The
// tool becomes a second writer; it is meant for short, one-shot updates (boot
// scripts, "hide my unlocked bootloader" style tweaks), not for hammering
// properties while the property service is busy adding hundreds of them.
// ---------------------------------------------------------------------------

constexpr char kPropertyAreaDir[] = PROP_DIRNAME "/";
constexpr char kSerialAreaFileName[] = "properties_serial";

constexpr size_t kPropertyAreaBytesUsedOffset = 0;
constexpr size_t kPropertyAreaSerialOffset = 4;
constexpr size_t kPropertyAreaMagicOffset = 8;
constexpr size_t kPropertyAreaVersionOffset = 12;

// sizeof(prop_area) without the flexible data_[] array.
constexpr size_t kPropertyAreaHeaderSize = 128;
// sizeof(prop_trie_node) without the flexible name[] array.
constexpr size_t kTrieNodeHeaderSize = 20;
// sizeof(prop_info) without the flexible name[] array.
constexpr size_t kPropInfoHeaderSize = 96;

constexpr size_t kTrieNodePropOffset = 4;
constexpr size_t kTrieNodeLeftOffset = 8;
constexpr size_t kTrieNodeRightOffset = 12;
constexpr size_t kTrieNodeChildrenOffset = 16;

constexpr size_t kPropInfoValueOffset = 4;
// offsetof(prop_info, long_property.offset)
constexpr size_t kPropInfoLongOffsetOffset = 4 + 56;

constexpr size_t kLongLegacyErrorBufferSize = 56;
constexpr char kLongLegacyError[] = "Must use __system_property_read_callback() to read";
constexpr size_t kLongLegacyErrorLength = sizeof(kLongLegacyError) - 1;

constexpr uint32_t kPropertyAreaMagic = 0x504f5250; // "PROP"
constexpr uint32_t kPropertyAreaVersion = 0xfc6ed0ab;

// prop_info::kLongFlag: serial bit telling readers that the value does not fit
// in the inline 92 byte buffer and lives elsewhere in the area.  bionic only
// allows such values for "ro." properties.
constexpr uint32_t kLongFlag = 1u << 16;
constexpr uint32_t kSerialCounterMask = 0x00ffffffu;
constexpr uint32_t kSerialCounterMaskWithoutLongFlag = kSerialCounterMask & ~kLongFlag;

static_assert(PROP_VALUE_MAX == 92,
	      "the prop_info layout hard-coded above assumes PROP_VALUE_MAX == 92");
static_assert(kLongLegacyErrorLength < kLongLegacyErrorBufferSize,
	      "legacy long property error message must fit into prop_info's buffer");

// ---------------------------------------------------------------------------
// Small helpers.
// ---------------------------------------------------------------------------

size_t AlignUp4(size_t value)
{
	return (value + 3) & ~static_cast<size_t>(3);
}

bool CheckedRange(size_t offset, size_t length, size_t size)
{
	return offset <= size && length <= size - offset;
}

uint32_t LoadU32(const void *address)
{
	return __atomic_load_n(static_cast<const uint32_t *>(address), __ATOMIC_RELAXED);
}

void StoreU32(void *address, uint32_t value)
{
	__atomic_store_n(static_cast<uint32_t *>(address), value, __ATOMIC_RELAXED);
}

// bionic publishes trie pointers with release/consume ordering; acquire is the
// conservative equivalent on the reader side.
uint32_t LoadPublishedU32(const void *address)
{
	return __atomic_load_n(static_cast<const uint32_t *>(address), __ATOMIC_ACQUIRE);
}

void StorePublishedU32(void *address, uint32_t value)
{
	__atomic_store_n(static_cast<uint32_t *>(address), value, __ATOMIC_RELEASE);
}

void WakeFutex(uint32_t *address)
{
	syscall(SYS_futex, address, FUTEX_WAKE, INT_MAX, nullptr, nullptr, 0);
}

void BumpAreaSerial(uint32_t *serial)
{
	uint32_t previous = __atomic_load_n(serial, __ATOMIC_RELAXED);
	__atomic_store_n(serial, previous + 1, __ATOMIC_RELEASE);
	WakeFutex(serial);
}

std::string SerialAreaPath()
{
	return std::string(kPropertyAreaDir) + kSerialAreaFileName;
}

bool IsPropertyAreaFile(const std::string &path)
{
	constexpr size_t prefixLength = sizeof(kPropertyAreaDir) - 1;
	return path.size() > prefixLength && path.compare(0, prefixLength, kPropertyAreaDir) == 0;
}

// /dev/__properties__/<context> 的文件名就是 SELinux context。被删除的映射
// 在内核里会显示成 "<path> (deleted)"，先把这个后缀剥掉。
std::string AreaBaseName(const std::string &path)
{
	constexpr char kDeletedSuffix[] = " (deleted)";
	std::string trimmed = path;
	if (trimmed.size() > sizeof(kDeletedSuffix) - 1 &&
	    trimmed.compare(trimmed.size() - (sizeof(kDeletedSuffix) - 1),
			    sizeof(kDeletedSuffix) - 1, kDeletedSuffix) == 0) {
		trimmed.resize(trimmed.size() - (sizeof(kDeletedSuffix) - 1));
	}
	size_t slash = trimmed.find_last_of('/');
	return slash == std::string::npos ? trimmed : trimmed.substr(slash + 1);
}

// ---------------------------------------------------------------------------
// Read-only property access through bionic.  These are public NDK APIs and
// always observe the shared memory, even the areas we modify ourselves.
// ---------------------------------------------------------------------------

bool ReadPropertyValue(const std::string &name, std::string *value)
{
	const prop_info *info = __system_property_find(name.c_str());
	if (info == nullptr) {
		return false;
	}

	std::string result;
	__system_property_read_callback(
		info,
		[](void *cookie, const char *, const char *propValue, uint32_t) {
			*static_cast<std::string *>(cookie) = propValue;
		},
		&result);
	*value = std::move(result);
	return true;
}

// ---------------------------------------------------------------------------
// /proc/self/maps: used to locate the file backing a prop_info pointer that
// bionic handed out.
// ---------------------------------------------------------------------------

struct Mapping {
	uintptr_t start = 0;
	uintptr_t end = 0;
	uint64_t offset = 0;
	std::string path;
};

bool ParseSelfMaps(std::vector<Mapping> &mappings)
{
	std::ifstream maps("/proc/self/maps");
	if (!maps) {
		return false;
	}

	std::string line;
	while (std::getline(maps, line)) {
		std::istringstream fields(line);
		std::string range;
		std::string permissions;
		std::string offset;
		std::string device;
		std::string inode;
		if (!(fields >> range >> permissions >> offset >> device >> inode)) {
			continue;
		}

		unsigned long start = 0;
		unsigned long end = 0;
		if (std::sscanf(range.c_str(), "%lx-%lx", &start, &end) != 2) {
			continue;
		}

		std::string path;
		std::getline(fields, path);
		size_t begin = path.find_first_not_of(" \t");
		if (begin == std::string::npos) {
			continue;
		}

		Mapping mapping;
		mapping.start = static_cast<uintptr_t>(start);
		mapping.end = static_cast<uintptr_t>(end);
		mapping.offset = std::strtoull(offset.c_str(), nullptr, 16);
		mapping.path = path.substr(begin);
		mappings.push_back(std::move(mapping));
	}
	return true;
}

const Mapping *FindMapping(const std::vector<Mapping> &mappings, const void *address)
{
	uintptr_t value = reinterpret_cast<uintptr_t>(address);
	for (const auto &mapping : mappings) {
		if (value >= mapping.start && value < mapping.end) {
			return &mapping;
		}
	}
	return nullptr;
}

// A lookup for a property that does not exist yet makes bionic map the property
// area file that would hold it.  Comparing the maps before and after that
// lookup therefore reveals the file to write to.
const Mapping *FindNewPropertyArea(const std::vector<Mapping> &before,
				   const std::vector<Mapping> &after)
{
	for (const auto &mapping : after) {
		if (!IsPropertyAreaFile(mapping.path)) {
			continue;
		}
		bool existed = false;
		for (const auto &previous : before) {
			if (previous.start == mapping.start && previous.path == mapping.path) {
				existed = true;
				break;
			}
		}
		if (!existed) {
			return &mapping;
		}
	}
	return nullptr;
}

struct ListEntry {
	std::string name;
	std::string value;
	std::string context;
};

struct ListState {
	std::vector<ListEntry> entries;
	const std::vector<Mapping> *maps = nullptr;
};

void CollectListEntry(const prop_info *info, void *cookie)
{
	auto *state = static_cast<ListState *>(cookie);
	ListEntry entry;
	__system_property_read_callback(
		info,
		[](void *cookie, const char *name, const char *value, uint32_t) {
			auto *entry = static_cast<ListEntry *>(cookie);
			entry->name = name;
			entry->value = value;
		},
		&entry);
	if (entry.name.empty()) {
		return;
	}
	if (state->maps != nullptr) {
		const Mapping *mapping = FindMapping(*state->maps, info);
		if (mapping != nullptr) {
			entry.context = AreaBaseName(mapping->path);
		}
	}
	state->entries.push_back(std::move(entry));
}

bool SnapshotAll(std::map<std::string, std::string> *out, std::string *error)
{
	(void)error;
	__system_property_foreach(
		[](const prop_info *info, void *cookie) {
			auto *out = static_cast<std::map<std::string, std::string> *>(cookie);
			__system_property_read_callback(
				info,
				[](void *cookie, const char *name, const char *propValue, uint32_t) {
					auto *out =
						static_cast<std::map<std::string, std::string> *>(cookie);
					out->insert_or_assign(name, propValue);
				},
				out);
		},
		out);
	return true;
}

void ShowAllProperties(bool withContext)
{
	ListState state;
	std::vector<Mapping> maps;
	if (withContext) {
		ParseSelfMaps(maps);
		state.maps = &maps;
	}

	__system_property_foreach(CollectListEntry, &state);
	std::sort(state.entries.begin(), state.entries.end(),
		  [](const ListEntry &one, const ListEntry &two) { return one.name < two.name; });

	for (const auto &entry : state.entries) {
		if (withContext) {
			std::cout << "[" << entry.name << "]: [" << entry.context << "]" << std::endl;
		} else {
			std::cout << "[" << entry.name << "]: [" << entry.value << "]" << std::endl;
		}
	}
}

// ---------------------------------------------------------------------------
// Read-write mapping of a property area file.
// ---------------------------------------------------------------------------

class WritableMapping {
    public:
	WritableMapping() = default;
	WritableMapping(const WritableMapping &) = delete;
	WritableMapping &operator=(const WritableMapping &) = delete;
	~WritableMapping()
	{
		Reset();
	}

	bool Open(const std::string &path, std::string *error)
	{
		Reset();

		int fd = open(path.c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW);
		if (fd < 0) {
			*error = "cannot open " + path + " read-write: " + std::strerror(errno);
			return false;
		}

		struct stat status{};
		if (fstat(fd, &status) < 0) {
			int saved = errno;
			close(fd);
			*error = "cannot stat " + path + ": " + std::strerror(saved);
			return false;
		}
		if (!S_ISREG(status.st_mode) ||
		    status.st_size <
			    static_cast<off_t>(kPropertyAreaHeaderSize + kTrieNodeHeaderSize)) {
			close(fd);
			*error = path + " is not a property area";
			return false;
		}

		size_t size = static_cast<size_t>(status.st_size);
		void *memory = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
		int saved = errno;
		close(fd);
		if (memory == MAP_FAILED) {
			*error = "cannot map " + path + " writable: " + std::strerror(saved);
			return false;
		}

		address_ = static_cast<uint8_t *>(memory);
		size_ = size;
		return true;
	}

	uint8_t *Data() const
	{
		return address_;
	}

	size_t Size() const
	{
		return size_;
	}

    private:
	void Reset()
	{
		if (address_ != nullptr) {
			munmap(address_, size_);
			address_ = nullptr;
			size_ = 0;
		}
	}

	uint8_t *address_ = nullptr;
	size_t size_ = 0;
};

bool ValidatePropertyArea(const uint8_t *area, size_t size, std::string *error)
{
	if (size < kPropertyAreaHeaderSize + kTrieNodeHeaderSize) {
		*error = "property area is too small";
		return false;
	}
	if (LoadU32(area + kPropertyAreaMagicOffset) != kPropertyAreaMagic ||
	    LoadU32(area + kPropertyAreaVersionOffset) != kPropertyAreaVersion) {
		*error = "invalid property area header (broken magic/version)";
		return false;
	}
	return true;
}

// ---------------------------------------------------------------------------
// Property area writer.  Mirrors the logic behind prop_area::add() and
// SystemProperties::Update() in bionic.
// ---------------------------------------------------------------------------

bool HasDirtyBackupArea(const uint8_t *area, size_t dataSize)
{
	if (dataSize < kTrieNodeHeaderSize) {
		return false;
	}
	const uint8_t *root = area + kPropertyAreaHeaderSize;
	uint32_t children = LoadU32(root + kTrieNodeChildrenOffset);
	if (children == kTrieNodeHeaderSize) {
		// The first node starts right after the root node: no dirty backup area.
		return false;
	}
	if (children == 0) {
		// Empty area: a modern one reserves the backup area right away.
		return LoadU32(area + kPropertyAreaBytesUsedOffset) ==
		       kTrieNodeHeaderSize + PROP_VALUE_MAX;
	}
	return true;
}

bool AllocateData(uint8_t *area, size_t dataSize, size_t size, uint32_t *offset, std::string *error)
{
	size_t aligned = AlignUp4(size);
	uint32_t used = LoadU32(area + kPropertyAreaBytesUsedOffset);
	if (used > dataSize || aligned > dataSize - used) {
		*error = "property area is full";
		return false;
	}

	*offset = used;
	StoreU32(area + kPropertyAreaBytesUsedOffset, static_cast<uint32_t>(used + aligned));
	// bionic relies on fresh mmap pages being zero, but the area may have been
	// recycled, so make the new object deterministic.
	std::memset(area + kPropertyAreaHeaderSize + *offset, 0, aligned);
	return true;
}

void InitTrieNode(uint8_t *area, uint32_t offset, const char *name, size_t nameLength)
{
	uint8_t *node = area + kPropertyAreaHeaderSize + offset;
	StoreU32(node, static_cast<uint32_t>(nameLength));
	StoreU32(node + kTrieNodePropOffset, 0);
	StoreU32(node + kTrieNodeLeftOffset, 0);
	StoreU32(node + kTrieNodeRightOffset, 0);
	StoreU32(node + kTrieNodeChildrenOffset, 0);
	std::memcpy(node + kTrieNodeHeaderSize, name, nameLength);
}

uint32_t LoadTriePointer(const uint8_t *area, uint32_t nodeOffset, size_t fieldOffset)
{
	return LoadPublishedU32(area + kPropertyAreaHeaderSize + nodeOffset + fieldOffset);
}

void StoreTriePointer(uint8_t *area, uint32_t nodeOffset, size_t fieldOffset, uint32_t value)
{
	StorePublishedU32(area + kPropertyAreaHeaderSize + nodeOffset + fieldOffset, value);
}

// prop_area::cmp_prop_name(): compare by length first, then by content.
int CompareNamePart(const char *one, size_t oneLength, const char *two, size_t twoLength)
{
	if (oneLength < twoLength) {
		return -1;
	}
	if (oneLength > twoLength) {
		return 1;
	}
	if (oneLength == 0) {
		return 0;
	}
	return std::strncmp(one, two, oneLength);
}

// prop_area::find_prop_trie_node(), with the same allocation behaviour.
bool FindTrieNode(uint8_t *area, size_t dataSize, uint32_t rootOffset, const char *name,
		  size_t nameLength, bool allocate, uint32_t *result, std::string *error)
{
	uint32_t current = rootOffset;
	for (;;) {
		if (!CheckedRange(current, kTrieNodeHeaderSize, dataSize)) {
			*error = "corrupted property trie (node offset out of range)";
			return false;
		}

		uint32_t nodeNameLength = LoadU32(area + kPropertyAreaHeaderSize + current);
		if (!CheckedRange(current + kTrieNodeHeaderSize, nodeNameLength, dataSize)) {
			*error = "corrupted property trie (node name out of range)";
			return false;
		}
		const char *nodeName = reinterpret_cast<const char *>(
			area + kPropertyAreaHeaderSize + current + kTrieNodeHeaderSize);

		int comparison = CompareNamePart(name, nameLength, nodeName, nodeNameLength);
		if (comparison == 0) {
			*result = current;
			return true;
		}

		size_t field = comparison < 0 ? kTrieNodeLeftOffset : kTrieNodeRightOffset;
		uint32_t child = LoadTriePointer(area, current, field);
		if (child != 0) {
			current = child;
			continue;
		}

		if (!allocate) {
			*error = "property not found";
			return false;
		}

		uint32_t created = 0;
		if (!AllocateData(area, dataSize, kTrieNodeHeaderSize + nameLength + 1, &created,
				  error)) {
			return false;
		}
		InitTrieNode(area, created, name, nameLength);
		StoreTriePointer(area, current, field, created);
		*result = created;
		return true;
	}
}

bool UpdatePropInfo(uint8_t *area, size_t areaSize, uint32_t dataOffset, const std::string &name,
		    const std::string &value, uint32_t *areaSerial, std::string *error)
{
	size_t dataSize = areaSize - kPropertyAreaHeaderSize;
	if (!CheckedRange(dataOffset, kPropInfoHeaderSize + 1, dataSize)) {
		*error = "corrupted property area (prop_info offset out of range)";
		return false;
	}

	uint8_t *prop = area + kPropertyAreaHeaderSize + dataOffset;
	const char *propName = reinterpret_cast<const char *>(prop + kPropInfoHeaderSize);
	size_t nameLimit = dataSize - dataOffset - kPropInfoHeaderSize;
	size_t propNameLength = strnlen(propName, nameLimit);
	if (propNameLength == 0 || propNameLength == nameLimit) {
		*error = "corrupted property area (unterminated property name)";
		return false;
	}
	if (propNameLength != name.size() || std::memcmp(propName, name.data(), name.size()) != 0) {
		*error = "property area lookup mismatch for \"" + name + "\"";
		return false;
	}

	size_t valueLength = value.size();
	bool longValue = valueLength >= static_cast<size_t>(PROP_VALUE_MAX);
	if (longValue && std::strncmp(propName, "ro.", 3) != 0) {
		*error = "values longer than " + std::to_string(PROP_VALUE_MAX - 1) +
			 " bytes are only supported for ro. properties";
		return false;
	}

	uint32_t *serial = reinterpret_cast<uint32_t *>(prop);
	uint32_t oldSerial = LoadU32(serial);
	bool oldLong = (oldSerial & kLongFlag) != 0;

	// Prepare long value storage first: readers only look there once the serial
	// says the property is long, so it can be filled before the dirty bit is
	// published.  The inline value, in contrast, must only be overwritten after
	// the dirty bit is visible.
	uint32_t longValueOffset = 0;
	bool reuseLongStorage = false;
	if (longValue && oldLong) {
		uint32_t previousRelative = LoadU32(prop + kPropInfoLongOffsetOffset);
		// The offset is relative to this prop_info and always points past the
		// record itself for areas written by bionic.  Reuse the slot when the new
		// value fits so a repeated write does not keep growing the area.
		size_t minimumRelative = kPropInfoHeaderSize + propNameLength + 1;
		if (previousRelative >= minimumRelative && previousRelative <= dataSize &&
		    dataOffset <= dataSize - previousRelative) {
			size_t previousOffset = static_cast<size_t>(dataOffset) + previousRelative;
			const char *previousValue = reinterpret_cast<const char *>(
				area + kPropertyAreaHeaderSize + previousOffset);
			size_t previousLength = strnlen(previousValue, dataSize - previousOffset);
			if (previousLength < dataSize - previousOffset &&
			    valueLength <= previousLength) {
				reuseLongStorage = true;
				longValueOffset = static_cast<uint32_t>(previousOffset);
			}
		}
	}

	if (longValue && !reuseLongStorage) {
		if (!AllocateData(area, dataSize, valueLength + 1, &longValueOffset, error)) {
			return false;
		}
	}
	if (longValue) {
		uint8_t *storage = area + kPropertyAreaHeaderSize + longValueOffset;
		std::memcpy(storage, value.data(), valueLength);
		storage[valueLength] = '\0';
	}

	// Keep a consistent pre-update value in the dirty backup area for readers
	// that observe the dirty serial (bionic's wait-free read protocol).
	if (HasDirtyBackupArea(area, dataSize)) {
		size_t oldLength = oldSerial >> 24;
		if (oldLength >= PROP_VALUE_MAX) {
			oldLength = PROP_VALUE_MAX - 1;
		}
		std::memcpy(area + kPropertyAreaHeaderSize + kTrieNodeHeaderSize,
			    prop + kPropInfoValueOffset, oldLength + 1);
	}
	__atomic_thread_fence(__ATOMIC_RELEASE);

	uint32_t dirtySerial = oldSerial | 1u;
	StoreU32(serial, dirtySerial);

	std::memset(prop + kPropInfoValueOffset, 0, PROP_VALUE_MAX);
	if (longValue) {
		std::memcpy(prop + kPropInfoValueOffset, kLongLegacyError,
			    sizeof(kLongLegacyError));
		StoreU32(prop + kPropInfoLongOffsetOffset, longValueOffset - dataOffset);
	} else {
		std::memcpy(prop + kPropInfoValueOffset, value.data(), valueLength);
	}
	__atomic_thread_fence(__ATOMIC_RELEASE);

	uint32_t serialLength = longValue ? static_cast<uint32_t>(kLongLegacyErrorLength) :
					    static_cast<uint32_t>(valueLength);
	uint32_t lengthFlags = (serialLength << 24) | (longValue ? kLongFlag : 0u);
	StoreU32(serial, lengthFlags | ((dirtySerial + 1) & kSerialCounterMaskWithoutLongFlag));
	WakeFutex(serial);
	BumpAreaSerial(areaSerial);

	// Restore the serial counter so that the modification is not visible through
	// __system_property_serial(); only the length bits (and long-ness) change.
	// This keeps the property looking like the boot-time value it replaced.
	__atomic_thread_fence(__ATOMIC_RELEASE);
	StoreU32(serial, lengthFlags | ((dirtySerial & ~1u) & kSerialCounterMaskWithoutLongFlag));
	return true;
}

bool InsertPropInfo(uint8_t *area, size_t areaSize, const std::string &name,
		    const std::string &value, uint32_t *areaSerial, std::string *error)
{
	size_t dataSize = areaSize - kPropertyAreaHeaderSize;

	// Walk the trie, creating the missing nodes like prop_area::find_property()
	// does when it is asked to allocate.
	uint32_t current = 0;
	const char *remaining = name.data();
	size_t remainingLength = name.size();
	for (;;) {
		size_t partLength = remainingLength;
		bool wantSubtree = false;
		for (size_t i = 0; i < remainingLength; ++i) {
			if (remaining[i] == '.') {
				partLength = i;
				wantSubtree = true;
				break;
			}
		}
		if (partLength == 0) {
			*error = "invalid property name";
			return false;
		}

		uint32_t children = LoadTriePointer(area, current, kTrieNodeChildrenOffset);
		if (children == 0) {
			if (!AllocateData(area, dataSize, kTrieNodeHeaderSize + partLength + 1,
					  &children, error)) {
				return false;
			}
			InitTrieNode(area, children, remaining, partLength);
			StoreTriePointer(area, current, kTrieNodeChildrenOffset, children);
		}

		uint32_t node = 0;
		if (!FindTrieNode(area, dataSize, children, remaining, partLength, true, &node,
				  error)) {
			return false;
		}
		current = node;

		if (!wantSubtree) {
			break;
		}
		remaining += partLength + 1;
		remainingLength -= partLength + 1;
	}

	uint32_t existing = LoadTriePointer(area, current, kTrieNodePropOffset);
	if (existing != 0) {
		return UpdatePropInfo(area, areaSize, existing, name, value, areaSerial, error);
	}

	uint32_t propOffset = 0;
	if (!AllocateData(area, dataSize, kPropInfoHeaderSize + name.size() + 1, &propOffset,
			  error)) {
		return false;
	}

	bool longValue = value.size() >= static_cast<size_t>(PROP_VALUE_MAX);
	uint32_t longValueOffset = 0;
	if (longValue) {
		if (std::strncmp(name.data(), "ro.", 3) != 0) {
			*error = "values longer than " + std::to_string(PROP_VALUE_MAX - 1) +
				 " bytes are only supported for ro. properties";
			return false;
		}
		if (!AllocateData(area, dataSize, value.size() + 1, &longValueOffset, error)) {
			return false;
		}
	}

	uint8_t *prop = area + kPropertyAreaHeaderSize + propOffset;
	// The name follows the fixed prop_info header; allocation zeroed the bytes,
	// so the terminating NUL is already in place.
	std::memcpy(prop + kPropInfoHeaderSize, name.data(), name.size());

	uint32_t serial = 0;
	if (longValue) {
		uint8_t *storage = area + kPropertyAreaHeaderSize + longValueOffset;
		std::memcpy(storage, value.data(), value.size());
		std::memcpy(prop + kPropInfoValueOffset, kLongLegacyError,
			    sizeof(kLongLegacyError));
		StoreU32(prop + kPropInfoLongOffsetOffset, longValueOffset - propOffset);
		serial = (static_cast<uint32_t>(kLongLegacyErrorLength) << 24) | kLongFlag;
	} else {
		std::memcpy(prop + kPropInfoValueOffset, value.data(), value.size());
		serial = static_cast<uint32_t>(value.size()) << 24;
	}
	StoreU32(prop, serial);

	// Publish the prop_info to readers only once it is fully initialised.
	__atomic_thread_fence(__ATOMIC_RELEASE);
	StoreTriePointer(area, current, kTrieNodePropOffset, propOffset);

	BumpAreaSerial(areaSerial);
	return true;
}

// ---------------------------------------------------------------------------
// Command line operations.
// ---------------------------------------------------------------------------

// 定义在文件末尾的删除/紧凑一节，SetProperty 需要提前用到。
bool ResolveContextPath(const std::string &name, std::string *path);

bool IsValidPropertyName(const std::string &name, std::string *error)
{
	// Same rules as init's IsLegalPropertyName() (system/core/init/util.cpp).
	// PROP_NAME_MAX is deliberately not enforced: init itself creates ro.*
	// properties that are longer than it (ro.appcompat_override.*).
	if (name.empty()) {
		*error = "property name is empty";
		return false;
	}
	if (name.front() == '.' || name.back() == '.') {
		*error = "property name must not start or end with '.'";
		return false;
	}
	for (size_t i = 0; i < name.size(); ++i) {
		char character = name[i];
		if (character == '.') {
			if (name[i - 1] == '.') {
				*error = "property name must not contain \"..\"";
				return false;
			}
			continue;
		}
		if (character == '_' || character == '-' || character == '@' || character == ':') {
			continue;
		}
		if ((character >= 'a' && character <= 'z') ||
		    (character >= 'A' && character <= 'Z') ||
		    (character >= '0' && character <= '9')) {
			continue;
		}
		*error = "illegal character in property name";
		return false;
	}
	if (name.compare(0, 4, "ctl.") == 0) {
		*error = "ctl.* properties are handled by init and cannot be set directly";
		return false;
	}
	return true;
}

bool SetProperty(const std::string &name, const std::string &value, bool forceDirect,
		 std::string *error)
{
	if (!IsValidPropertyName(name, error)) {
		return false;
	}

	// Non-ro properties go through the property service, exactly like
	// setprop(1): init enforces the SELinux and type checks, persists persist.*
	// values and runs the property triggers.  Only ro.* properties are written
	// directly below, because the service refuses to update them a second time.
	if (!forceDirect && !name.starts_with("ro.")) {
		if (value.size() >= static_cast<size_t>(PROP_VALUE_MAX)) {
			*error = "values longer than " + std::to_string(PROP_VALUE_MAX - 1) +
				 " bytes are only supported for ro. properties";
			return false;
		}
		errno = 0;
		int result = __system_property_set(name.c_str(), value.c_str());
		if (result != 0) {
			*error = "property service rejected \"" + name + "\"";
			if (errno != 0) {
				*error += ": ";
				*error += std::strerror(errno);
			}
			return false;
		}
		return true;
	}

	std::vector<Mapping> mapsBefore;
	if (!ParseSelfMaps(mapsBefore)) {
		*error = "cannot read /proc/self/maps";
		return false;
	}

	// Ask bionic where the property lives.  When it does not exist yet this also
	// makes bionic map the area that would hold it, which is how we find the
	// file below.
	const prop_info *info = __system_property_find(name.c_str());

	std::vector<Mapping> mapsAfter;
	if (!ParseSelfMaps(mapsAfter)) {
		*error = "cannot read /proc/self/maps";
		return false;
	}

	std::string areaPath;
	uint32_t dataOffset = 0;
	if (info != nullptr) {
		const Mapping *areaMapping = FindMapping(mapsAfter, info);
		if (areaMapping == nullptr || !IsPropertyAreaFile(areaMapping->path) ||
		    areaMapping->offset != 0) {
			*error = "cannot locate the property area backing \"" + name + "\"";
			return false;
		}
		uintptr_t infoAddress = reinterpret_cast<uintptr_t>(info);
		if (infoAddress < areaMapping->start + kPropertyAreaHeaderSize) {
			*error = "property area mapping is inconsistent";
			return false;
		}
		areaPath = areaMapping->path;
		dataOffset = static_cast<uint32_t>(infoAddress - areaMapping->start -
						   kPropertyAreaHeaderSize);
	} else if (!ResolveContextPath(name, &areaPath)) {
		// 没有可用的 property_info（很老的设备）时，退回到"find 触发了懒映射"
		// 的推断：bionic 第一次访问某个 context 时才会新 mmap 它的文件。
		const Mapping *areaMapping = FindNewPropertyArea(mapsBefore, mapsAfter);
		if (areaMapping == nullptr || areaMapping->offset != 0) {
			*error = "cannot resolve the property area for \"" + name +
				 "\" (unknown property context)";
			return false;
		}
		areaPath = areaMapping->path;
	}

	WritableMapping area;
	if (!area.Open(areaPath, error) ||
	    !ValidatePropertyArea(area.Data(), area.Size(), error)) {
		return false;
	}

	WritableMapping serialArea;
	if (!serialArea.Open(SerialAreaPath(), error) ||
	    !ValidatePropertyArea(serialArea.Data(), serialArea.Size(), error)) {
		return false;
	}
	uint32_t *areaSerial =
		reinterpret_cast<uint32_t *>(serialArea.Data() + kPropertyAreaSerialOffset);

	bool updated;
	if (info != nullptr) {
		updated = UpdatePropInfo(area.Data(), area.Size(), dataOffset, name, value,
					 areaSerial, error);
	} else {
		updated = InsertPropInfo(area.Data(), area.Size(), name, value, areaSerial, error);
	}
	if (!updated) {
		return false;
	}

	// Fast-fail: surface a write that did not actually take effect.
	std::string actual;
	if (!ReadPropertyValue(name, &actual)) {
		*error = "property \"" + name + "\" disappeared after the update";
		return false;
	}
	if (actual != value) {
		*error = "verification failed for \"" + name + "\": expected \"" + value +
			 "\", got \"" + actual + "\"";
		return false;
	}
	return true;
}

// ---------------------------------------------------------------------------
// Deletion and compaction.
// ---------------------------------------------------------------------------

// Walk the trie down to the node that owns this exact property name.  This is
// the read-only counterpart of the traversal in InsertPropInfo().
bool FindPropertyNode(uint8_t *area, size_t dataSize, const std::string &name, uint32_t *result,
		      std::string *error)
{
	uint32_t current = 0;
	const char *remaining = name.data();
	size_t remainingLength = name.size();
	for (;;) {
		size_t partLength = remainingLength;
		bool wantSubtree = false;
		for (size_t i = 0; i < remainingLength; ++i) {
			if (remaining[i] == '.') {
				partLength = i;
				wantSubtree = true;
				break;
			}
		}
		if (partLength == 0) {
			*error = "invalid property name";
			return false;
		}

		uint32_t children = LoadTriePointer(area, current, kTrieNodeChildrenOffset);
		if (children == 0) {
			*error = "property \"" + name + "\" does not exist";
			return false;
		}

		uint32_t node = 0;
		if (!FindTrieNode(area, dataSize, children, remaining, partLength, false, &node,
				  error)) {
			return false;
		}
		if (!wantSubtree) {
			*result = node;
			return true;
		}
		current = node;
		remaining += partLength + 1;
		remainingLength -= partLength + 1;
	}
}

// Read a prop_info straight from the raw area, without going through bionic.
// Used by the compaction walk, which has to see properties bionic may have
// already forgotten about (stale records left behind by updates).
bool ReadPropInfoAt(const uint8_t *area, size_t dataSize, uint32_t propOffset, std::string *name,
		    std::string *value, std::string *error)
{
	if (!CheckedRange(propOffset, kPropInfoHeaderSize + 1, dataSize)) {
		*error = "corrupted property area (prop_info offset out of range)";
		return false;
	}

	const uint8_t *prop = area + kPropertyAreaHeaderSize + propOffset;
	const char *propName = reinterpret_cast<const char *>(prop + kPropInfoHeaderSize);
	size_t nameLimit = dataSize - propOffset - kPropInfoHeaderSize;
	size_t nameLength = strnlen(propName, nameLimit);
	if (nameLength == 0 || nameLength == nameLimit) {
		*error = "corrupted property area (unterminated property name)";
		return false;
	}

	uint32_t serial = LoadU32(prop);
	std::string result;
	if ((serial & kLongFlag) != 0) {
		uint32_t relative = LoadU32(prop + kPropInfoLongOffsetOffset);
		if (relative < kPropInfoHeaderSize + nameLength + 1 ||
		    propOffset > dataSize - relative) {
			*error = "corrupted property area (long value offset out of range)";
			return false;
		}
		size_t longOffset = static_cast<size_t>(propOffset) + relative;
		const char *longValue = reinterpret_cast<const char *>(
			area + kPropertyAreaHeaderSize + longOffset);
		size_t limit = dataSize - longOffset;
		size_t length = strnlen(longValue, limit);
		if (length == limit) {
			*error = "corrupted property area (unterminated long value)";
			return false;
		}
		result.assign(longValue, length);
	} else {
		size_t declared = serial >> 24;
		if (declared >= PROP_VALUE_MAX) {
			declared = PROP_VALUE_MAX - 1;
		}
		const char *inlineValue = reinterpret_cast<const char *>(prop + kPropInfoValueOffset);
		result.assign(inlineValue, strnlen(inlineValue, declared));
	}

	name->assign(propName, nameLength);
	*value = std::move(result);
	return true;
}

// property_info 是 bionic 的 context 索引：同样是一个 prop_area，trie 每个
// 节点对应一个属性名前缀，prop_info 的 value 是 "<context>:<type>"（type 为
// exact / prefix / default）。按 '.' 分段做最长前缀匹配，就能确定一个属性
// 该落在哪个 area 文件里。
//
// 这条路径比"调用 find 后看 maps 多了哪个映射"可靠：bionic 对已经加载过的
// context 不会再 mmap 一次，而一个进程里写第二个同 context 的新属性很常见。
bool ResolveContextPath(const std::string &name, std::string *path)
{
	std::ifstream file(std::string(kPropertyAreaDir) + "property_info", std::ios::binary);
	if (!file) {
		return false;
	}
	std::string buffer{std::istreambuf_iterator<char>(file),
			   std::istreambuf_iterator<char>()};
	if (buffer.size() <= kPropertyAreaHeaderSize + kTrieNodeHeaderSize) {
		return false;
	}

	const uint8_t *area = reinterpret_cast<const uint8_t *>(buffer.data());
	if (LoadU32(area + kPropertyAreaMagicOffset) != kPropertyAreaMagic ||
	    LoadU32(area + kPropertyAreaVersionOffset) != kPropertyAreaVersion) {
		return false;
	}
	size_t dataSize = buffer.size() - kPropertyAreaHeaderSize;

	uint32_t current = 0;
	uint32_t bestProp = LoadTriePointer(area, 0, kTrieNodePropOffset);
	const char *remaining = name.data();
	size_t remainingLength = name.size();

	for (;;) {
		uint32_t children = LoadTriePointer(area, current, kTrieNodeChildrenOffset);
		if (children == 0) {
			break;
		}
		size_t partLength = remainingLength;
		bool wantSubtree = false;
		for (size_t i = 0; i < remainingLength; ++i) {
			if (remaining[i] == '.') {
				partLength = i;
				wantSubtree = true;
				break;
			}
		}
		if (partLength == 0) {
			break;
		}

		uint32_t node = children;
		bool found = false;
		for (int guard = 0; node != 0 && guard < 10000; ++guard) {
			if (!CheckedRange(node, kTrieNodeHeaderSize, dataSize)) {
				return false;
			}
			const uint8_t *raw = area + kPropertyAreaHeaderSize + node;
			uint32_t nameLength = LoadU32(raw);
			if (!CheckedRange(node + kTrieNodeHeaderSize, nameLength, dataSize)) {
				return false;
			}
			const char *nodeName =
				reinterpret_cast<const char *>(raw + kTrieNodeHeaderSize);
			if (CompareNamePart(remaining, partLength, nodeName, nameLength) == 0) {
				found = true;
				break;
			}
			size_t field = CompareNamePart(remaining, partLength, nodeName, nameLength) < 0
					       ? kTrieNodeLeftOffset
					       : kTrieNodeRightOffset;
			node = LoadTriePointer(area, node, field);
		}
		if (!found) {
			break;
		}

		current = node;
		uint32_t prop = LoadTriePointer(area, current, kTrieNodePropOffset);
		if (prop != 0) {
			bestProp = prop;
		}
		if (!wantSubtree) {
			break;
		}
		remaining += partLength + 1;
		remainingLength -= partLength + 1;
	}

	if (bestProp == 0) {
		return false;
	}

	std::string matchedName;
	std::string value;
	std::string error;
	if (!ReadPropInfoAt(area, dataSize, bestProp, &matchedName, &value, &error)) {
		return false;
	}
	size_t lastColon = value.find_last_of(':');
	if (lastColon != std::string::npos) {
		std::string suffix = value.substr(lastColon + 1);
		if (suffix == "exact" || suffix == "prefix" || suffix == "default") {
			value.resize(lastColon);
		}
	}
	*path = std::string(kPropertyAreaDir) + value;
	return true;
}

// Depth-first walk over one trie level.  budget guards against crafted areas
// with cyclic or absurdly deep node graphs.
bool WalkTrie(const uint8_t *area, size_t dataSize, uint32_t nodeOffset,
	      std::vector<std::pair<std::string, std::string>> *entries, size_t *budget,
	      std::string *error)
{
	if (nodeOffset == 0) {
		return true;
	}
	if (*budget == 0) {
		*error = "corrupted property trie (cyclic or too deep)";
		return false;
	}
	--*budget;

	if (!CheckedRange(nodeOffset, kTrieNodeHeaderSize, dataSize)) {
		*error = "corrupted property trie (node offset out of range)";
		return false;
	}
	const uint8_t *node = area + kPropertyAreaHeaderSize + nodeOffset;
	uint32_t nameLength = LoadU32(node);
	if (!CheckedRange(nodeOffset + kTrieNodeHeaderSize, nameLength, dataSize)) {
		*error = "corrupted property trie (node name out of range)";
		return false;
	}

	uint32_t propOffset = LoadU32(node + kTrieNodePropOffset);
	uint32_t left = LoadTriePointer(area, nodeOffset, kTrieNodeLeftOffset);
	uint32_t right = LoadTriePointer(area, nodeOffset, kTrieNodeRightOffset);
	uint32_t children = LoadTriePointer(area, nodeOffset, kTrieNodeChildrenOffset);

	if (!WalkTrie(area, dataSize, left, entries, budget, error)) {
		return false;
	}
	if (propOffset != 0) {
		std::string name;
		std::string value;
		if (!ReadPropInfoAt(area, dataSize, propOffset, &name, &value, error)) {
			return false;
		}
		entries->emplace_back(std::move(name), std::move(value));
	}
	if (!WalkTrie(area, dataSize, right, entries, budget, error)) {
		return false;
	}
	return WalkTrie(area, dataSize, children, entries, budget, error);
}

// Drop trie nodes that no longer hold a property or children.  Returns whether
// the whole subtree rooted at nodeOffset became empty, so the parent can clear
// the pointer to it.
bool PruneSubtree(uint8_t *area, size_t dataSize, uint32_t nodeOffset, size_t *budget,
		  bool *empty)
{
	*empty = true;
	if (nodeOffset == 0) {
		return true;
	}
	if (*budget == 0 || !CheckedRange(nodeOffset, kTrieNodeHeaderSize, dataSize)) {
		// Bail out conservatively: keep the node so nothing is lost.
		*empty = false;
		return false;
	}
	--*budget;

	uint32_t prop = LoadU32(area + kPropertyAreaHeaderSize + nodeOffset + kTrieNodePropOffset);

	bool leftEmpty = true;
	bool rightEmpty = true;
	bool childrenEmpty = true;
	PruneSubtree(area, dataSize, LoadTriePointer(area, nodeOffset, kTrieNodeLeftOffset), budget,
		     &leftEmpty);
	PruneSubtree(area, dataSize, LoadTriePointer(area, nodeOffset, kTrieNodeRightOffset), budget,
		     &rightEmpty);
	PruneSubtree(area, dataSize, LoadTriePointer(area, nodeOffset, kTrieNodeChildrenOffset),
		     budget, &childrenEmpty);

	if (leftEmpty) {
		StoreTriePointer(area, nodeOffset, kTrieNodeLeftOffset, 0);
	}
	if (rightEmpty) {
		StoreTriePointer(area, nodeOffset, kTrieNodeRightOffset, 0);
	}
	if (childrenEmpty) {
		StoreTriePointer(area, nodeOffset, kTrieNodeChildrenOffset, 0);
	}

	*empty = prop == 0 && leftEmpty && rightEmpty && childrenEmpty;
	return true;
}

bool RemoveProperty(const std::string &name, bool prune, std::string *error)
{
	if (!IsValidPropertyName(name, error)) {
		return false;
	}

	const prop_info *info = __system_property_find(name.c_str());
	if (info == nullptr) {
		*error = "property \"" + name + "\" does not exist";
		return false;
	}

	std::vector<Mapping> maps;
	if (!ParseSelfMaps(maps)) {
		*error = "cannot read /proc/self/maps";
		return false;
	}
	const Mapping *mapping = FindMapping(maps, info);
	if (mapping == nullptr || !IsPropertyAreaFile(mapping->path) || mapping->offset != 0) {
		*error = "cannot locate the property area backing \"" + name + "\"";
		return false;
	}

	WritableMapping area;
	if (!area.Open(mapping->path, error) ||
	    !ValidatePropertyArea(area.Data(), area.Size(), error)) {
		return false;
	}
	size_t dataSize = area.Size() - kPropertyAreaHeaderSize;

	uint32_t node = 0;
	if (!FindPropertyNode(area.Data(), dataSize, name, &node, error)) {
		return false;
	}
	uint32_t propOffset = LoadTriePointer(area.Data(), node, kTrieNodePropOffset);
	if (propOffset == 0) {
		*error = "property \"" + name + "\" is not published";
		return false;
	}

	WritableMapping serialArea;
	if (!serialArea.Open(SerialAreaPath(), error) ||
	    !ValidatePropertyArea(serialArea.Data(), serialArea.Size(), error)) {
		return false;
	}
	uint32_t *areaSerial =
		reinterpret_cast<uint32_t *>(serialArea.Data() + kPropertyAreaSerialOffset);

	// Unpublish the node first: new lookups stop finding it immediately.
	// Then clear the prop_info serial, so a reader that already holds the old
	// pointer treats the record as never-published and retries the lookup
	// instead of reading a value that is about to be reclaimed.
	StorePublishedU32(area.Data() + kPropertyAreaHeaderSize + node + kTrieNodePropOffset, 0);
	__atomic_thread_fence(__ATOMIC_RELEASE);
	StoreU32(area.Data() + kPropertyAreaHeaderSize + propOffset, 0);
	BumpAreaSerial(areaSerial);

	if (prune) {
		size_t budget = dataSize / kTrieNodeHeaderSize + 1;
		bool empty = true;
		PruneSubtree(area.Data(), dataSize,
			     LoadTriePointer(area.Data(), 0, kTrieNodeChildrenOffset), &budget,
			     &empty);
	}

	if (__system_property_find(name.c_str()) != nullptr) {
		*error = "property \"" + name + "\" is still visible after deletion";
		return false;
	}
	return true;
}

// The SELinux context of a property is simply the name of the area file that
// holds it; bionic maps /dev/__properties__/<context>.
bool GetPropertyContext(const std::string &name, std::string *context)
{
	const prop_info *info = __system_property_find(name.c_str());
	if (info == nullptr) {
		return false;
	}

	std::vector<Mapping> maps;
	if (!ParseSelfMaps(maps)) {
		return false;
	}
	const Mapping *mapping = FindMapping(maps, info);
	if (mapping == nullptr || !IsPropertyAreaFile(mapping->path)) {
		return false;
	}

	*context = AreaBaseName(mapping->path);
	return true;
}

bool CompactOneArea(const std::string &areaPath, const std::string &serialPath,
		    std::string *error)
{
	WritableMapping area;
	if (!area.Open(areaPath, error) ||
	    !ValidatePropertyArea(area.Data(), area.Size(), error)) {
		return false;
	}
	size_t dataSize = area.Size() - kPropertyAreaHeaderSize;

	std::vector<std::pair<std::string, std::string>> entries;
	size_t budget = dataSize / kTrieNodeHeaderSize + 1;
	if (!WalkTrie(area.Data(), dataSize,
		      LoadTriePointer(area.Data(), 0, kTrieNodeChildrenOffset), &entries, &budget,
		      error)) {
		return false;
	}

	uint32_t used = LoadU32(area.Data() + kPropertyAreaBytesUsedOffset);
	bool hasBackup = HasDirtyBackupArea(area.Data(), dataSize);
	size_t initialUsed = kTrieNodeHeaderSize + (hasBackup ? PROP_VALUE_MAX : 0);

	// 每条属性至少要占一个 prop_info（含名字），长值还要额外的值空间。
	size_t minimum = initialUsed;
	for (const auto &entry : entries) {
		minimum += AlignUp4(kPropInfoHeaderSize + entry.first.size() + 1);
		if (entry.second.size() >= static_cast<size_t>(PROP_VALUE_MAX)) {
			minimum += AlignUp4(entry.second.size() + 1);
		}
	}
	// 已经是最紧凑的布局，就不冒重写的风险了。
	if (used <= minimum) {
		return true;
	}

	std::vector<uint8_t> backup(area.Data(), area.Data() + area.Size());
	std::vector<uint8_t> fresh(area.Size(), 0);
	std::memcpy(fresh.data(), area.Data(), kPropertyAreaHeaderSize);
	StoreU32(fresh.data() + kPropertyAreaBytesUsedOffset, static_cast<uint32_t>(initialUsed));
	InitTrieNode(fresh.data(), 0, "", 0);

	uint32_t scratchSerial = 0;
	for (const auto &entry : entries) {
		if (!InsertPropInfo(fresh.data(), fresh.size(), entry.first, entry.second,
				    &scratchSerial, error)) {
			return false;
		}
	}

	// 先在内存里自检一遍：新镜像必须能遍历出同一组属性。
	std::vector<std::pair<std::string, std::string>> rebuilt;
	size_t checkBudget = dataSize / kTrieNodeHeaderSize + 1;
	if (!WalkTrie(fresh.data(), dataSize,
		      LoadTriePointer(fresh.data(), 0, kTrieNodeChildrenOffset), &rebuilt,
		      &checkBudget, error)) {
		return false;
	}
	std::sort(entries.begin(), entries.end());
	std::sort(rebuilt.begin(), rebuilt.end());
	if (entries != rebuilt) {
		*error = "compacted layout failed verification";
		return false;
	}

	WritableMapping serialArea;
	if (!serialArea.Open(serialPath, error) ||
	    !ValidatePropertyArea(serialArea.Data(), serialArea.Size(), error)) {
		return false;
	}
	uint32_t *areaSerial =
		reinterpret_cast<uint32_t *>(serialArea.Data() + kPropertyAreaSerialOffset);

	// 原地覆盖。property area 被 init 长期 mmap，换文件（rename）会让两边
	// 看到不同的 inode，只能写回同一份映射。
	std::memcpy(area.Data(), fresh.data(), fresh.size());
	__atomic_thread_fence(__ATOMIC_RELEASE);
	BumpAreaSerial(areaSerial);

	// 写回后再验一次；失败就把旧内容原样写回去。
	std::vector<std::pair<std::string, std::string>> published;
	size_t afterBudget = dataSize / kTrieNodeHeaderSize + 1;
	if (!WalkTrie(area.Data(), dataSize,
		      LoadTriePointer(area.Data(), 0, kTrieNodeChildrenOffset), &published,
		      &afterBudget, error)) {
		published.clear();
	}
	std::sort(published.begin(), published.end());
	if (published != entries) {
		std::memcpy(area.Data(), backup.data(), backup.size());
		__atomic_thread_fence(__ATOMIC_RELEASE);
		BumpAreaSerial(areaSerial);
		*error = "compact verification failed, original layout restored";
		return false;
	}
	return true;
}

bool CompactDirectory(const std::string &filter, std::string *error)
{
	const std::string serialPath = SerialAreaPath();
	DIR *dir = opendir(kPropertyAreaDir);
	if (dir == nullptr) {
		*error = std::string("cannot open ") + kPropertyAreaDir + ": " +
			 std::strerror(errno);
		return false;
	}

	bool ok = true;
	bool matched = filter.empty();
	while (struct dirent *entry = readdir(dir)) {
		std::string name = entry->d_name;
		if (name == "." || name == ".." || name == kSerialAreaFileName ||
		    name == "property_info") {
			// property_info 是 context 索引，不是属性数据，不能重排。
			continue;
		}
		if (!filter.empty() && name != filter) {
			continue;
		}
		matched = true;
		if (entry->d_type != DT_REG && entry->d_type != DT_UNKNOWN) {
			continue;
		}

		std::string path = std::string(kPropertyAreaDir) + name;
		std::string detail;
		if (CompactOneArea(path, serialPath, &detail)) {
			continue;
		}
		// property_info 之类不是 property area 的文件，静默跳过。
		if (detail.find("is not a property area") != std::string::npos ||
		    detail.find("invalid property area header") != std::string::npos ||
		    detail.find("property area is too small") != std::string::npos) {
			continue;
		}
		fprintf(stderr, "resetprop: %s: %s\n", path.c_str(), detail.c_str());
		ok = false;
	}
	closedir(dir);

	if (!matched) {
		*error = "no property area for context \"" + filter + "\"";
		return false;
	}
	return ok;
}

} // namespace

// ---------------------------------------------------------------------------
// ncore 侧入口。
// ---------------------------------------------------------------------------

bool read(const std::string &name, std::string *value)
{
	return ReadPropertyValue(name, value);
}

bool write(const std::string &name, const std::string &value, bool forceDirect,
	   std::string *error)
{
	return SetProperty(name, value, forceDirect, error);
}

bool remove(const std::string &name, bool prune, std::string *error)
{
	return RemoveProperty(name, prune, error);
}

bool context_of(const std::string &name, std::string *context)
{
	return GetPropertyContext(name, context);
}

bool compact(const std::string &contextFilter, std::string *error)
{
	return CompactDirectory(contextFilter, error);
}

bool snapshot(std::map<std::string, std::string> *out, std::string *error)
{
	return SnapshotAll(out, error);
}

void list(bool withContext)
{
	ShowAllProperties(withContext);
}

} // namespace ncore::prop
