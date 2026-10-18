import copy
import importlib.util
import json
from pathlib import Path
import random
import sys
import subprocess
import tempfile
import unittest

MODULES=Path(__file__).resolve().parents[1]/'model-coordinate-migration'
sys.path.insert(0,str(MODULES))
import math3d as m
import migrate as migration


class CoordinateMigrationTest(unittest.TestCase):
    def assertVector(self,a,b):
        for x,y in zip(a,b): self.assertAlmostEqual(x,y,places=6)

    def test_euler_noncommuting_and_gimbal(self):
        q=m.mul(m.C,m.euler((90,0,90)))
        self.assertVector(m.rotate(q,(.2,.3,.4)),m.rotate(m.euler((90,0,180)),(.2,.3,.4)))
        for values in [(23,90,49),(23,-90,49),(0,0,0)]+[tuple(random.Random(i*3+j).uniform(-180,180) for j in range(3)) for i in range(100)]:
            q=m.euler(values); restored=m.euler(m.angles(q))
            for axis in [(1,0,0),(0,1,0),(0,0,1)]: self.assertVector(m.rotate(q,axis),m.rotate(restored,axis))

    def test_rail_nonuniform_scale_preserves_geometry(self):
        old={'offset':{'x':1,'y':2,'z':3},'rotation':{'x':37,'y':21,'z':-54},'scale':{'x':2,'y':3,'z':4},'item':{'type':'PAPER'}}
        new=copy.deepcopy(old); migration.rail_element(new)
        for point in [(1,2,3),(-2,.3,1)]:
            scaled=tuple(x*y for x,y in zip(point,(2,3,4)))
            a=m.rotate(m.euler(migration.xyz(old['rotation'])),scaled)
            b=m.rotate(m.euler(migration.xyz(new['rotation'])),scaled)
            self.assertVector(m.vector(tuple(x+y for x,y in zip(a,(1,2,3)))),tuple(x+y for x,y in zip(b,migration.xyz(new['offset']))))
        self.assertEqual(old['scale'],new['scale']); self.assertEqual(old['item'],new['item'])

    def test_signal_ground_and_rail(self):
        q=m.euler((13,24,35)); point=(2,3,4)
        for ground in (True,False):
            p=dict(x=1,y=2,z=3,**dict(zip(('rotation'+k for k in 'XYZW'),q)))
            migration.pose(p,ground)
            nq=tuple(p['rotation'+k] for k in 'XYZW')
            after=m.rotate(nq,m.vector(point))
            before=m.rotate(q,point)
            self.assertVector(after,before if ground else m.vector(before))
            self.assertVector(migration.xyz(p),(1,2,3) if ground else (-3,2,1))

    def test_definitions_and_wand_preserve_other_fields(self):
        p={'kind':'ground','pose':dict(x=1,y=2,z=3,rotationX=0,rotationY=0,rotationZ=0,rotationW=1)}
        d={'schemaVersion':1,'revision':17,'fixedBlockSignals':[{'revision':8,'placement':p}]}
        migration.definitions(d); self.assertEqual(d['revision'],17); self.assertEqual(d['fixedBlockSignals'][0]['revision'],8)
        wand={'railPreset':dict(x=1,y=2,z=3,rotationX=0,rotationY=0,rotationZ=0,rotationW=1,modelId='a:b'),'nested':{'keep':[1,2,3]}}
        out=json.loads(migration.transform_wand_state(json.dumps(wand)))
        self.assertEqual(out['nested'],wand['nested']); self.assertEqual(out['railPreset']['x'],-3)

    def test_model_root_and_box(self):
        parts=[{'type':'item_display','scale':{'x':2},'imports':['shared']}]
        d={'parts':parts,'selection-boxes':[{'min':[1,2,3],'max':[4,5,6]}]}
        migration.model(d); self.assertEqual(d['parts'][0]['parts'],parts)
        self.assertEqual(d['selection-boxes'][0],{'min':[-6,2,1],'max':[-3,5,4]})
        with self.assertRaises(ValueError): migration.model({'imports':['other']})

    def test_render_hash_and_geometry_metadata(self):
        d={'version':2,'templates':[{'templateId':'a','repeatLength':2,'elements':[{'offsetX':1,'offsetY':2,'offsetZ':3,'scaleX':2,'scaleY':3,'scaleZ':4}]}], 'rails':[{'templateId':'a','railId':'r','segments':[{'arcStart':1}]}]}
        seg=copy.deepcopy(d['rails'][0]['segments']); migration.render(d)
        self.assertEqual(d['rails'][0]['templateId'],d['templates'][0]['templateId']); self.assertEqual(seg,d['rails'][0]['segments'])
        self.assertEqual(len(d['templates'][0]['templateId']),64)

    def test_refuse_existing_or_nested_output_before_planning(self):
        with tempfile.TemporaryDirectory() as td:
            source=Path(td)/'source'; source.mkdir()
            existing=Path(td)/'existing'; existing.mkdir()
            for output in (existing,source/'nested'):
                result=subprocess.run([sys.executable,str(MODULES/'migrate.py'),'--source',str(source),'--output',str(output)],capture_output=True)
                self.assertNotEqual(result.returncode,0)
                self.assertIn(b'Output must be a new directory outside source',result.stderr)
            self.assertFalse((source/'nested').exists())

    def test_signalpack_import_refused_and_nonfinite_rejected(self):
        with self.assertRaises(ValueError): migration.model({'parts':[{'parts':[{'import':'a:b'}]}]},True)
        with self.assertRaises(ValueError): migration.model({})
        with self.assertRaises(ValueError): migration.pose(dict(rotationW=2))
        with self.assertRaises(ValueError): migration.rail_element({'offset':{'x':float('inf')}})

    @unittest.skipUnless(importlib.util.find_spec('yaml'),'PyYAML required')
    def test_patch_source_unchanged_primary_backup(self):
        with tempfile.TemporaryDirectory() as td:
            source=Path(td); folder=source/'plugins/SimpleSignals/worlds'; folder.mkdir(parents=True)
            data={'schemaVersion':1,'fixedBlockSignals':[{'placement':{'kind':'ground','pose':dict(x=1,y=2,z=3,rotationX=0,rotationY=0,rotationZ=0,rotationW=1)}}]}
            for name in ('x-definitions.json','x-definitions.json.bak'): (folder/name).write_text(json.dumps(data))
            before={p:p.read_bytes() for p in folder.iterdir()}; changes,report=migration.plan(source)
            self.assertEqual(len(changes),2)
            for p,raw in before.items(): self.assertEqual(p.read_bytes(),raw)
            (source/'link').symlink_to(folder,target_is_directory=True)
            with self.assertRaises(ValueError): migration.plan(source)

if __name__=='__main__': unittest.main()
