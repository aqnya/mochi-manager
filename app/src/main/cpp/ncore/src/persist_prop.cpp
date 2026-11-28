// SPDX-License-Identifier: GPL-3.0-or-later
// 持久化属性存储的读写，格式见 include/persist_prop.h。
//
// protobuf 部分只用到 wire format 的两个基本类型（varint 与
// length-delimited string），手写解码比拖进一份 protobuf-lite 划算得多；
// 未知字段按 wire type 跳过，这样即便 AOSP 以后加字段也能继续读。
#include "persist_prop.h"

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

namespace ncore::prop::persist {

namespace {

constexpr char kPersistDir[] = "/data/property";
constexpr char kPersistFile[] = "/data/property/persistent_properties";

using Record = std::pair<std::string, std::string>;

bool ReadWholeFile(const std::string &path, std::string *out)
{
	std::ifstream file(path, std::ios::binary);
	if (!file) {
		return false;
	}
	out->assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
	return true;
}

// ---------------------------------------------------------------------------
// protobuf wire format.
// ---------------------------------------------------------------------------

bool ReadVarint(const std::string &buffer, size_t *offset, uint64_t *value)
{
	uint64_t result = 0;
	int shift = 0;
	while (*offset < buffer.size() && shift < 64) {
		uint8_t byte = static_cast<uint8_t>(buffer[(*offset)++]);
		result |= static_cast<uint64_t>(byte & 0x7f) << shift;
		if ((byte & 0x80) == 0) {
			*value = result;
			return true;
		}
		shift += 7;
	}
	return false;
}

bool ReadLengthDelimited(const std::string &buffer, size_t *offset, std::string *out)
{
	uint64_t length = 0;
	if (!ReadVarint(buffer, offset, &length)) {
		return false;
	}
	if (length > buffer.size() - *offset) {
		return false;
	}
	out->assign(buffer, *offset, static_cast<size_t>(length));
	*offset += static_cast<size_t>(length);
	return true;
}

// 跳过当前字段的值，offset 指向字段的 key 之后。
bool SkipField(const std::string &buffer, size_t *offset, uint32_t wire_type)
{
	switch (wire_type) {
	case 0: {
		uint64_t ignored = 0;
		return ReadVarint(buffer, offset, &ignored);
	}
	case 1: {
		if (buffer.size() - *offset < 8) {
			return false;
		}
		*offset += 8;
		return true;
	}
	case 2: {
		std::string ignored;
		return ReadLengthDelimited(buffer, offset, &ignored);
	}
	case 5: {
		if (buffer.size() - *offset < 4) {
			return false;
		}
		*offset += 4;
		return true;
	}
	default:
		return false;
	}
}

bool DecodeRecord(const std::string &payload, Record *record, bool *has_name, bool *has_value)
{
	size_t offset = 0;
	while (offset < payload.size()) {
		uint64_t key = 0;
		if (!ReadVarint(payload, &offset, &key)) {
			return false;
		}
		uint32_t field = static_cast<uint32_t>(key >> 3);
		uint32_t wire_type = static_cast<uint32_t>(key & 7);
		if (wire_type == 2 && (field == 1 || field == 2)) {
			std::string value;
			if (!ReadLengthDelimited(payload, &offset, &value)) {
				return false;
			}
			if (field == 1) {
				record->first = std::move(value);
				*has_name = true;
			} else {
				record->second = std::move(value);
				*has_value = true;
			}
			continue;
		}
		if (!SkipField(payload, &offset, wire_type)) {
			return false;
		}
	}
	return true;
}

bool DecodeRecords(const std::string &buffer, std::vector<Record> *records)
{
	size_t offset = 0;
	while (offset < buffer.size()) {
		uint64_t key = 0;
		if (!ReadVarint(buffer, &offset, &key)) {
			return false;
		}
		uint32_t field = static_cast<uint32_t>(key >> 3);
		uint32_t wire_type = static_cast<uint32_t>(key & 7);
		if (field == 1 && wire_type == 2) {
			std::string payload;
			if (!ReadLengthDelimited(buffer, &offset, &payload)) {
				return false;
			}
			Record record;
			bool has_name = false;
			bool has_value = false;
			if (!DecodeRecord(payload, &record, &has_name, &has_value)) {
				return false;
			}
			// 缺 name/value 的记录 init 也会忽略，这里保持一致。
			if (has_name && has_value) {
				records->push_back(std::move(record));
			}
			continue;
		}
		if (!SkipField(buffer, &offset, wire_type)) {
			return false;
		}
	}
	return true;
}

void AppendVarint(std::string *out, uint64_t value)
{
	while (value >= 0x80) {
		out->push_back(static_cast<char>((value & 0x7f) | 0x80));
		value >>= 7;
	}
	out->push_back(static_cast<char>(value));
}

void AppendLengthDelimited(std::string *out, uint32_t field, const std::string &value)
{
	AppendVarint(out, (static_cast<uint64_t>(field) << 3) | 2);
	AppendVarint(out, value.size());
	out->append(value);
}

std::string EncodeRecords(const std::vector<Record> &records)
{
	std::string out;
	for (const auto &record : records) {
		std::string payload;
		AppendLengthDelimited(&payload, 1, record.first);
		AppendLengthDelimited(&payload, 2, record.second);
		AppendLengthDelimited(&out, 1, payload);
	}
	return out;
}

// ---------------------------------------------------------------------------
// Storage access.
// ---------------------------------------------------------------------------

bool UsingProto()
{
	return access(kPersistFile, F_OK) == 0;
}

bool LoadRecords(std::vector<Record> *records, std::string *error)
{
	std::string buffer;
	if (!ReadWholeFile(kPersistFile, &buffer)) {
		*error = std::string("cannot read ") + kPersistFile + ": " + std::strerror(errno);
		return false;
	}
	if (!DecodeRecords(buffer, records)) {
		*error = std::string("cannot decode ") + kPersistFile;
		return false;
	}
	// init 保持列表有序（它自己做二分查找），写回时也要有序。
	std::sort(records->begin(), records->end(),
		  [](const Record &one, const Record &two) { return one.first < two.first; });
	return true;
}

bool WriteAll(int fd, const std::string &payload)
{
	size_t written = 0;
	while (written < payload.size()) {
		ssize_t count = ::write(fd, payload.data() + written, payload.size() - written);
		if (count < 0) {
			if (errno == EINTR) {
				continue;
			}
			return false;
		}
		written += static_cast<size_t>(count);
	}
	return true;
}

bool SaveRecords(const std::vector<Record> &records, std::string *error)
{
	const std::string payload = EncodeRecords(records);

	std::string pattern = std::string(kPersistFile) + ".XXXXXX";
	std::vector<char> path(pattern.begin(), pattern.end());
	path.push_back('\0');
	int fd = mkstemp(path.data());
	if (fd < 0) {
		*error = std::string("cannot create temporary file in ") + kPersistDir + ": " +
			 std::strerror(errno);
		return false;
	}

	bool ok = WriteAll(fd, payload);

	// 保留原文件的权限与属主；新文件默认 0600 root:root。
	struct stat existing {};
	if (stat(kPersistFile, &existing) == 0) {
		if (fchmod(fd, existing.st_mode & 07777) != 0 ||
		    fchown(fd, existing.st_uid, existing.st_gid) != 0) {
			// 权限没能继承就算了，rename 之后 init 仍按 0600 root 读得动。
		}
	} else {
		if (fchmod(fd, 0600) != 0) {
			// 同上。
		}
	}
	if (fsync(fd) != 0) {
		ok = false;
	}
	close(fd);

	if (!ok) {
		unlink(path.data());
		*error = std::string("cannot write ") + kPersistFile + ": " + std::strerror(errno);
		return false;
	}
	if (rename(path.data(), kPersistFile) != 0) {
		*error = std::string("cannot replace ") + kPersistFile + ": " + std::strerror(errno);
		unlink(path.data());
		return false;
	}
	return true;
}

// 老布局（Android 7 及更早）：一个属性一个文件。
std::string SinglePath(const std::string &name)
{
	return std::string(kPersistDir) + "/" + name;
}

bool IsSafeName(const std::string &name)
{
	return !name.empty() && name.find('/') == std::string::npos && name != "." && name != "..";
}

bool WriteSingle(const std::string &name, const std::string &value, std::string *error)
{
	if (mkdir(kPersistDir, 0700) != 0 && errno != EEXIST) {
		*error = std::string("cannot create ") + kPersistDir + ": " + std::strerror(errno);
		return false;
	}
	const std::string path = SinglePath(name);
	const std::string temp = path + ".tmp";

	int fd = open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
	if (fd < 0) {
		*error = "cannot open " + temp + ": " + std::strerror(errno);
		return false;
	}
	bool ok = WriteAll(fd, value);
	if (fchmod(fd, 0600) != 0) {
		ok = false;
	}
	close(fd);
	if (!ok || rename(temp.c_str(), path.c_str()) != 0) {
		*error = "cannot write " + path + ": " + std::strerror(errno);
		unlink(temp.c_str());
		return false;
	}
	return true;
}

}  // namespace

bool read(const std::string &name, std::string *value)
{
	if (UsingProto()) {
		std::vector<Record> records;
		std::string error;
		if (!LoadRecords(&records, &error)) {
			return false;
		}
		auto it = std::lower_bound(records.begin(), records.end(), name,
					   [](const Record &record, const std::string &key) {
						   return record.first < key;
					   });
		if (it == records.end() || it->first != name) {
			return false;
		}
		*value = it->second;
		return true;
	}
	return ReadWholeFile(SinglePath(name), value);
}

bool write(const std::string &name, const std::string &value, std::string *error)
{
	if (!IsSafeName(name)) {
		*error = "invalid persistent property name \"" + name + "\"";
		return false;
	}
	if (!UsingProto()) {
		return WriteSingle(name, value, error);
	}

	std::vector<Record> records;
	if (!LoadRecords(&records, error)) {
		return false;
	}
	auto it = std::lower_bound(records.begin(), records.end(), name,
				   [](const Record &record, const std::string &key) {
					   return record.first < key;
				   });
	if (it != records.end() && it->first == name) {
		it->second = value;
	} else {
		records.insert(it, Record(name, value));
	}
	return SaveRecords(records, error);
}

bool remove(const std::string &name, std::string *error)
{
	if (!IsSafeName(name)) {
		*error = "invalid persistent property name \"" + name + "\"";
		return false;
	}
	if (!UsingProto()) {
		if (unlink(SinglePath(name).c_str()) != 0 && errno != ENOENT) {
			*error = "cannot remove " + SinglePath(name) + ": " + std::strerror(errno);
			return false;
		}
		return true;
	}

	std::vector<Record> records;
	if (!LoadRecords(&records, error)) {
		return false;
	}
	auto it = std::lower_bound(records.begin(), records.end(), name,
				   [](const Record &record, const std::string &key) {
					   return record.first < key;
				   });
	if (it == records.end() || it->first != name) {
		return true;
	}
	records.erase(it);
	return SaveRecords(records, error);
}

bool list(std::map<std::string, std::string> *out, std::string *error)
{
	if (!UsingProto()) {
		std::string path = std::string(kPersistDir);
		DIR *dir = opendir(path.c_str());
		if (dir == nullptr) {
			// 老布局下目录可能根本不存在，等同于没有持久化属性。
			if (errno == ENOENT) {
				return true;
			}
			*error = "cannot open " + path + ": " + std::strerror(errno);
			return false;
		}
		while (struct dirent *entry = readdir(dir)) {
			std::string name = entry->d_name;
			if (name == "." || name == ".." || name.find(".tmp") != std::string::npos) {
				continue;
			}
			std::string value;
			if (ReadWholeFile(SinglePath(name), &value)) {
				out->emplace(std::move(name), std::move(value));
			}
		}
		closedir(dir);
		return true;
	}

	std::vector<Record> records;
	if (!LoadRecords(&records, error)) {
		return false;
	}
	for (auto &record : records) {
		out->emplace(std::move(record.first), std::move(record.second));
	}
	return true;
}

}  // namespace ncore::prop::persist
