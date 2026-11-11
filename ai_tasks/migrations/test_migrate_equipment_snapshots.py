import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import migrate_equipment_snapshots as m
from remove_program_library_archived import digest, output_json

class Codec:
    """Deterministic test double; real-JAR integration below tests production canonical codecs."""
    def __init__(self, current=False): self.current=current
    def verify(self): pass
    def provenance(self):return {'jarSha256':'candidate' if self.current else 'source'}
    def __call__(self, operation, value):
        value=copy.deepcopy(value)
        if operation.endswith('-ref') or operation=='runtime-file':return {}
        value.pop('hash',None)
        if self.current:value['equipmentFixture']=True
        elif 'equipmentFixture' in value:raise ValueError('not source schema')
        value['hash']=digest(output_json(value))
        return value

class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.instance=self.root/'instance';self.instance.mkdir()
        self.roots={key:self.instance/key for key in ['packages','models','worlds']}
        for root in self.roots.values():root.mkdir()
        self.source,self.candidate=Codec(),Codec(True)
        self.package=self.source('package',{'packageName':'test'})
        self.model=self.source('model',{'car':{'name':'test'}})
        for store,value in [('packages',self.package),('models',self.model)]:
            (self.roots[store]/(value['hash']+'.json')).write_bytes(output_json(value))
        self.core={'trains':[{'extension':{'BUKKIT':self.envelope('train',{'packageSnapshotHash':self.package['hash']})},
            'cars':[{'config':{'extension':{'BUKKIT':self.envelope('car',{'packageSnapshotHash':self.package['hash'], 'modelSnapshotHash':self.model['hash'], 'heldScenes':{'opaque':self.package['hash']}})}}}]}]}
        runtime={'formatVersion':2,'coreSnapshotJson':json.dumps(self.core),'basicSnapshotJson':'opaque','simpleAtoSnapshotJson':'opaque'}
        for suffix in ['-runtime.json','-runtime.json.bak']:
            (self.roots['worlds']/('world'+suffix)).write_bytes(output_json(runtime))
        (self.roots['worlds']/'world-infrastructure.json').write_bytes(b'opaque unchanged')
    def envelope(self,kind,payload):return {'typeId':'javarailways:'+kind+'_render_state','schemaVersion':1,'payload':payload}
    def inspect(self):return m.inspect(self.roots,self.source,self.candidate)
    def test_dry_run_pins_sources_and_both_primary_fallback_typed_references(self):
        before={key:m.scan(root,key!='worlds') for key,root in self.roots.items()}
        plan,originals,outputs=self.inspect()
        self.assertEqual(6,plan['referenceChanges'])
        self.assertEqual(before,originals)
        self.assertEqual(before,{key:m.scan(root,key!='worlds') for key,root in self.roots.items()})
        self.assertEqual(b'opaque unchanged',outputs['worlds']['world-infrastructure.json'])
        core=json.loads(json.loads(outputs['worlds']['world-runtime.json'])['coreSnapshotJson'])
        car=core['trains'][0]['cars'][0]['config']['extension']['BUKKIT']['payload']
        self.assertEqual(self.package['hash'],car['heldScenes']['opaque'])
        self.assertNotEqual(self.package['hash'],car['packageSnapshotHash'])
    def test_opaque_extension_payloads_are_never_searched(self):
        core=copy.deepcopy(self.core);opaque=self.envelope('car',{'packageSnapshotHash':'missing'})
        core['trains'][0]['extension']['other']={'typeId':'other:opaque','payload':opaque}
        m.convert_core(core,{'packages':{self.package['hash']:'a'*64},'models':{self.model['hash']:'b'*64}},self.candidate)
        self.assertEqual(opaque,core['trains'][0]['extension']['other']['payload'])
    def test_missing_snapshot_reference_aborts(self):
        (self.roots['packages']/(self.package['hash']+'.json')).unlink()
        with self.assertRaisesRegex(ValueError,'missing'):self.inspect()
    def test_corrupt_hash_filename_aborts(self):
        path=self.roots['models']/(self.model['hash']+'.json');path.write_bytes(output_json(dict(self.model,car={'name':'tampered'})))
        with self.assertRaisesRegex(ValueError,'hash'):self.inspect()
    def test_symlink_root_file_and_hardlink_are_rejected(self):
        link=self.instance/'link';link.symlink_to(self.roots['packages'],target_is_directory=True)
        with self.assertRaises(ValueError):m.inspect(dict(self.roots,packages=link),self.source,self.candidate)
        path=self.roots['packages']/(self.package['hash']+'.json');extra=self.root/'hardlink';os.link(path,extra)
        with self.assertRaises(ValueError):self.inspect()
    def test_apply_backups_and_converges_using_candidate_codec(self):
        plan,originals,_=self.inspect();backup=self.root/'backup'
        with patch.object(m,'ensure_stopped'):
            m.apply(self.roots,plan,backup,self.source,self.candidate,self.instance)
        after,_,_=self.inspect()
        self.assertTrue(all(item['action']=='unchanged' for item in after['files']))
        self.assertTrue((backup/'manifest.json').is_file())
        for store,values in originals.items():
            for name,raw in values.items():self.assertEqual(raw,(backup/store/name).read_bytes())
    def test_changed_source_rejected_before_apply(self):
        plan,_,_=self.inspect();(self.roots['worlds']/'new-file').write_bytes(b'new')
        with patch.object(m,'ensure_stopped'),self.assertRaisesRegex(ValueError,'inventory'):
            m.apply(self.roots,plan,self.root/'backup',self.source,self.candidate,self.instance)
    def test_failure_after_reference_replacement_rolls_everything_back(self):
        plan,originals,_=self.inspect();real=m.atomic_replace;failed=False
        def fault(path,data):
            nonlocal failed
            real(path,data)
            if not failed:failed=True;raise OSError('after replace')
        with patch.object(m,'ensure_stopped'),patch.object(m,'atomic_replace',side_effect=fault),self.assertRaisesRegex(ValueError,'rolled back'):
            m.apply(self.roots,plan,self.root/'backup',self.source,self.candidate,self.instance)
        self.assertEqual(originals,{key:m.scan(root,key!='worlds') for key,root in self.roots.items()})
    def test_failure_after_new_snapshot_publication_rolls_back_owned_file(self):
        plan,originals,_=self.inspect();real=m.create_atomic;failed=False
        def fault(*args,**kwargs):
            nonlocal failed
            real(*args,**kwargs)
            if not failed:failed=True;raise OSError('after create')
        with patch.object(m,'ensure_stopped'),patch.object(m,'create_atomic',side_effect=fault),self.assertRaisesRegex(ValueError,'rolled back'):
            m.apply(self.roots,plan,self.root/'backup',self.source,self.candidate,self.instance)
        self.assertEqual(originals,{key:m.scan(root,key!='worlds') for key,root in self.roots.items()})
    def test_foreign_file_created_at_snapshot_destination_is_not_deleted_by_rollback(self):
        plan,originals,_=self.inspect();real=m.create_atomic;foreign=b'foreign concurrent file';paths=[]
        def fault(path,*args,**kwargs):
            path.write_bytes(foreign);paths.append(path)
            return real(path,*args,**kwargs)
        with patch.object(m,'ensure_stopped'),patch.object(m,'create_atomic',side_effect=fault),self.assertRaisesRegex(ValueError,'rolled back'):
            m.apply(self.roots,plan,self.root/'backup',self.source,self.candidate,self.instance)
        self.assertEqual(1,len(paths))
        self.assertEqual(foreign,paths[0].read_bytes())
    def test_render_envelope_validation_does_not_accept_extra_fields_or_boolean_version(self):
        for extra in [{'unexpected':True},{'schemaVersion':True}]:
            core=copy.deepcopy(self.core)
            core['trains'][0]['extension']['BUKKIT'].update(extra)
            with self.assertRaises(ValueError):m.convert_core(core,{'packages':{},'models':{}},self.candidate)

    def test_running_instance_guard_precedes_writes(self):
        plan,originals,_=self.inspect()
        with patch.object(m,'ensure_stopped',side_effect=ValueError('running')),self.assertRaisesRegex(ValueError,'running'):
            m.apply(self.roots,plan,self.root/'backup',self.source,self.candidate,self.instance)
        self.assertFalse((self.root/'backup').exists())
        self.assertEqual(originals,{key:m.scan(root,key!='worlds') for key,root in self.roots.items()})

@unittest.skipUnless(os.environ.get('JR_EQUIPMENT_SOURCE_JAR') and os.environ.get('JR_EQUIPMENT_CANDIDATE_JAR'),'requires exact old and candidate plugin JARs')
class RealCodecTest(unittest.TestCase):
    def test_real_package_and_model_codecs_rehash_and_preserve_typed_payloads(self):
        java=Path(os.environ['JR_EQUIPMENT_JAVA_DIR']) if os.environ.get('JR_EQUIPMENT_JAVA_DIR') else None
        old=m.Bridge(Path(os.environ['JR_EQUIPMENT_SOURCE_JAR']),java);self.addCleanup(old.close)
        new=m.Bridge(Path(os.environ['JR_EQUIPMENT_CANDIDATE_JAR']),java);self.addCleanup(new.close)
        package=old('package',{'hash':'','packageName':'fixture','actions':{},'control':{},'carControls':{'motor':{'brake':{'regenerativeForce':30000.0}}}})
        current=new('package',package)
        self.assertNotEqual(package['hash'],current['hash'])
        self.assertEqual(350.0,current['carControls']['motor']['equipment']['nominalCylinderPressureKpa'])
        self.assertEqual(30000.0,current['carControls']['motor']['brake']['regenerativeForce'])
        self.assertEqual(current,new('package',current))
        model=old('model',{'hash':'','car':{'name':'fixture','length':10.0},'parts':{}})
        converted=new('model',model)
        # CarDefinition.control is transient: render identity remains independent of equipment.
        self.assertEqual(model['hash'],converted['hash'])
        self.assertEqual(converted,new('model',converted))
        new('train-ref',{'packageName':'fixture','packageSnapshotHash':current['hash']})
        new('car-ref',{'carModelName':'motor','packageSnapshotHash':current['hash'],'modelSnapshotHash':converted['hash']})
        # Exercise audited inspect/apply using actual package/model/render-payload/outer codecs.
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);instance=root/'instance';instance.mkdir()
            roots={key:instance/key for key in ('packages','models','worlds')}
            for path in roots.values():path.mkdir()
            (roots['packages']/(package['hash']+'.json')).write_bytes(output_json(package))
            (roots['models']/(model['hash']+'.json')).write_bytes(output_json(model))
            core={'trains':[{'extension':{'BUKKIT':{'typeId':'javarailways:train_render_state','schemaVersion':1,
                'payload':{'packageName':'fixture','packageSnapshotHash':package['hash']}}},
                'cars':[{'config':{'extension':{'BUKKIT':{'typeId':'javarailways:car_render_state','schemaVersion':1,
                    'payload':{'carModelName':'motor','packageSnapshotHash':package['hash'],'modelSnapshotHash':model['hash']}}}}}]}]}
            runtime={'formatVersion':2,'infrastructureRevision':0,'physicsEpoch':0,
                'coreSnapshotJson':json.dumps(core),'basicSnapshotJson':'{}','simpleAtoSnapshotJson':'{}'}
            for suffix in ('-runtime.json','-runtime.json.bak'):
                (roots['worlds']/('fixture'+suffix)).write_bytes(output_json(runtime))
            plan,originals,outputs=m.inspect(roots,old,new)
            self.assertEqual(4,plan['referenceChanges'])
            self.assertEqual(originals['models'],outputs['models'])
            with patch.object(m,'ensure_stopped'):
                m.apply(roots,plan,root/'backup',old,new,instance)
            self.assertTrue(all(item['action']=='unchanged' for item in m.inspect(roots,old,new)[0]['files']))


if __name__=='__main__':unittest.main()

class S100ReplacementTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.old=self.root/'old.js';self.old.write_text('old default\n')
        self.new=self.root/'new.js';self.new.write_text('new default\n')
        self.policy=m.S100BehaviorReplacement([self.old],self.new)
        self.value=dict(packageName='s100',behavior=dict(fileName='behavior.js',source=self.old.read_text()),actions={'custom':'unchanged'},control={'maxSpeed':42})
    def test_exact_replacement_preserves_actions_and_controls(self):
        result=self.policy.convert(self.value)
        self.assertEqual(self.new.read_text(),result['behavior']['source'])
        self.assertEqual(self.value['actions'],result['actions']);self.assertEqual(self.value['control'],result['control'])
        self.assertEqual(self.old.read_text(),self.value['behavior']['source'])
        self.assertEqual(result,self.policy.convert(result))
    def test_unknown_custom_script_is_not_overwritten(self):
        self.value['behavior']['source']='custom script'
        with self.assertRaisesRegex(ValueError,'exact reviewed'):self.policy.convert(self.value)
    def test_unrelated_packages_unchanged(self):
        self.value['packageName']='custom'
        self.assertEqual(self.value,self.policy.convert(self.value))
    def test_changed_replacement_scripts_are_detected(self):
        self.new.write_text('changed')
        with self.assertRaisesRegex(ValueError,'pinned'):self.policy.convert(self.value)

class S100SnapshotMigrationTest(unittest.TestCase):
    setUp = MigrationTest.setUp
    envelope = MigrationTest.envelope
    def test_behavior_rehash_updates_both_runtime_refs_and_converges(self):
        old=self.root/'old.js';new=self.root/'new.js'
        old.write_text('old default');new.write_text('new default')
        policy=m.S100BehaviorReplacement([old],new)
        package=self.source('package',dict(packageName='s100',behavior=dict(fileName='behavior.js',source='old default'),actions={'keep':'action'},control={'keep':'control'}))
        (self.roots['packages']/(self.package['hash']+'.json')).unlink()
        (self.roots['packages']/(package['hash']+'.json')).write_bytes(output_json(package))
        for envelope in [self.core['trains'][0]['extension']['BUKKIT'],self.core['trains'][0]['cars'][0]['config']['extension']['BUKKIT']]:
            envelope['payload']['packageSnapshotHash']=package['hash']
        for suffix in ['-runtime.json','-runtime.json.bak']:
            path=self.roots['worlds']/('world'+suffix)
            value=json.loads(path.read_bytes());value['coreSnapshotJson']=json.dumps(self.core);path.write_bytes(output_json(value))
        plan,_,outputs=m.inspect(self.roots,self.source,self.candidate,policy)
        changed=next(iter(outputs['packages'].values()));value=json.loads(changed)
        self.assertEqual('new default',value['behavior']['source'])
        self.assertEqual(package['actions'],value['actions']);self.assertEqual(package['control'],value['control'])
        self.assertEqual(6,plan['referenceChanges'])
        with patch.object(m,'ensure_stopped'):
            m.apply(self.roots,plan,self.root/'backup',self.source,self.candidate,self.instance,policy)
        after,_,_=m.inspect(self.roots,self.source,self.candidate,policy)
        self.assertTrue(all(item['action']=='unchanged' for item in after['files']))

class PrecompiledHelperReceiptTest(unittest.TestCase):
    def test_receipt_binds_jar_helper_source_and_compiled_class(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);jar=root/'source.jar';jar.write_bytes(b'fixture jar')
            classes=root/'classes';classes.mkdir();helper=classes/'EquipmentSnapshotsMigrationBridge.class';helper.write_bytes(b'fixture class')
            source=Path(m.__file__).with_name('EquipmentSnapshotsMigrationBridge.java')
            receipt=dict(jarSha256=digest(jar.read_bytes()),helperSourceSha256=digest(source.read_bytes()),helperSha256=digest(helper.read_bytes()))
            (classes/'receipt.json').write_bytes(output_json(receipt))
            with m.Bridge(jar,bridge_classes=classes) as bridge:bridge.verify()
            for key in receipt:
                bad=dict(receipt);bad[key]='invalid';(classes/'receipt.json').write_bytes(output_json(bad))
                with self.assertRaisesRegex(ValueError,'receipt mismatch'):m.Bridge(jar,bridge_classes=classes)
