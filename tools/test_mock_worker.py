import json
from pathlib import Path
import tempfile
import unittest
import mock_worker


class ProtocolTests(unittest.TestCase):
    def test_only_published_requests_are_consumed_and_never_claim_rendering(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'inbox').mkdir()
            (root/'inbox/request01.json').write_text('{"kind":"area","a":[0,80,0],"b":[1,80,1]}')
            (root/'inbox/request02.pending').write_text('{"kind":"area"}')
            mock_worker.once(root)
            result=json.loads((root/'results/request01.json').read_text())
            self.assertEqual(result['stage'],'error');self.assertIn('no rendering',result['error'])
            self.assertFalse((root/'results/request02.json').exists())
            self.assertEqual(mock_worker.once(root),[])

    def test_cancellation_and_symlink_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'inbox').mkdir();(root/'cancel').mkdir()
            (root/'inbox/request01.json').write_text('{"kind":"camera"}')
            (root/'cancel/request01').touch()
            (root/'inbox/request02.json').symlink_to(root/'inbox/request01.json')
            items=mock_worker.once(root)
            self.assertEqual(items[0]['stage'],'cancelled');self.assertEqual(len(items),1)
