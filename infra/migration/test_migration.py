import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("mig", pathlib.Path(__file__).with_name("nextcloud-users-to-keycloak.py"))
mig = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mig)
HERE = pathlib.Path(__file__).parent / "beispiel"


class PlanTest(unittest.TestCase):
    def test_beispiel(self):
        create, problems, skipped = mig.plan(mig.load_users(HERE / "users.json"), mig.load_mapping(HERE / "group-mapping.csv"))
        self.assertEqual([u["username"] for u in create], ["philipp.alt", "jonas"])
        self.assertEqual(create[0]["groups"], ["/ahv-vorstand", "/alte-herren"])
        self.assertEqual((create[0]["firstName"], create[0]["lastName"]), ("Philipp von", "Alt"))
        self.assertEqual({p[0] for p in problems}, {"Max Müller", "kasse"})
        self.assertEqual({s[0] for s in skipped}, {"hans", "bursch"})

    def test_unbekannte_gruppe_wird_gemeldet(self):
        create, _, _ = mig.plan([{"user_id": "a", "email": "a@x.de", "groups": ["Chor"]}], {})
        self.assertEqual(create[0]["unmapped"], ["Chor"])


if __name__ == "__main__":
    unittest.main()
