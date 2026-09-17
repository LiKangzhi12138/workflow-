from __future__ import annotations

import argparse
import re
import tarfile
from pathlib import Path


CPP14_FLAG = "-std=c++14"
CPP17_FLAG = "-std=c++17"
EXPECTED_UPSTREAM_CPP14_FLAG_COUNT = 5
EXPECTED_UPSTREAM_CPP17_FLAG_COUNT = 1
EXPECTED_PATCHED_CPP17_FLAG_COUNT = (
    EXPECTED_UPSTREAM_CPP14_FLAG_COUNT + EXPECTED_UPSTREAM_CPP17_FLAG_COUNT
)
SCATTER_UPSTREAM = "_get_stream(device)"
SCATTER_PATCHED = "_get_stream(torch.device('cuda', device))"
EFFECTIVE_CPP14_PATTERN = re.compile(r"(?:-std=(?:c|gnu)\+\+14|/std:c\+\+14)")
TEXT_SUFFIXES = {
    ".c",
    ".cc",
    ".cpp",
    ".cu",
    ".cuh",
    ".h",
    ".hpp",
    ".md",
    ".py",
    ".rst",
    ".txt",
}


class MmcvPatchError(ValueError):
    pass


def safe_extract(archive_path: Path, output_directory: Path) -> Path:
    output_directory.mkdir(parents=True, exist_ok=True)
    root = output_directory.resolve()
    with tarfile.open(archive_path, "r:*") as archive:
        for member in archive.getmembers():
            if member.issym() or member.islnk() or member.isdev():
                raise MmcvPatchError("MMCV source archive contains an unsupported link or device")
            target = (root / member.name).resolve()
            if target != root and root not in target.parents:
                raise MmcvPatchError("MMCV source archive contains an unsafe path")
        archive.extractall(root, filter="data")
    directories = [path for path in root.iterdir() if path.is_dir()]
    if len(directories) != 1:
        raise MmcvPatchError("Expected one MMCV source directory")
    return directories[0]


def patch_cpp_standard(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    cpp14_count = source.count(CPP14_FLAG)
    cpp17_count = source.count(CPP17_FLAG)

    if cpp14_count == EXPECTED_UPSTREAM_CPP14_FLAG_COUNT:
        if cpp17_count != EXPECTED_UPSTREAM_CPP17_FLAG_COUNT:
            raise MmcvPatchError(
                "Unexpected MMCV 1.6.2 C++17 baseline before patch: "
                f"expected {EXPECTED_UPSTREAM_CPP17_FLAG_COUNT}, found {cpp17_count}"
            )
        source = source.replace(CPP14_FLAG, CPP17_FLAG)
    elif cpp14_count == 0 and cpp17_count == EXPECTED_PATCHED_CPP17_FLAG_COUNT:
        # The exact audited post-patch state makes repeat execution idempotent.
        pass
    else:
        raise MmcvPatchError(
            "Unexpected MMCV 1.6.2 C++ standard flags: "
            f"c++14={cpp14_count}, c++17={cpp17_count}"
        )

    audit_setup_compile_flags(source)
    path.write_text(source, encoding="utf-8")


def audit_setup_compile_flags(source: str) -> None:
    effective_cpp14 = EFFECTIVE_CPP14_PATTERN.findall(source)
    if effective_cpp14:
        raise MmcvPatchError(
            "Effective C++14 extension flags remain after patch: "
            + ", ".join(effective_cpp14)
        )
    if source.count(CPP17_FLAG) != EXPECTED_PATCHED_CPP17_FLAG_COUNT:
        raise MmcvPatchError(
            "Unexpected MMCV C++17 flag count after patch: "
            f"expected {EXPECTED_PATCHED_CPP17_FLAG_COUNT}, "
            f"found {source.count(CPP17_FLAG)}"
        )

    required_compile_paths = (
        "'nvcc': [cuda_args, '-std=c++17'] if cuda_args else ['-std=c++17']",
        "'cxx': ['-std=c++17']",
        "extra_compile_args['cxx'] = ['-std=c++17']",
        "extra_compile_args['nvcc'] += ['-std=c++17']",
    )
    missing = [snippet for snippet in required_compile_paths if snippet not in source]
    if missing:
        raise MmcvPatchError(
            "Required MMCV CPU/CUDA C++17 compile path is missing: " + repr(missing)
        )


def patch_scatter_compatibility(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    upstream_count = source.count(SCATTER_UPSTREAM)
    patched_count = source.count(SCATTER_PATCHED)
    if upstream_count == 1 and patched_count == 0:
        source = source.replace(SCATTER_UPSTREAM, SCATTER_PATCHED)
    elif upstream_count == 0 and patched_count == 1:
        pass
    else:
        raise MmcvPatchError(
            "Unexpected MMCV 1.6.2 scatter stream call state: "
            f"upstream={upstream_count}, patched={patched_count}"
        )
    if source.count(SCATTER_UPSTREAM) != 0 or source.count(SCATTER_PATCHED) != 1:
        raise MmcvPatchError("PyTorch 2.7 scatter stream compatibility patch did not apply exactly once")
    path.write_text(source, encoding="utf-8")


def audit_source_tree(source_root: Path) -> None:
    violations: list[str] = []
    cpp17_locations: list[str] = []
    for path in source_root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            source = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for line_number, line in enumerate(source.splitlines(), start=1):
            if EFFECTIVE_CPP14_PATTERN.search(line):
                violations.append(f"{path.relative_to(source_root)}:{line_number}:{line.strip()}")
            cpp17_locations.extend(
                f"{path.relative_to(source_root)}:{line_number}"
                for _ in range(line.count(CPP17_FLAG))
            )
    if violations:
        raise MmcvPatchError(
            "Effective C++14 flags remain in the patched MMCV source tree: "
            + " | ".join(violations)
        )
    if len(cpp17_locations) != EXPECTED_PATCHED_CPP17_FLAG_COUNT:
        raise MmcvPatchError(
            "Patched MMCV source tree does not contain the expected C++17 flags: "
            f"expected {EXPECTED_PATCHED_CPP17_FLAG_COUNT}, found {len(cpp17_locations)}"
        )
    print(
        "MMCV source audit passed: "
        f"effectiveCpp14Flags=0, cpp17Flags={len(cpp17_locations)}, "
        "cpuExtensionCxx17=true, cudaExtensionCxx17=true, scatterTorch27=true"
    )


def patch_mmcv_source(source_root: Path) -> None:
    patch_cpp_standard(source_root / "setup.py")
    patch_scatter_compatibility(source_root / "mmcv" / "parallel" / "_functions.py")
    audit_source_tree(source_root)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source_root = safe_extract(args.archive, args.output)
    patch_mmcv_source(source_root)
    print(source_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
