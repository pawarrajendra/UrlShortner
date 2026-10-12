"""Phase 2 lifecycle on a generated Compose project; no existing data is used."""

from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

spec = spec_from_file_location("phase1_smoke", Path(__file__).with_name("phase1-smoke.py"))
smoke = module_from_spec(spec)
spec.loader.exec_module(smoke)

if __name__ == "__main__":
    smoke.main(core=True)
