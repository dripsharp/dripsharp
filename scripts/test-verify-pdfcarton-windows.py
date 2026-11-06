#!/usr/bin/env python3
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile

spec = importlib.util.spec_from_file_location('runner', Path(__file__).with_name('verify-pdfcarton-windows.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class WindowsRunnerTests(unittest.TestCase):
    def test_preflight_requires_stable_sdk_and_runtime(self):
        script = (runner.SUPPORT / 'run.ps1').read_text()
        for variable, prefix in (('sdk', ''), ('runtimes', 'Microsoft.NETCore.App ')):
            pattern = re.search(r"\$" + variable + r" -match '([^']+)'", script).group(1)
            self.assertRegex(prefix + r'10.0.401 [C:\dotnet]', pattern)
            for version in ('10.0.100-preview.1', '10.0.401-rc.1', '10.0', '11.0.100', '9.0.100'):
                self.assertNotRegex(prefix + version + r' [C:\dotnet]', pattern)

    def test_preflight_transport_and_timeout_never_pass(self):
        with patch.object(runner, 'remote', return_value=subprocess.CompletedProcess([], 37, '', 'VM unavailable')):
            with self.assertRaisesRegex(RuntimeError, '37'):
                runner.preflight('fixture')
        with patch.object(runner, 'remote', side_effect=subprocess.TimeoutExpired('ssh', 45)):
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.preflight('fixture')

    def test_package_metadata_and_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            def package(name, content, commit='a'*40):
                path = Path(root) / name
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('test.nuspec', f'<package><metadata><id>DripSharp.PdfCarton</id><version>1.0.0</version><repository commit="{commit}" /></metadata></package>')
                    archive.writestr('a.dll', content)
                return path
            first = package('one.nupkg', b'first')
            second = package('two.nupkg', b'second')
            self.assertNotEqual(runner.package_identity(first, '1.0.0', 'a'*40), runner.package_identity(second, '1.0.0', 'a'*40))
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                runner.package_identity(first, '1.0.0', 'b'*40)

    def test_stale_missing_same_version_and_failed_results(self):
        request = dict(runId='new', inputDigest='tests', mode='candidate', version='1.0.0', commit='a'*40, packageSHA256='exact')
        result = dict(request, status='passed', exitCode=0)
        with tempfile.TemporaryDirectory() as root:
            files = Path(root)
            for key in ('runId', 'inputDigest', 'mode', 'commit'):
                with self.assertRaisesRegex(ValueError, 'Stale'):
                    runner.check_result(dict(result, **{key: 'stale'}), request, files)
            with self.assertRaisesRegex(ValueError, 'Missing required'):
                runner.check_result(result, request, files)
            for name in ('environment.json','steps.json','smoke.trx','legacy-execute.stdout.log','exit-code.txt'):
                (files/name).write_text('0')
            (files/'package.json').write_text(json.dumps(dict(id='DripSharp.PdfCarton', version='1.0.0', commit='a'*40, sha256='other')))
            with self.assertRaisesRegex(ValueError, 'Same-version/different-package'):
                runner.check_result(result, request, files)
            with self.assertRaisesRegex(ValueError, 'failed'):
                runner.check_result(dict(result, exitCode=37), request, files)

    def test_staging_has_one_product_reference_no_source_reference(self):
        with tempfile.TemporaryDirectory() as root:
            runner.stage_inputs(Path(root), '3.0.8-alpha.6')
            for name in ('legacy','smoke'):
                source=(Path(root)/name/(name+'.csproj')).read_text()
                self.assertEqual(1, source.count('Include="DripSharp.PdfCarton"'))
                self.assertNotIn('ProjectReference', source)
            self.assertTrue((Path(root)/'smoke/ReleaseSmokePdfTests.cs').is_file())
            self.assertIn('<AssemblyName>DripSharp.PdfCarton.Tests</AssemblyName>', (Path(root)/'smoke/smoke.csproj').read_text())
            self.assertNotIn('AssemblyName', (Path(root)/'legacy/legacy.csproj').read_text())
            for name in runner.UPSTREAM_FIXTURES:
                self.assertTrue((Path(root)/'smoke/Fixtures'/name).is_file())
            self.assertIn('DisableTestParallelization = true', (Path(root)/'smoke/SerialSuite.cs').read_text())
            self.assertTrue((Path(root)/'smoke/IoConsumerTests.cs').is_file())

    def test_publish_order_and_failure_fences(self):
        for failure in ('windows', 'live', 'master', None):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as root:
                events = []
                args = SimpleNamespace(mode='publish-prepared', host='fixture', run_id='123', repo='owner/repo', version='1.0.0', commit='a'*40)
                def command(argv, **kwargs):
                    if argv[:3] == ['gh', 'run', 'download']:
                        events.append('download')
                    elif argv[0] == 'clojure':
                        events.append('live')
                        if failure == 'live':
                            return subprocess.CompletedProcess(argv, 1, '', 'version exists')
                    elif argv[:3] == ['gh', 'workflow', 'run']:
                        events.append('dispatch')
                        self.assertIn('package_checksums_sha256=fixture-checksum', argv)
                    else:
                        self.fail(str(argv))
                    return subprocess.CompletedProcess(argv, 0, '', '')
                def windows(_):
                    events.append('windows')
                    if failure == 'windows':
                        raise RuntimeError('Windows tests failed')
                    return 0
                def github(argv):
                    if '/jobs?' in argv[-1]:
                        return {'jobs': [{'name':'Publish PdfCarton to nuget.org','conclusion':'skipped'}]}
                    events.append('master')
                    return {'object': {'sha': ('b' if failure == 'master' else 'a')*40}}
                with patch.object(runner, 'ROOT', Path(root)), patch.object(runner, 'digest', return_value='fixture-checksum'), patch.object(runner, 'preflight'), patch.object(runner, 'prepared_run'), patch.object(runner, 'verify_artifact', return_value=Path(root)/'candidate.nupkg'), patch.object(runner, 'run', side_effect=windows), patch.object(runner, 'github_json', side_effect=github), patch.object(runner.subprocess, 'run', side_effect=command):
                    if failure:
                        with self.assertRaises((RuntimeError, ValueError)):
                            runner.verify_prepared(args)
                        self.assertNotIn('dispatch', events)
                    else:
                        self.assertEqual(0, runner.verify_prepared(args))
                        self.assertEqual(['download','windows','live','master','dispatch'], events)

    def test_prepared_artifact_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)
            for suffix in ('nupkg','snupkg'):
                (path / ('DripSharp.PdfCarton.1.0.0.'+suffix)).write_bytes(b'wrong')
            (path/'SHA256SUMS').write_text('0'*64+'  DripSharp.PdfCarton.1.0.0.nupkg\n'+'0'*64+'  DripSharp.PdfCarton.1.0.0.snupkg\n')
            with self.assertRaisesRegex(ValueError, 'SHA256SUMS mismatch'):
                runner.verify_artifact(path, '1.0.0', 'a'*40)

if __name__ == '__main__': unittest.main()
