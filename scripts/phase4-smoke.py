"""Upgrade saved Phase 3/V2 to Phase 4/V3 and verify committed analytics in Docker."""
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
spec = spec_from_file_location("foundation_smoke", Path(__file__).with_name("phase1-smoke.py"))
smoke = module_from_spec(spec)
spec.loader.exec_module(smoke)
if __name__ == "__main__":
    smoke.main(core=True, brownfield=True, analytics=True)
