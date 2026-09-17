from __future__ import annotations

import argparse
import ast
import tarfile
from pathlib import Path


IMPORT_UPSTREAM = "from dataclasses import dataclass, replace"
IMPORT_PATCHED = "from dataclasses import dataclass, field, replace"
CONV_UPSTREAM = "    conv_cfg: MaxxVitConvCfg = MaxxVitConvCfg()"
CONV_PATCHED = "    conv_cfg: MaxxVitConvCfg = field(default_factory=MaxxVitConvCfg)"
TRANSFORMER_UPSTREAM = (
    "    transformer_cfg: MaxxVitTransformerCfg = MaxxVitTransformerCfg()"
)
TRANSFORMER_PATCHED = (
    "    transformer_cfg: MaxxVitTransformerCfg = "
    "field(default_factory=MaxxVitTransformerCfg)"
)


class TimmPatchError(ValueError):
    pass


def safe_extract(archive_path: Path, output_directory: Path) -> Path:
    output_directory.mkdir(parents=True, exist_ok=True)
    root = output_directory.resolve()
    with tarfile.open(archive_path, "r:*") as archive:
        for member in archive.getmembers():
            if member.issym() or member.islnk() or member.isdev():
                raise TimmPatchError("timm source archive contains an unsupported link or device")
            target = (root / member.name).resolve()
            if target != root and root not in target.parents:
                raise TimmPatchError("timm source archive contains an unsafe path")
        archive.extractall(root, filter="data")
    directories = [path for path in root.iterdir() if path.is_dir()]
    if len(directories) != 1:
        raise TimmPatchError("Expected one timm source directory")
    return directories[0]


def _line_count(source: str, token: str) -> int:
    return source.splitlines().count(token)


def _replace_exact_line(source: str, old: str, new: str) -> str:
    replaced = 0
    output: list[str] = []
    for line in source.splitlines(keepends=True):
        content = line.rstrip("\r\n")
        ending = line[len(content) :]
        if content == old:
            output.append(new + ending)
            replaced += 1
        else:
            output.append(line)
    if replaced != 1:
        raise TimmPatchError(
            f"Expected exactly one timm maxxvit line {old!r}, found {replaced}"
        )
    return "".join(output)


def patch_maxxvit(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    upstream_counts = (
        _line_count(source, IMPORT_UPSTREAM),
        _line_count(source, CONV_UPSTREAM),
        _line_count(source, TRANSFORMER_UPSTREAM),
    )
    patched_counts = (
        _line_count(source, IMPORT_PATCHED),
        _line_count(source, CONV_PATCHED),
        _line_count(source, TRANSFORMER_PATCHED),
    )

    if upstream_counts == (1, 1, 1) and patched_counts == (0, 0, 0):
        source = _replace_exact_line(source, IMPORT_UPSTREAM, IMPORT_PATCHED)
        source = _replace_exact_line(source, CONV_UPSTREAM, CONV_PATCHED)
        source = _replace_exact_line(source, TRANSFORMER_UPSTREAM, TRANSFORMER_PATCHED)
    elif upstream_counts == (0, 0, 0) and patched_counts == (1, 1, 1):
        pass
    else:
        raise TimmPatchError(
            "Unexpected timm 0.6.11 maxxvit compatibility state: "
            f"upstream={upstream_counts}, patched={patched_counts}"
        )

    audit_maxxvit_source(source)
    path.write_text(source, encoding="utf-8")


def audit_maxxvit_source(source: str) -> None:
    required_counts = {
        IMPORT_PATCHED: 1,
        CONV_PATCHED: 1,
        TRANSFORMER_PATCHED: 1,
    }
    unexpected_counts = {
        IMPORT_UPSTREAM: 0,
        CONV_UPSTREAM: 0,
        TRANSFORMER_UPSTREAM: 0,
    }
    for token, expected in {**required_counts, **unexpected_counts}.items():
        actual = _line_count(source, token)
        if actual != expected:
            raise TimmPatchError(
                f"Unexpected timm maxxvit token count for {token!r}: "
                f"expected {expected}, found {actual}"
            )
    ast.parse(source)


def _is_dataclass(decorator: ast.expr) -> bool:
    target = decorator.func if isinstance(decorator, ast.Call) else decorator
    return isinstance(target, ast.Name) and target.id == "dataclass"


def _is_field_call(value: ast.expr) -> bool:
    return (
        isinstance(value, ast.Call)
        and isinstance(value.func, ast.Name)
        and value.func.id == "field"
    )


def audit_source_tree(source_root: Path) -> None:
    mutable_defaults: list[str] = []
    for path in source_root.rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if not isinstance(node, ast.ClassDef) or not any(
                _is_dataclass(decorator) for decorator in node.decorator_list
            ):
                continue
            for statement in node.body:
                if not isinstance(statement, ast.AnnAssign) or statement.value is None:
                    continue
                value = statement.value
                if isinstance(value, (ast.Call, ast.List, ast.Dict, ast.Set)) and not _is_field_call(value):
                    field_name = ast.unparse(statement.target)
                    mutable_defaults.append(
                        f"{path.relative_to(source_root)}:{statement.lineno}:"
                        f"{node.name}.{field_name}={ast.unparse(value)}"
                    )
    if mutable_defaults:
        raise TimmPatchError(
            "Python 3.11-incompatible dataclass defaults remain: "
            + " | ".join(mutable_defaults)
        )

    maxxvit_path = source_root / "timm" / "models" / "maxxvit.py"
    audit_maxxvit_source(maxxvit_path.read_text(encoding="utf-8"))
    print(
        "timm source audit passed: timmVersion=0.6.11, "
        "python311CompatibilityPatch=true, mutableDataclassDefaults=0, "
        "defaultFactories=2"
    )


def patch_timm_source(source_root: Path) -> None:
    patch_maxxvit(source_root / "timm" / "models" / "maxxvit.py")
    audit_source_tree(source_root)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source_root = safe_extract(args.archive, args.output)
    patch_timm_source(source_root)
    print(source_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
