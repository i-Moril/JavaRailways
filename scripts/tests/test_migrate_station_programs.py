import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("migration", pathlib.Path(__file__).parents[1] / "migrate_station_programs.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)

class StationMigrationTest(unittest.TestCase):
    def document(self):
        return {"formatVersion": 1, "runs": [{"id": "run", "calls": [{"id": "call", "stationId": "a", "status": "TERMINAL", "arrivalProgram": {"parameters": [], "steps": [{"id": "wait", "type": "WAIT_DURATION", "durationTicks": {"value": 10}}]}}]}]}

    def test_conversion_keeps_original_and_orders_old_actions_after_open(self):
        source = self.document()
        result, changes, blocked = migration.convert(source)
        self.assertFalse(blocked)
        self.assertTrue(changes)
        self.assertIn("arrivalProgram", source["runs"][0]["calls"][0])
        self.assertNotIn("arrivalProgram", result["runs"][0]["calls"][0])
        self.assertEqual([s["type"] for s in result["stationPrograms"][0]["arrival"]["steps"]], ["OPEN_DOORS", "WAIT_DURATION", "END"])
        self.assertEqual(result["formatVersion"], 1)

    def test_conflicting_missing_override_requires_explicit_resolution(self):
        source = self.document()
        source["runs"][0]["calls"].append({"id": "other", "stationId": "a", "status": "STOP"})
        _, _, blocked = migration.convert(source)
        self.assertTrue(any("conflicting" in error for error in blocked))

    def test_dynamic_condition_is_not_silently_reinterpreted(self):
        source = self.document()
        source["runs"][0]["calls"][0]["arrivalProgram"]["steps"] = [{"id": "dynamic", "type": "BRANCH", "typeId": "javarailways:train_model", "thenSteps": [], "elseSteps": []}]
        _, _, blocked = migration.convert(source)
        self.assertTrue(any("runtime condition" in error for error in blocked))
