#!/usr/bin/env python3
import ctypes
import errno
import fcntl
import multiprocessing
import os
import stat
import tempfile

RENAMEAT2 = 316
RENAME_NOREPLACE = 1
LOCK_NAME = ".pagenest-publish.lock"
libc = ctypes.CDLL(None, use_errno=True)


def full_regular_state(value):
    if not stat.S_ISREG(value.st_mode):
        return None
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def rename_no_replace(root_fd, source, target):
    result = libc.syscall(
        RENAMEAT2,
        root_fd,
        ctypes.c_char_p(source.encode()),
        root_fd,
        ctypes.c_char_p(target.encode()),
        RENAME_NOREPLACE,
    )
    if result != 0:
        number = ctypes.get_errno()
        raise OSError(number, os.strerror(number))


def open_root(path):
    return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW)


def publish(path, part_fd, part_name, target_name):
    root_fd = open_root(path)
    lock_fd = os.open(
        LOCK_NAME,
        os.O_RDWR | os.O_CREAT | os.O_CLOEXEC | os.O_NOFOLLOW,
        0o600,
        dir_fd=root_fd,
    )
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        descriptor_state = full_regular_state(os.fstat(part_fd))
        entry_state = full_regular_state(
            os.stat(part_name, dir_fd=root_fd, follow_symlinks=False)
        )
        if descriptor_state is None or descriptor_state != entry_state:
            return "stale"
        try:
            rename_no_replace(root_fd, part_name, target_name)
        except OSError as failure:
            if failure.errno == errno.EEXIST:
                return "exists"
            raise
        final_state = full_regular_state(
            os.stat(target_name, dir_fd=root_fd, follow_symlinks=False)
        )
        if final_state != descriptor_state:
            raise RuntimeError("published final state differs from owned part")
        os.fsync(root_fd)
        return "published"
    finally:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)
        os.close(root_fd)


def process_publish(path, part_name, target_name, result_queue):
    root_fd = open_root(path)
    part_fd = os.open(part_name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=root_fd)
    os.close(root_fd)
    try:
        result_queue.put(publish(path, part_fd, part_name, target_name))
    finally:
        os.close(part_fd)


def crash_publish(path, part_name, target_name, after_rename):
    root_fd = open_root(path)
    lock_fd = os.open(LOCK_NAME, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600, dir_fd=root_fd)
    part_fd = os.open(part_name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=root_fd)
    fcntl.flock(lock_fd, fcntl.LOCK_EX)
    descriptor_state = full_regular_state(os.fstat(part_fd))
    entry_state = full_regular_state(os.stat(part_name, dir_fd=root_fd, follow_symlinks=False))
    if descriptor_state != entry_state:
        os._exit(20)
    if after_rename:
        rename_no_replace(root_fd, part_name, target_name)
        os.fsync(root_fd)
    os._exit(0)


def write_synced(root, name, content):
    path = os.path.join(root, name)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    os.write(descriptor, content)
    os.fsync(descriptor)
    os.close(descriptor)


def main():
    with tempfile.TemporaryDirectory(prefix="pagenest-publish-") as root:
        content = b"complete book bytes"
        write_synced(root, "one.part", content)
        write_synced(root, "two.part", content)
        queue = multiprocessing.Queue()
        processes = [
            multiprocessing.Process(
                target=process_publish,
                args=(root, name, "book.epub", queue),
            )
            for name in ("one.part", "two.part")
        ]
        for process in processes:
            process.start()
        for process in processes:
            process.join(10)
            if process.exitcode != 0:
                raise RuntimeError(f"publisher exit={process.exitcode}")
        results = sorted(queue.get(timeout=1) for _ in processes)
        if results != ["exists", "published"]:
            raise RuntimeError(f"unexpected concurrent results: {results}")
        if open(os.path.join(root, "book.epub"), "rb").read() != content:
            raise RuntimeError("concurrent publication exposed incomplete content")
        print("flock_renameat2_two_process=PASS")

        write_synced(root, "mutated.part", b"owned")
        root_fd = open_root(root)
        part_fd = os.open("mutated.part", os.O_RDONLY | os.O_NOFOLLOW, dir_fd=root_fd)
        os.rename("mutated.part", "held.part", src_dir_fd=root_fd, dst_dir_fd=root_fd)
        write_synced(root, "mutated.part", b"attacker")
        os.close(root_fd)
        result = publish(root, part_fd, "mutated.part", "mutated.epub")
        os.close(part_fd)
        if result != "stale" or os.path.exists(os.path.join(root, "mutated.epub")):
            raise RuntimeError("part replacement reached the final name")
        print("part_full_state_mutation=PASS")

        write_synced(root, "before-crash.part", content)
        before = multiprocessing.Process(
            target=crash_publish,
            args=(root, "before-crash.part", "before-crash.epub", False),
        )
        before.start()
        before.join(10)
        if before.exitcode != 0 or os.path.exists(os.path.join(root, "before-crash.epub")):
            raise RuntimeError("pre-rename crash exposed a final")
        write_synced(root, "after-crash.part", content)
        after = multiprocessing.Process(
            target=crash_publish,
            args=(root, "after-crash.part", "after-crash.epub", True),
        )
        after.start()
        after.join(10)
        if after.exitcode != 0 or open(os.path.join(root, "after-crash.epub"), "rb").read() != content:
            raise RuntimeError("post-rename crash lost or corrupted final")
        print("rename_crash_boundaries=PASS")


if __name__ == "__main__":
    main()
