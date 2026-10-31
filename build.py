#!/usr/bin/env python3
"""Build standalone Sync 23033 compatibility patches using locally supplied tools."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--tools', type=Path, required=True)
parser.add_argument('--apk', type=Path)
parser.add_argument('--keystore', type=Path)
args = parser.parse_args()
tools = args.tools.resolve()
build = root / 'build'
output = root / 'output'
for name in ['stubs', 'extension', 'tests', 'patches', 'dex-extension', 'dex-patches']:
    (build / name).mkdir(parents=True, exist_ok=True)
output.mkdir(exist_ok=True)
java = str(Path(shutil.which('java')).resolve())
java_home = str(Path(java).parent.parent)

def run(*command):
    subprocess.run([str(c) for c in command], cwd=root, check=True)

def jar(directory, target):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(directory).as_posix())

def cp(*paths):
    return os.pathsep.join(map(str, paths))

javac = [java, '-XX:-UsePerfData', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
run(*javac, '--release', '8', '-d', build / 'stubs',
    root / 'src/stubs/app/revanced/extension/shared/Logger.java')
extension_cp = cp(build / 'stubs', tools / 'android.jar', tools / 'volley.jar', tools / 'json.jar')
run(*javac, '--release', '8', '-cp', extension_cp, '-d', build / 'extension',
    *sorted((root / 'src/extension').rglob('*.java')))
test_cp = cp(build / 'stubs', build / 'extension', tools / 'volley.jar', tools / 'json.jar', tools / 'android.jar')
run(*javac, '-cp', test_cp, '-d', build / 'tests', *sorted((root / 'tests').rglob('*.java')))
run(java, '-XX:-UsePerfData', '-cp', cp(build / 'tests', test_cp), 'BatchCommentsTest')
run(java, '-XX:-UsePerfData', '-cp', cp(build / 'tests', test_cp),
    'app.revanced.extension.synccompat.SafeSLinksTest')
jar(build / 'stubs', build / 'stubs.jar')
jar(build / 'extension', build / 'extension.jar')
run(java, '-XX:-UsePerfData', '-cp', tools / 'r8.jar', 'com.android.tools.r8.D8',
    '--release', '--min-api', '23', '--lib', java_home,
    '--classpath', tools / 'android.jar', '--classpath', tools / 'volley.jar',
    '--classpath', tools / 'json.jar', '--classpath', build / 'stubs.jar',
    '--output', build / 'dex-extension', build / 'extension.jar')
run(java, '-XX:-UsePerfData', '-cp', str(tools / 'kotlin/kotlinc/lib') + '/*',
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-Xskip-prerelease-check',
    '-Xallow-unstable-dependencies', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-Xlambdas=class', '-Xstring-concat=inline', '-classpath', tools / 'revanced-cli.jar',
    '-d', build / 'patches', root / 'src/patches/SyncCompatPatches.kt')
jar(build / 'patches', build / 'patches.jar')
run(java, '-XX:-UsePerfData', '-cp', tools / 'r8.jar', 'com.android.tools.r8.D8',
    '--release', '--min-api', '26', '--lib', java_home,
    '--classpath', tools / 'revanced-cli.jar', '--output', build / 'dex-patches', build / 'patches.jar')
bundle = output / 'sync-gallery-comments-compat-v0.6.1.rvp'
manifest = ('Manifest-Version: 1.0\r\nName: Sync Gallery and Batch Comments Compatibility\r\n'
            'Description: Gallery, batch comments and secure share links for Sync build 23033\r\n'
            'Version: 0.6.1\r\nTimestamp: ' + str(int(time.time()*1000)) + '\r\n'
            'Author: ReVanced contributors and local compatibility changes\r\nLicense: GPL-3.0-only\r\n\r\n')
with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
    archive.writestr('META-INF/MANIFEST.MF', manifest)
    for path in sorted((build / 'patches').rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(build / 'patches').as_posix())
    archive.write(build / 'dex-patches/classes.dex', 'classes.dex')
    archive.write(build / 'dex-extension/classes.dex', 'extensions/synccompat.rve')
print(bundle)
if args.apk:
    if not args.keystore:
        parser.error('--apk requires your own --keystore')
    run(java, '-XX:-UsePerfData', '-jar', tools / 'revanced-cli.jar', 'patch', '-p', bundle,
        '-b', '-o', output / 'Sync-v23.06.30-gallery-comments-v0.6.1.apk', '-t', build / 'patch-temp',
        '--keystore', args.keystore.resolve(), args.apk.resolve())
