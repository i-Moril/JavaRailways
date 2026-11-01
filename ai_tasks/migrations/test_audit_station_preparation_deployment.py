import json
from pathlib import Path
import tempfile
import unittest
import audit_station_preparation_deployment as m

class Validator:
    def __init__(self, valid): self.valid=valid
    def verify(self): pass
    def __call__(self, operation, value):
        if not self.valid: raise ValueError('invalid schema')
        return {}

class AuditTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.paths=[self.root/name for name in ['documents','worlds','timetables','javascript']]
        for p in self.paths:p.mkdir()
        (self.paths[0]/'world').mkdir()
        self.doc=self.paths[0]/'world/a.json';self.doc.write_text('{"arrivalOverrides":[]}')
        self.runtime=self.paths[1]/'a-runtime.json'
        self.write_runtime([])
    def write_runtime(self, timetables):
        self.runtime.write_text(json.dumps(dict(formatVersion=2,simpleAtoSnapshotJson=json.dumps(dict(timetables=timetables)))))
    def audit(self, old=False,new=False):return m.audit(*self.paths,Validator(old),Validator(new))
    def test_existing_invalid_documents_are_reported_without_rewriting(self):
        before=self.doc.read_bytes();r=self.audit()
        self.assertEqual({'source_False_candidate_False':1},r['documentValidation'])
        self.assertTrue(r['zeroLeadMigrationTargets']);self.assertEqual(before,self.doc.read_bytes())
    def test_validator_regression_is_distinguished(self):
        self.assertEqual(1,self.audit(True,False)['validatorRegressions'])
    def test_numeric_lead_and_fallback_timetables_require_review(self):
        self.doc.write_text('{"stationPrograms":[{"preparationLeadTicks":20}]}')
        self.assertFalse(self.audit()['zeroLeadMigrationTargets'])
        self.doc.write_text('{}')
        fallback=self.runtime.with_suffix('.json.bak')
        fallback.write_text(json.dumps(dict(formatVersion=2,simpleAtoSnapshotJson=json.dumps(dict(timetables=[{}])))))
        self.assertFalse(self.audit()['zeroLeadMigrationTargets'])
        self.assertEqual(2,self.audit()['runtimeFiles'])
    def test_formal_js_is_not_silently_ignored(self):
        (self.paths[3]/'a.js').write_text('anything')
        self.assertFalse(self.audit()['zeroLeadMigrationTargets'])

if __name__=='__main__':unittest.main()
