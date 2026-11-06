#!/usr/bin/env python3
"""Verify exact PdfCarton packages on Windows without regeneration.

Only the explicit publish-prepared command dispatches publication, after
Windows checks and final live prerequisites. All other modes are read-only
with respect to GitHub/NuGet publication.

Read the windows-testing skill before execution. Each invocation stages a new
owned run and fresh package cache. Results are never implicitly reused: changes
to package bytes, tests, host/runtime or mode require a new run. Published mode
restores from NuGet.org only, independently of candidate archives/signatures.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import zipfile
from copy import copy

ROOT = Path(__file__).resolve().parents[1]
SUPPORT = ROOT / 'scripts/pdfcarton-windows'
# Upstream fixtures the consumer tests read from the product test project.
PRODUCT_FIXTURES = ROOT / 'products/pdfcarton/tests/DripSharp.PdfCarton.Tests/Fixtures'
UPSTREAM_FIXTURES = ('input/eu-001.pdf', 'modules/pdfbox/target/pdfs/PDFBOX-3947-670064.pdf')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def package_identity(path, version, commit):
    with zipfile.ZipFile(path) as archive:
        names = [name for name in archive.namelist() if name.endswith('.nuspec')]
        if len(names) != 1:
            raise ValueError('Expected one package manifest')
        tree = ET.fromstring(archive.read(names[0]))
        metadata = tree.find('{*}metadata')
        actual = (metadata.findtext('{*}id'), metadata.findtext('{*}version'), metadata.find('{*}repository').get('commit'))
        if actual != ('DripSharp.PdfCarton', version, commit):
            raise ValueError(f'Package ID/version/commit mismatch: {actual}')
    return digest(path)


def powershell_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def remote(host, code, timeout=60):
    encoded = base64.b64encode(code.encode('utf-16le')).decode()
    return subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', host,
                           'pwsh', '-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded],
                          timeout=timeout, text=True, capture_output=True)


def preflight(host):
    result = remote(host, '& {\n' + (SUPPORT / 'run.ps1').read_text() + '\n} -Preflight', 45)
    if result.returncode:
        raise RuntimeError(f'Windows preflight failed ({result.returncode}): {result.stderr}')
    return json.loads(result.stdout)


def project(version, legacy=False):
    # The consumer tests are the product's own test sources, some of which use
    # internals granted to DripSharp.PdfCarton.Tests; build them under that name.
    references = f'<PackageReference Include="DripSharp.PdfCarton" Version="{version}" />'
    if legacy:
        references += '<PackageReference Include="Microsoft.NETFramework.ReferenceAssemblies.net481" Version="1.0.3" PrivateAssets="all" />'
    else:
        references += '<PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.14.1" PrivateAssets="all" /><PackageReference Include="xunit.v3" Version="3.2.2" PrivateAssets="all" /><PackageReference Include="xunit.runner.visualstudio" Version="3.1.5" PrivateAssets="all" />'
    return f'''<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup>
<TargetFramework>{'net481' if legacy else 'net10.0'}</TargetFramework>
<OutputType>{'Exe' if legacy else 'Library'}</OutputType>
{'' if legacy else '<AssemblyName>DripSharp.PdfCarton.Tests</AssemblyName>'}
<IsTestProject>{'false' if legacy else 'true'}</IsTestProject>
<ImplicitUsings>enable</ImplicitUsings><Nullable>{'disable' if legacy else 'enable'}</Nullable>
<LangVersion>latest</LangVersion><PlatformTarget>x64</PlatformTarget><TreatWarningsAsErrors>true</TreatWarningsAsErrors>
</PropertyGroup><ItemGroup>{references}</ItemGroup>
<ItemGroup><None Update="Fixtures/**/*" CopyToOutputDirectory="PreserveNewest" /></ItemGroup></Project>'''


def stage_inputs(upload, version):
    for name in ('smoke', 'legacy'):
        (upload / name).mkdir(parents=True)
        (upload / name / (name + '.csproj')).write_text(project(version, name == 'legacy'))
    sources = ROOT / 'targets/pdfcube/consumer-tests'
    for source in sources.glob('*.cs'):
        # The extra raster conformance cases use generated-test internals; retain
        # the existing public Fonts consumer. Native rendering is checked below.
        text = source.read_text()
        if source.name == 'FontsConsumerTests.cs':
            text = text.split('// Bulk raster access')[0]
        (upload / 'smoke' / source.name).write_text(text)
    shutil.copytree(sources / 'fixtures', upload / 'smoke/Fixtures')
    for name in UPSTREAM_FIXTURES:
        target = upload / 'smoke/Fixtures' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(PRODUCT_FIXTURES / name, target)
    # Run serially, as the product suite does: some consumer tests count
    # process-wide bitmaps and caches.
    (upload / 'smoke/SerialSuite.cs').write_text(
        '[assembly: Xunit.CollectionBehavior(DisableTestParallelization = true)]\n')
    shutil.copy2(SUPPORT / 'WindowsRegressionTests.cs', upload / 'smoke')
    shutil.copy2(SUPPORT / 'LegacyExceptions.cs', upload / 'legacy')
    shutil.copy2(SUPPORT / 'run.ps1', upload)
    (upload / 'global.json').write_text(json.dumps({'sdk': {'version': '10.0.100', 'rollForward': 'latestFeature', 'allowPrerelease': False}}))


def check_result(result, request, files):
    for key in ('runId', 'inputDigest', 'mode', 'version', 'commit'):
        if result.get(key) != request[key]:
            raise ValueError(f'Stale or mismatched Windows result: {key}')
    if result.get('status') != 'passed' or result.get('exitCode') != 0:
        raise ValueError('Windows tests failed; inspect retrieved runner-error and stage logs')
    for name in ('environment.json', 'package.json', 'steps.json', 'smoke.trx', 'legacy-execute.stdout.log', 'exit-code.txt'):
        if not (files / name).is_file():
            raise ValueError(f'Missing required Windows result: {name}')
    package = json.loads((files / 'package.json').read_text(encoding='utf-8-sig'))
    if (package.get('id'), package.get('version'), package.get('commit')) != ('DripSharp.PdfCarton', request['version'], request['commit']):
        raise ValueError('Result package metadata mismatch')
    if request['mode'] == 'candidate' and package.get('sha256') != request['packageSHA256']:
        raise ValueError('Same-version/different-package result rejected')
    steps = json.loads((files / 'steps.json').read_text(encoding='utf-8-sig'))
    expected = [case + '-' + phase for case in ('smoke', 'legacy') for phase in ('restore', 'build', 'execute')]
    if [step['name'] for step in steps] != expected or any(step['exitCode'] for step in steps):
        raise ValueError('Missing or failing Windows stage')
    trx = ET.parse(files / 'smoke.trx')
    counters = trx.find('.//{*}Counters')
    if counters is None or int(counters.get('passed', '0')) < 13 or int(counters.get('failed', '0')):
        raise ValueError('Missing mandatory smoke/consumer Windows assertions')
    if (files / 'exit-code.txt').read_text(encoding='utf-8-sig').strip() != '0':
        raise ValueError('Windows exit-code artifact is not zero')


def run(args):
    environment = preflight(args.host)
    if args.mode == 'preflight':
        print(json.dumps(environment, indent=2))
        return 0
    if not args.version or not args.commit:
        raise ValueError('--version and --commit are required')
    if not re.fullmatch(r'[0-9a-f]{40}', args.commit) or not re.fullmatch(r'[0-9A-Za-z.+-]+', args.version):
        raise ValueError('Expected full product commit SHA and NuGet version')
    if args.mode == 'candidate' and not args.package:
        raise ValueError('--package is required in candidate mode')
    package_hash = package_identity(args.package, args.version, args.commit) if args.mode == 'candidate' else None
    repo = Path(subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=ROOT, text=True).strip()).resolve()
    short = lambda value: hashlib.sha256(str(value).encode()).hexdigest()[:12]
    workspace = f'C:/WT/r{short(repo)}/w{short(ROOT.resolve())}'
    run_id = datetime.now(timezone.utc).strftime('%y%m%d%H%M%S') + '-' + secrets.token_hex(3)
    remote_root = workspace + '/' + run_id
    local = Path(args.results or ROOT / 'target/windows-tests').resolve() / run_id
    upload = local / 'upload'
    upload.mkdir(parents=True)
    stage_inputs(upload, args.version)
    if package_hash:
        (upload / 'feed').mkdir()
        shutil.copy2(args.package, upload / 'feed' / Path(args.package).name)
    local_feed = '<add key="candidate" value="feed" />' if package_hash else ''
    # Mapping forbids a same-version public package from satisfying candidate restore.
    mapping = '<packageSource key="candidate"><package pattern="DripSharp.PdfCarton" /></packageSource>' if package_hash else ''
    (upload / 'NuGet.Config').write_text(f'<configuration><packageSources><clear />{local_feed}<add key="nuget.org" value="https://api.nuget.org/v3/index.json" /></packageSources><packageSourceMapping>{mapping}<packageSource key="nuget.org"><package pattern="*" /></packageSource></packageSourceMapping></configuration>')
    inputs = hashlib.sha256()
    for path in sorted(upload.rglob('*')):
        if path.is_file():
            inputs.update(str(path.relative_to(upload)).encode())
            inputs.update(path.read_bytes())
    request = dict(runId=run_id, inputDigest=inputs.hexdigest(), mode=args.mode, version=args.version, commit=args.commit, packageSHA256=package_hash, packageFile=Path(args.package).name if package_hash else None)
    (upload / 'request.json').write_text(json.dumps(request, indent=2))
    (local / 'preflight.json').write_text(json.dumps(environment, indent=2))
    record = dict(runId=run_id, state='active', started=datetime.now(timezone.utc).isoformat(), localArtifactDestination=str(local))
    owner = dict(repository=str(repo), workspace=str(ROOT.resolve()), created=datetime.now(timezone.utc).isoformat())
    q = powershell_quote
    initialize = f'''$ErrorActionPreference='Stop'
$workspace={q(workspace)}
New-Item -ItemType Directory -Force $workspace | Out-Null
$ownerPath=Join-Path $workspace 'workspace.json'
$owner={q(json.dumps(owner))} | ConvertFrom-Json
if (Test-Path $ownerPath) {{ $existing=Get-Content $ownerPath -Raw | ConvertFrom-Json; if ($existing.repository -ne $owner.repository -or $existing.workspace -ne $owner.workspace) {{ throw 'Workspace identity collision' }} }} else {{ $owner | ConvertTo-Json | Set-Content $ownerPath }}
if (Test-Path {q(remote_root)}) {{ throw 'Run path already exists' }}
New-Item -ItemType Directory -Path {q(remote_root + '/i')},{q(remote_root + '/o')} -Force | Out-Null
{q(json.dumps(record))} | Set-Content {q(remote_root + '/run.json')}
'''
    result = remote(args.host, initialize)
    if result.returncode:
        raise RuntimeError(result.stderr)
    longest = max(len((remote_root + '/i/' + str(path.relative_to(upload))).encode('utf-16le')) // 2 for path in upload.rglob('*'))
    if longest > 180:
        raise ValueError(f'Staged paths leave insufficient Windows build path budget: {longest}')
    timings = {}
    def transfer(source, destination):
        return subprocess.run(['scp', '-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-r', source, destination], timeout=180, capture_output=True, text=True)
    started = time.monotonic()
    result = transfer(str(upload) + '/.', args.host + ':' + remote_root + '/i/')
    timings['uploadSeconds'] = time.monotonic() - started
    if result.returncode:
        raise RuntimeError('Windows upload failed: ' + result.stderr)
    started = time.monotonic()
    try:
        result = remote(args.host, '& ' + q(remote_root + '/i/run.ps1') + '; exit $LASTEXITCODE', 1800)
        (local / 'ssh.log').write_text(result.stdout + result.stderr)
        ssh_code = result.returncode
    except subprocess.TimeoutExpired:
        # Inner steps have 10-minute owned-process-tree timeouts; the transport
        # timeout is incomplete evidence, never success or permission to reuse.
        ssh_code = 124
        (local / 'ssh.log').write_text('SSH timeout; remote run may still need inspection. No result reuse permitted.\n')
    timings['remoteSeconds'] = time.monotonic() - started
    started = time.monotonic()
    download = transfer(args.host + ':' + remote_root + '/o', str(local / 'results'))
    timings['downloadSeconds'] = time.monotonic() - started
    (local / 'timings.json').write_text(json.dumps(timings, indent=2))
    (local / 'request.json').write_text(json.dumps(request, indent=2))
    print(f'Windows {args.mode} artifacts: {local}', flush=True)
    if download.returncode:
        raise RuntimeError('Windows result download failed: ' + download.stderr)
    if ssh_code:
        raise RuntimeError(f'Windows command failed/incomplete: exit {ssh_code}; logs: {local}')
    files = local / 'results'
    check_result(json.loads((files / 'result.json').read_text(encoding='utf-8-sig')), request, files)
    print(json.dumps({'status': 'passed', 'mode': args.mode, 'artifacts': str(local), 'timings': timings}, indent=2))
    return 0


def github_json(args):
    completed = subprocess.run(['gh'] + args, text=True, capture_output=True, timeout=60)
    if completed.returncode:
        raise RuntimeError('GitHub verification failed: ' + completed.stderr)
    return json.loads(completed.stdout)


def prepared_run(args):
    if not args.run_id or not re.fullmatch(r'[0-9]+', args.run_id):
        raise ValueError('--run-id must identify the successful prepare workflow run')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        raise ValueError('Expected owner/repository')
    record = github_json(['api', f'repos/{args.repo}/actions/runs/{args.run_id}'])
    if any(record.get(key) != value for key, value in {
            'head_sha': args.commit, 'head_branch': 'master', 'event': 'workflow_dispatch',
            'status': 'completed', 'conclusion': 'success',
            'path': '.github/workflows/nuget-release.yml'}.items()):
        raise ValueError('Prepare run must be successful nuget-release.yml workflow_dispatch on the selected master commit')
    return record


def verify_artifact(directory, version, commit):
    expected = {f'DripSharp.PdfCarton.{version}.nupkg', f'DripSharp.PdfCarton.{version}.snupkg'}
    if {path.name for path in directory.iterdir()} != expected | {'SHA256SUMS'}:
        raise ValueError('Unexpected prepared artifact inventory')
    rows = (directory / 'SHA256SUMS').read_text().splitlines()
    parsed = {}
    for row in rows:
        match = re.fullmatch(r'([0-9a-fA-F]{64}) [ *]([^/\\]+)', row)
        if not match or match[2] not in expected or match[2] in parsed:
            raise ValueError('Invalid SHA256SUMS inventory')
        parsed[match[2]] = match[1].lower()
    if set(parsed) != expected:
        raise ValueError('Missing prepared package checksum')
    for name, expected_hash in parsed.items():
        if digest(directory / name) != expected_hash:
            raise ValueError('Prepared artifact SHA256SUMS mismatch: ' + name)
        package_identity(directory / name, version, commit)
    return directory / f'DripSharp.PdfCarton.{version}.nupkg'


def verify_prepared(args):
    if not args.version or not args.commit:
        raise ValueError('--version and --commit are required')
    if not re.fullmatch(r'[0-9a-f]{40}', args.commit) or not re.fullmatch(r'[0-9A-Za-z.+-]+', args.version):
        raise ValueError('Expected full product commit SHA and NuGet version')
    # Availability is cheap and precedes artifact download and all Windows work.
    preflight(args.host)
    prepared_run(args)
    directory = (ROOT / 'target/windows-tests' / ('prepared-' + args.run_id + '-' + secrets.token_hex(3))).resolve()
    directory.mkdir(parents=True)
    download = subprocess.run(['gh', 'run', 'download', args.run_id, '--repo', args.repo,
                               '--name', 'pdfcarton-nuget-release', '--dir', str(directory)],
                              text=True, capture_output=True, timeout=180)
    if download.returncode:
        raise RuntimeError('Prepare artifact download failed: ' + download.stderr)
    package = verify_artifact(directory, args.version, args.commit)
    candidate = copy(args)
    candidate.mode = 'candidate'
    candidate.package = package
    code = run(candidate)
    if code or args.mode != 'publish-prepared':
        return code
    # Explicit publish-prepared invocation is a publication request. Check the
    # immutable artifact and current remote state again, then hand the same run
    # ID to the hosted publish mode. No receipt or old Windows result is reused.
    verify_artifact(directory, args.version, args.commit)
    prepared_run(args)
    jobs = github_json(['api', f'repos/{args.repo}/actions/runs/{args.run_id}/jobs?per_page=100'])['jobs']
    publish_jobs = [job for job in jobs if job.get('name') == 'Publish PdfCarton to nuget.org']
    if len(publish_jobs) != 1 or publish_jobs[0].get('conclusion') != 'skipped':
        raise ValueError('Publication requires a prepare-only run whose publish job was skipped')
    prerequisite = subprocess.run(['clojure', '-J-Xmx512m', '-M:run', 'product-preflight', 'pdfcube', '--live'],
                                  cwd=ROOT, text=True, capture_output=True, timeout=120)
    if prerequisite.returncode:
        raise RuntimeError('Final live release prerequisites failed: ' + prerequisite.stdout + prerequisite.stderr)
    head = github_json(['api', f'repos/{args.repo}/git/ref/heads/master'])['object']['sha']
    if head != args.commit:
        raise ValueError('Remote master moved since prepare; refusing publication')
    dispatched = subprocess.run(['gh', 'workflow', 'run', 'nuget-release.yml', '--repo', args.repo,
                                 '--ref', 'master', '-f', 'mode=publish', '-f', 'prepare_run_id=' + args.run_id,
                                 '-f', 'package_checksums_sha256=' + digest(directory / 'SHA256SUMS')],
                                text=True, capture_output=True, timeout=60)
    if dispatched.returncode:
        raise RuntimeError('Publish dispatch failed: ' + dispatched.stderr)
    print('Publish dispatched for exact Windows-tested prepare run ' + args.run_id +
          '. Follow configured deployment protection. Upload/indexing and published Windows verification remain pending.')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['preflight', 'candidate', 'published', 'verify-prepared', 'publish-prepared'])
    parser.add_argument('--host', default=os.getenv('PDFCARTON_WINDOWS_HOST', 'windows-test-vm'))
    parser.add_argument('--package', type=Path)
    parser.add_argument('--run-id')
    parser.add_argument('--repo', default='dripsharp/pdfcarton')
    parser.add_argument('--version')
    parser.add_argument('--commit')
    parser.add_argument('--results', type=Path)
    try:
        args = parser.parse_args()
        if args.mode in ('verify-prepared', 'publish-prepared'):
            return verify_prepared(args)
        return run(args)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        return 1

if __name__ == '__main__':
    sys.exit(main())
