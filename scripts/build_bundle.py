#!/usr/bin/env python3
"""Build/test a cumulative ReVanced bundle; optionally create a signed APK."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

from scripts.bootstrap_toolchain import install
from scripts.release_artifacts import git, package_release, verify_bundle, version, write_zip

ROOT = Path(__file__).resolve().parents[1]

def discover_java_tests(directory):
    """Return launchable class names, including packages declared in test sources."""
    tests = []
    for test in sorted(Path(directory).rglob("*Test.java")):
        source = test.read_text()
        # Comments must not supply a fake main method or package declaration.
        source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.DOTALL)
        if not re.search(r"public\s+static\s+void\s+main\s*\(", source):
            continue
        package = re.search(r"(?m)^\s*package\s+([\w$]+(?:\s*\.\s*[\w$]+)*)\s*;", source)
        prefix = re.sub(r"\s+", "", package[1]) + "." if package else ""
        tests.append(prefix + test.stem)
    return tests


def run_java_tests(directory, java, classpath):
    tests = discover_java_tests(directory)
    if not tests:
        raise SystemExit("No Java test suites were discovered")
    for name in tests:
        print("Running Java suite: " + name, flush=True)
        subprocess.run([str(java), "-XX:-UsePerfData", "-cp", str(classpath), name],
                       cwd=ROOT, check=True)
    return tests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools", type=Path, default=ROOT / "toolchain")
    parser.add_argument("--bootstrap", action="store_true", help="Install/verify the pinned toolchain first")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--keystore", type=Path)
    args = parser.parse_args()
    if args.apk and not args.keystore:
        parser.error("--apk requires your own --keystore")
    tools = args.tools.resolve()
    if args.bootstrap:
        install(tools, ROOT / "toolchain.lock.json")
    required = ["revanced-cli.jar", "r8.jar", "android.jar", "volley.jar", "json.jar", "kotlin/kotlinc/lib/kotlin-compiler.jar"]
    if any(not (tools / name).is_file() for name in required):
        parser.error("Build tools missing; rerun with --bootstrap")
    current = version()
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH") or git("show", "-s", "--format=%ct", "HEAD"))
    build, output = ROOT / "build", ROOT / "output"
    for name in ["stubs", "extension", "tests", "patches", "dex-extension", "dex-patches"]:
        target = build / name
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
    output.mkdir(exist_ok=True)
    java_path = shutil.which("java")
    if not java_path:
        parser.error("Install Java 17 or later")
    java = str(Path(java_path).resolve())
    java_home = str(Path(java).parent.parent)

    def run(*command):
        subprocess.run([str(value) for value in command], cwd=ROOT, check=True)

    def cp(*paths):
        return os.pathsep.join(map(str, paths))

    def jar(directory, target):
        write_zip(target, {str(path.relative_to(directory)): path.read_bytes()
                          for path in directory.rglob("*") if path.is_file()}, epoch)

    javac = [java, "-XX:-UsePerfData", "-m", "jdk.compiler/com.sun.tools.javac.Main"]
    # Compile-only signatures (ReVanced Logger, Sync's Award model); never packaged.
    run(*javac, "--release", "8", "-d", build / "stubs",
        *sorted((ROOT / "src/stubs").rglob("*.java")))
    extension_cp = cp(build / "stubs", tools / "android.jar", tools / "volley.jar", tools / "json.jar")
    run(*javac, "--release", "8", "-cp", extension_cp, "-d", build / "extension",
        *sorted((ROOT / "src/extension").rglob("*.java")))
    test_cp = cp(build / "stubs", build / "extension", tools / "volley.jar", tools / "json.jar", tools / "android.jar")
    run(*javac, "-cp", test_cp, "-d", build / "tests", *sorted((ROOT / "tests").rglob("*.java")))
    tests = run_java_tests(ROOT / "tests", java, cp(build / "tests", test_cp))
    jar(build / "stubs", build / "stubs.jar")
    jar(build / "extension", build / "extension.jar")
    run(java, "-XX:-UsePerfData", "-cp", tools / "r8.jar", "com.android.tools.r8.D8",
        "--release", "--min-api", "23", "--lib", java_home,
        "--classpath", tools / "android.jar", "--classpath", tools / "volley.jar",
        "--classpath", tools / "json.jar", "--classpath", build / "stubs.jar",
        "--output", build / "dex-extension", build / "extension.jar")
    run(java, "-XX:-UsePerfData", "-cp", str(tools / "kotlin/kotlinc/lib") + "/*",
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-Xskip-prerelease-check",
        "-Xallow-unstable-dependencies", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-Xlambdas=class", "-Xstring-concat=inline", "-classpath", tools / "revanced-cli.jar",
        "-d", build / "patches", ROOT / "src/patches/SyncCompatPatches.kt")
    jar(build / "patches", build / "patches.jar")
    run(java, "-XX:-UsePerfData", "-cp", tools / "r8.jar", "com.android.tools.r8.D8",
        "--release", "--min-api", "26", "--lib", java_home,
        "--classpath", tools / "revanced-cli.jar", "--output", build / "dex-patches", build / "patches.jar")
    manifest = ("Manifest-Version: 1.0\r\nName: Sync Gallery and Batch Comments Compatibility\r\n"
                "Description: Cumulative compatibility patches for Sync build 23033\r\n"
                f"Version: {current}\r\nTimestamp: {epoch * 1000}\r\n"
                f"Source-Commit: {git('rev-parse', 'HEAD')}\r\n"
                "Author: ReVanced contributors and local compatibility changes\r\nLicense: GPL-3.0-only\r\n\r\n")
    entries = {str(path.relative_to(build / "patches")): path.read_bytes()
               for path in (build / "patches").rglob("*") if path.is_file()}
    entries.update({"META-INF/MANIFEST.MF": manifest.encode(),
                    "classes.dex": (build / "dex-patches/classes.dex").read_bytes(),
                    "extensions/synccompat.rve": (build / "dex-extension/classes.dex").read_bytes()})
    bundle = output / "patches.rvp"
    write_zip(bundle, entries, epoch)
    verify_bundle(bundle, current)
    run(java, "-jar", tools / "revanced-cli.jar", "list-patches", "-p", bundle, "-b", "--packages", "--versions")
    package_release(ROOT, output, bundle, epoch, tests)
    shutil.copyfile(bundle, output / f"sync-gallery-comments-compat-v{current}.rvp")
    print(f"Built and verified {bundle}", flush=True)
    if args.apk:
        if not args.keystore.is_file():
            raise SystemExit("Signing key is missing; refusing to create a new identity")
        command = [java, "-XX:-UsePerfData", "-jar", tools / "revanced-cli.jar", "patch", "-p", bundle,
                   "-b", "-o", output / f"Sync-v23.06.30-compat-v{current}.apk",
                   "-t", build / "patch-temp", "--keystore", args.keystore.resolve()]
        for env, option in [("KEYSTORE_PASSWORD", "--keystore-password"),
                            ("KEY_ALIAS", "--keystore-entry-alias"),
                            ("KEY_PASSWORD", "--keystore-entry-password")]:
            if os.environ.get(env):
                command += [option, os.environ[env]]
        result = subprocess.run(list(map(str, [*command, args.apk.resolve()])), cwd=ROOT)
        if result.returncode:
            raise SystemExit(f"APK patch/sign step failed (exit {result.returncode})")

if __name__ == "__main__":
    main()

