from __future__ import annotations

import json
import os as operating_system
import shutil
import subprocess
from pathlib import Path

import pytest


SERVER_DIRECTORY = Path(__file__).resolve().parent.parent


def prepare_installer(directory: Path) -> tuple[Path, Path, dict[str, str]]:
    source_directory = directory / "source"
    source_directory.mkdir()
    for source_path in SERVER_DIRECTORY.iterdir():
        if source_path.is_file() and (
            source_path.name == "install.sh" or source_path.name.startswith("tsess")
        ):
            shutil.copy2(source_path, source_directory / source_path.name)
    (source_directory / "zmx/src").mkdir(parents=True)
    binary_directory = source_directory / "zmx/zig-out/bin"
    binary_directory.mkdir(parents=True)
    binary_path = binary_directory / "zmx"
    binary_path.write_text(
        '#!/bin/bash\nprintf "%s\\n" "$*" >> "$INSTALL_CALLS"\n'
        'printf "candidate-version\\n"\n'
    )
    binary_path.chmod(0o755)
    destination_directory = directory / "installed helpers"
    destination_directory.mkdir()
    isolated_home = directory / "isolated-home"
    isolated_home.mkdir()
    environment = {
        **operating_system.environ,
        "HOME": str(isolated_home),
        "HOMEATTACH_BIN_DIR": str(destination_directory),
        "INSTALL_CALLS": str(directory / "install-calls.txt"),
    }
    return source_directory, destination_directory, environment


def run_installer(source_directory: Path, environment: dict[str, str]):
    return subprocess.run(
        ["/bin/bash", str(source_directory / "install.sh")],
        env=environment,
        capture_output=True,
        text=True,
        timeout=10,
    )


def test_install_replaces_running_executable_without_changing_original_inode(tmp_path):
    source_directory, destination_directory, environment = prepare_installer(tmp_path)
    installed_binary = destination_directory / "zmx"
    shutil.copy2("/usr/bin/sleep", installed_binary)
    original_inode = installed_binary.stat().st_ino
    original_contents = installed_binary.read_bytes()
    process = subprocess.Popen([str(installed_binary), "60"])
    try:
        result = run_installer(source_directory, environment)
        assert result.returncode == 0, result.stderr
        assert process.poll() is None
        assert installed_binary.stat().st_ino != original_inode
        assert Path(f"/proc/{process.pid}/exe").read_bytes() == original_contents
        assert installed_binary.read_bytes() == (
            source_directory / "zmx/zig-out/bin/zmx"
        ).read_bytes()
        for helper in ("tsess-upgrade", "tsess-qr-config", "tsess-new", "tsess-mux"):
            assert operating_system.access(destination_directory / helper, operating_system.X_OK)
        assert not list(destination_directory.glob(".homeattach-install.*"))
        assert not list(Path(environment["HOME"]).iterdir())
        assert Path(environment["INSTALL_CALLS"]).read_text().splitlines() == ["version"]
    finally:
        process.terminate()
        process.wait(timeout=5)


def test_install_replaces_destination_symlink_without_modifying_its_target(tmp_path):
    source_directory, destination_directory, environment = prepare_installer(tmp_path)
    original_binary = tmp_path / "previous-binary"
    original_binary.write_text("previous version")
    installed_binary = destination_directory / "zmx"
    installed_binary.symlink_to(original_binary)
    result = run_installer(source_directory, environment)
    assert result.returncode == 0, result.stderr
    assert not installed_binary.is_symlink()
    assert original_binary.read_text() == "previous version"


def test_failed_copy_preserves_installed_executable_and_removes_temporary_file(tmp_path):
    source_directory, destination_directory, environment = prepare_installer(tmp_path)
    installed_binary = destination_directory / "zmx"
    installed_binary.write_text("original executable")
    original_inode = installed_binary.stat().st_ino
    command_directory = tmp_path / "commands"
    command_directory.mkdir()
    failing_install = command_directory / "install"
    failing_install.write_text(
        '#!/bin/bash\nprintf "partial candidate" > "${@: -1}"\nexit 42\n'
    )
    failing_install.chmod(0o755)
    environment["PATH"] = f"{command_directory}:/usr/bin:/bin"
    result = run_installer(source_directory, environment)
    assert result.returncode == 42
    assert installed_binary.stat().st_ino == original_inode
    assert installed_binary.read_text() == "original executable"
    assert not list(destination_directory.glob(".homeattach-install.*"))


@pytest.fixture
def upgrade_helper(tmp_path):
    destination_directory = tmp_path / "installed helpers"
    destination_directory.mkdir()
    for filename in ("tsess-upgrade", "tsess-state"):
        shutil.copy2(SERVER_DIRECTORY / filename, destination_directory / filename)
    binary_path = destination_directory / "zmx"
    binary_path.write_text(
        '#!/usr/bin/python3\nimport json, os as operating_system, sys\n'
        'print(json.dumps(sys.argv))\n'
        'sys.exit(int(operating_system.environ.get("UPGRADE_RESULT", "0")))\n'
    )
    binary_path.chmod(0o755)
    return destination_directory


def test_upgrade_selects_sibling_executable_and_exact_named_session(upgrade_helper):
    result = subprocess.run(
        [str(upgrade_helper / "tsess-upgrade"), "work.session-123"],
        env={**operating_system.environ, "HOMEATTACH_ZMX": "/never-run-this", "ZMX_SESSION": "other"},
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert result.returncode == 0, result.stderr
    binary_path = str(upgrade_helper / "zmx")
    assert json.loads(result.stdout) == [
        binary_path, "upgrade", "work.session-123", binary_path,
    ]


@pytest.mark.parametrize("arguments", [[], ["first", "second"], ["../other"], ["--all"]])
def test_upgrade_rejects_missing_multiple_or_invalid_sessions(upgrade_helper, arguments):
    result = subprocess.run(
        [str(upgrade_helper / "tsess-upgrade"), *arguments],
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert result.returncode != 0
    assert result.stdout == ""


def test_upgrade_preserves_candidate_failure_status(upgrade_helper):
    result = subprocess.run(
        [str(upgrade_helper / "tsess-upgrade"), "session"],
        env={**operating_system.environ, "UPGRADE_RESULT": "23"},
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert result.returncode == 23


def test_upgrade_rejects_missing_sibling_binary(upgrade_helper):
    (upgrade_helper / "zmx").unlink()
    result = subprocess.run(
        [str(upgrade_helper / "tsess-upgrade"), "session"],
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert result.returncode != 0
    assert "missing installed executable" in result.stderr
