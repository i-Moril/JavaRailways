import copy
import tempfile
import unittest
from pathlib import Path
from compile_provenance import OUTPUTS, class_manifest, runtime_manifest, source_manifest, verify_receipt


class CompilationReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.source = self.repo / 'Basic/src/main/Example.kt'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('class Example')
        for directory in OUTPUTS.values():
            target = self.repo / directory / 'Example.class'
            target.parent.mkdir(parents=True)
            target.write_bytes(b'compiled example')
        self.classpath = str(self.repo / OUTPUTS[':Basic:compileKotlin'])
        self.receipt = dict(repo=str(self.repo), source_sha256=source_manifest(self.repo),
            tasks={task:class_manifest(self.repo, directory) for task,directory in OUTPUTS.items()},
            classpath=self.classpath, runtime_sha256=runtime_manifest(self.classpath))

    def test_unchanged_compile_receipt(self):
        verify_receipt(self.repo, self.receipt, self.classpath)

    def test_source_changed_before_runner_started(self):
        self.source.write_text('class Changed')
        with self.assertRaisesRegex(ValueError, 'Sources differ'):
            verify_receipt(self.repo, self.receipt, self.classpath)

    def test_source_added_or_removed_before_runner_started(self):
        for change in ('add', 'remove'):
            with self.subTest(change=change):
                extra = self.source.with_name('Extra.kt')
                if change == 'add':
                    extra.write_text('class Extra')
                else:
                    extra.unlink()
                    self.source.unlink()
                with self.assertRaisesRegex(ValueError, 'Sources differ'):
                    verify_receipt(self.repo, self.receipt, self.classpath)

    def test_compiled_class_changed(self):
        target = self.repo / OUTPUTS[':Core:compileKotlin'] / 'Example.class'
        target.write_bytes(b'corrupted')
        with self.assertRaisesRegex(ValueError, 'Compiled classes differ'):
            verify_receipt(self.repo, self.receipt, self.classpath)

    def test_runtime_classpath_changed(self):
        (Path(self.classpath) / 'Extra.class').write_bytes(b'foreign')
        with self.assertRaisesRegex(ValueError, 'Runtime classpath contents differ'):
            verify_receipt(self.repo, self.receipt, self.classpath)

    def test_partial_receipt_rejected(self):
        receipt = copy.deepcopy(self.receipt)
        receipt['tasks'].pop(':Core:compileKotlin')
        with self.assertRaisesRegex(ValueError, 'Incomplete compilation receipt'):
            verify_receipt(self.repo, receipt, self.classpath)


if __name__ == '__main__':
    unittest.main()
