#!/usr/bin/env python3
"""Package the verified Maven build into runtime and SDK release ZIPs."""

import argparse
from datetime import datetime
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import re
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parent.parent
MAVEN = {'pom': 'http://maven.apache.org/POM/4.0.0'}


def inspection(jar):
    with ZipFile(jar) as archive:
        for name in ('LICENSE', 'NOTICE'):
            if archive.read(f'META-INF/{name}') != (ROOT / name).read_bytes():
                raise ValueError(f'{jar.name} has an unexpected {name}')
        return sorted(Path(path).name for path in archive.namelist()
                      if path.startswith('BOOT-INF/lib/') and path.endswith('.jar'))


def package(destination, timestamp, members):
    with ZipFile(destination, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, source in sorted(members.items()):
            info = ZipInfo(f'{destination.stem}/{name}', timestamp)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            data = source.read_bytes() if isinstance(source, Path) else source.encode()
            archive.writestr(info, data, compress_type=ZIP_DEFLATED, compresslevel=9)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='destination, defaults to release/v<version>')
    args = parser.parse_args()
    parent = ET.parse(ROOT / 'pom.xml').getroot()
    version = parent.findtext('pom:version', namespaces=MAVEN)
    if not re.fullmatch(r'\d+\.\d+\.\d+', version or ''):
        parser.error('The parent POM must specify a release version')
    date = parent.findtext('pom:properties/pom:project.build.outputTimestamp', namespaces=MAVEN)
    timestamp = datetime.fromisoformat(date.replace('Z', '+00:00')).timetuple()[:6]
    directory = args.output or ROOT / 'release' / f'v{version}'
    runtime = ROOT / 'start' / 'target' / f'java-dsh-bridge-{version}.jar'
    sdk_root = ROOT / 'java-dsh-bridge-sdk'
    sdk = sdk_root / 'target' / f'java-dsh-bridge-sdk-{version}.jar'
    if not runtime.is_file() or not sdk.is_file():
        parser.error('Run ./mvnw -B -ntp clean verify before packaging')
    dependencies = inspection(runtime)
    if not dependencies or inspection(sdk):
        raise ValueError('Expected an executable Bridge JAR and a standalone SDK library JAR')

    files = {name: ROOT / name for name in ('LICENSE', 'NOTICE', 'LICENSE.zh-CN.md', 'NOTICE.zh-CN.md')}
    # The release guides are self-contained and apply to both ZIPs.
    for name in ('RELEASE.md', 'RELEASE.en.md'):
        files[name] = ROOT / name
    directory.mkdir(parents=True, exist_ok=True)
    runtime_name = f'java-dsh-bridge-{version}.zip'
    sdk_name = f'java-dsh-bridge-sdk-{version}.zip'
    notices = ROOT / 'start' / 'src' / 'main' / 'resources' / 'META-INF' / 'third-party'
    runtime_files = {f'third-party/{path.name}': path for path in notices.iterdir() if path.is_file()}
    runtime_files.update(files)
    runtime_files[runtime.name] = runtime
    runtime_files['DEPENDENCIES.txt'] = '\n'.join(dependencies) + '\n'
    sdk_files = {**files, sdk.name: sdk, f'java-dsh-bridge-sdk-{version}.pom': sdk_root / 'pom.xml',
                 f'java-dsh-bridge-parent-{version}.pom': ROOT / 'pom.xml'}
    with TemporaryDirectory(dir=directory) as staging:
        stage = Path(staging)
        runtime_zip = stage / runtime_name
        sdk_zip = stage / sdk_name
        package(runtime_zip, timestamp, runtime_files)
        package(sdk_zip, timestamp, sdk_files)
        assets = (runtime_zip, sdk_zip)
        sums = stage / 'SHA256SUMS'
        sums.write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n' for path in assets),
                        encoding='ascii')
        for path in (*assets, sums):
            destination = directory / path.name
            path.replace(destination)
            print(destination.resolve())



if __name__ == '__main__':
    main()
